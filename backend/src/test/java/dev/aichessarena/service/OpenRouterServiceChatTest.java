package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.aichessarena.config.OpenRouterKeyStoreFixture;
import dev.aichessarena.service.OpenRouterService.LlmResponse;
import dev.aichessarena.service.OpenRouterService.ProviderOutcome;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Contract tests: how OpenRouter HTTP outcomes are classified for the game engine. */
class OpenRouterServiceChatTest {

    private HttpServer server;
    private volatile String lastRequestBody;
    private final java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void successfulCompletionIsOk() throws IOException {
        OpenRouterService service = serviceAnswering(200,
                "{\"choices\":[{\"message\":{\"content\":\"{\\\"move\\\":\\\"e4\\\"}\"}}],"
                        + "\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":4,\"cost\":0.0001}}");

        LlmResponse response = chat(service);

        assertEquals(ProviderOutcome.OK, response.outcome());
        assertEquals("{\"move\":\"e4\"}", response.content());
        assertEquals(12, response.promptTokens());
        assertFalse(response.isProviderFailure());
    }

    @Test
    void requestCarriesTheGivenOutputTokenLimit() throws IOException {
        OpenRouterService service = serviceAnswering(200, "{\"choices\":[{\"message\":{\"content\":\"e4\"}}]}");

        service.chat("model-a", List.of(new OpenRouterService.ChatMsg("user", "move")),
                in(Duration.ofSeconds(5)), 8192);

        assertEquals(8192, new ObjectMapper().readTree(lastRequestBody).path("max_tokens").asInt());
    }

    @Test
    void threeArgumentChatUsesTheConfiguredDefaultLimit() throws IOException {
        OpenRouterService service = serviceAnswering(200, "{\"choices\":[{\"message\":{\"content\":\"e4\"}}]}");

        chat(service);

        assertEquals(100, new ObjectMapper().readTree(lastRequestBody).path("max_tokens").asInt());
    }

    @Test
    void finishReasonAndReasoningTokensAreParsed() throws IOException {
        OpenRouterService service = serviceAnswering(200,
                "{\"choices\":[{\"message\":{\"content\":null},\"finish_reason\":\"length\"}],"
                        + "\"usage\":{\"prompt_tokens\":300,\"completion_tokens\":4096,"
                        + "\"completion_tokens_details\":{\"reasoning_tokens\":4000},\"cost\":0.02}}");

        LlmResponse response = chat(service);

        assertEquals(ProviderOutcome.OK, response.outcome());
        assertEquals("length", response.finishReason());
        assertEquals(4000, response.reasoningTokens());
        assertEquals(4096, response.completionTokens());
        assertTrue(response.truncated());
        assertFalse(response.isProviderFailure());
    }

    @Test
    void normalStopIsNotTruncatedAndMissingReasoningTokensStayNull() throws IOException {
        OpenRouterService service = serviceAnswering(200,
                "{\"choices\":[{\"message\":{\"content\":\"e4\"},\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":1}}");

        LlmResponse response = chat(service);

        assertEquals("stop", response.finishReason());
        assertNull(response.reasoningTokens());
        assertFalse(response.truncated());
    }

    @Test
    void emptyModelContentStaysTheModelsFault() throws IOException {
        OpenRouterService service = serviceAnswering(200, "{\"choices\":[{\"message\":{\"content\":null}}]}");

        LlmResponse response = chat(service);

        assertEquals(ProviderOutcome.OK, response.outcome());
        assertNull(response.content());
    }

    @Test
    void authAndCreditFailuresAreFatal() throws IOException {
        assertFailure(401, "{\"error\":{\"message\":\"No auth credentials found\"}}", ProviderOutcome.FATAL,
                "OpenRouter rejected the API key (HTTP 401): No auth credentials found");
        assertFailure(402, "{\"error\":{\"message\":\"Insufficient credits\"}}", ProviderOutcome.FATAL,
                "OpenRouter account has insufficient credits (HTTP 402): Insufficient credits");
        assertFailure(404, "not json", ProviderOutcome.FATAL, "Model not found on OpenRouter (HTTP 404)");
    }

    @Test
    void rateLimitsAndServerErrorsAreRetryable() throws IOException {
        assertFailure(429, "{}", ProviderOutcome.RETRYABLE, "OpenRouter rate limit reached (HTTP 429)");
        assertFailure(500, "", ProviderOutcome.RETRYABLE, "OpenRouter server error (HTTP 500)");
        assertFailure(503, "{\"error\":{\"message\":\"upstream down\"}}", ProviderOutcome.RETRYABLE,
                "OpenRouter server error (HTTP 503): upstream down");
    }

    @Test
    void malformedOrChoicelessSuccessBodiesAreRetryable() throws IOException {
        assertFailure(200, "<html>gateway</html>", ProviderOutcome.RETRYABLE, "OpenRouter returned a malformed response");
        assertFailure(200, "{\"choices\":[]}", ProviderOutcome.RETRYABLE, "OpenRouter returned a response without choices");
        assertFailure(200, "{\"choices\":[{}]}", ProviderOutcome.RETRYABLE, "OpenRouter returned a malformed choice");
        assertFailure(200, "{\"error\":{\"message\":\"Provider returned error\",\"code\":502}}", ProviderOutcome.RETRYABLE,
                "OpenRouter upstream error: Provider returned error");
    }

    @Test
    void unreachableServerIsRetryable() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        OpenRouterService service = service("http://127.0.0.1:" + closedPort);

        LlmResponse response = chat(service);

        assertEquals(ProviderOutcome.RETRYABLE, response.outcome());
        assertTrue(response.errorMessage().startsWith("Could not reach OpenRouter"), response.errorMessage());
        assertFalse(response.timedOut());
    }

    @Test
    void slowModelIsATimeoutNotAProviderFailure() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/chat/completions", exchange -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        OpenRouterService service = service("http://127.0.0.1:" + server.getAddress().getPort());

        LlmResponse response = service.chat("model-a", List.of(new OpenRouterService.ChatMsg("user", "move")),
                Duration.ofMillis(200));

        assertEquals(ProviderOutcome.TIMEOUT, response.outcome());
        assertTrue(response.timedOut());
        assertFalse(response.isProviderFailure());
    }

    @Test
    void slowlyStreamedBodyTimesOutAtTheDeadlineNotWhenTheBodyEnds() throws IOException {
        // Headers arrive at once, the body trickles in over about 3 s: the old request timeout never fired.
        OpenRouterService service = serviceStreamingSlowly(10, 300);

        long start = System.nanoTime();
        LlmResponse response = service.chat("model-a", List.of(new OpenRouterService.ChatMsg("user", "move")),
                in(Duration.ofMillis(700)), 100);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertEquals(ProviderOutcome.TIMEOUT, response.outcome());
        assertTrue(response.timedOut());
        assertFalse(response.isProviderFailure());
        assertTrue(elapsedMs >= 650 && elapsedMs < 1_800, "elapsed " + elapsedMs + " ms");
    }

    @Test
    void streamedBodyThatFinishesBeforeTheDeadlineSucceeds() throws IOException {
        OpenRouterService service = serviceStreamingSlowly(3, 50);

        LlmResponse response = service.chat("model-a", List.of(new OpenRouterService.ChatMsg("user", "move")),
                in(Duration.ofSeconds(5)), 100);

        assertEquals(ProviderOutcome.OK, response.outcome());
        assertEquals("e4", response.content());
    }

    @Test
    void withoutAMoveTimeLimitTheSafetyCapIsAProviderProblemNotAModelTimeout() throws IOException {
        OpenRouterService service = serviceStreamingSlowly(10, 300);
        service.requestSafetyCap = Duration.ofMillis(600);

        long start = System.nanoTime();
        LlmResponse response = service.chat("model-a", List.of(new OpenRouterService.ChatMsg("user", "move")),
                null, 100);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertEquals(ProviderOutcome.RETRYABLE, response.outcome());
        assertFalse(response.timedOut());
        assertTrue(response.isProviderFailure());
        assertEquals("OpenRouter request did not finish within the safety limit of 600 ms", response.errorMessage());
        assertTrue(elapsedMs >= 550 && elapsedMs < 1_800, "elapsed " + elapsedMs + " ms");
    }

    @Test
    void withoutAMoveTimeLimitAFastAnswerSucceeds() throws IOException {
        OpenRouterService service = serviceAnswering(200, "{\"choices\":[{\"message\":{\"content\":\"e4\"}}]}");

        LlmResponse response = service.chat("model-a", List.of(new OpenRouterService.ChatMsg("user", "move")),
                null, 100);

        assertEquals(ProviderOutcome.OK, response.outcome());
        assertEquals("e4", response.content());
    }

    @Test
    void aMoveTimeLimitLongerThanTheSafetyCapIsBoundedByTheCapAsAProviderProblem() throws IOException {
        OpenRouterService service = serviceStreamingSlowly(10, 300);
        service.requestSafetyCap = Duration.ofMillis(500);

        LlmResponse response = service.chat("model-a", List.of(new OpenRouterService.ChatMsg("user", "move")),
                in(Duration.ofSeconds(30)), 100);

        assertEquals(ProviderOutcome.RETRYABLE, response.outcome());
        assertFalse(response.timedOut());
    }

    @Test
    void errorStatusWithAStalledBodyKeepsTheProviderClassificationInsteadOfATimeout() throws IOException {
        // Status and headers arrive at once, the error body would take about 3 s.
        String errorBody = "{\"error\":{\"message\":\"upstream down\"}}";
        for (int status : new int[] {503, 429}) {
            stopServer();
            OpenRouterService service = serviceStreaming(status, errorBody, 10, 300);

            LlmResponse response = service.chat("model-a", List.of(new OpenRouterService.ChatMsg("user", "move")),
                    in(Duration.ofMillis(500)), 100);

            assertEquals(ProviderOutcome.RETRYABLE, response.outcome(), "HTTP " + status);
            assertEquals(status, response.httpStatus());
            assertFalse(response.timedOut());
            assertTrue(response.isProviderFailure());
        }

        stopServer();
        OpenRouterService service = serviceStreaming(401, errorBody, 10, 300);
        LlmResponse unauthorized = service.chat("model-a", List.of(new OpenRouterService.ChatMsg("user", "move")),
                in(Duration.ofMillis(500)), 100);
        assertEquals(ProviderOutcome.FATAL, unauthorized.outcome());
        assertEquals("OpenRouter rejected the API key (HTTP 401)", unauthorized.errorMessage());
    }

    @Test
    void requestPreparationCountsAgainstTheDeadline() throws IOException {
        // 400 ms of preparation plus a 300 ms answer exceed a 600 ms deadline, although the answer alone fits.
        OpenRouterService service = serviceStreamingSlowly(2, 150);
        service.objectMapper = slowObjectMapper(400);

        long start = System.nanoTime();
        LlmResponse response = service.chat("model-a", List.of(new OpenRouterService.ChatMsg("user", "move")),
                in(Duration.ofMillis(600)), 100);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertEquals(ProviderOutcome.TIMEOUT, response.outcome());
        assertTrue(elapsedMs >= 550 && elapsedMs < 1_000, "elapsed " + elapsedMs + " ms");
    }

    @Test
    void aDeadlineUsedUpByPreparationSendsNothing() throws IOException {
        OpenRouterService service = serviceStreamingSlowly(1, 0);
        service.objectMapper = slowObjectMapper(300);

        LlmResponse response = service.chat("model-a", List.of(new OpenRouterService.ChatMsg("user", "move")),
                in(Duration.ofMillis(200)), 100);

        assertEquals(ProviderOutcome.TIMEOUT, response.outcome());
        assertEquals(0, requests.get());
    }

    @Test
    void aDeadlineThatHasAlreadyPassedFailsAtOnceWithoutSending() throws IOException {
        OpenRouterService service = serviceStreamingSlowly(1, 0);

        long start = System.nanoTime();
        LlmResponse response = service.chat("model-a", List.of(new OpenRouterService.ChatMsg("user", "move")),
                OpenRouterService.AnswerDeadline.after(Duration.ofMillis(-20), System.nanoTime()), 100);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertEquals(ProviderOutcome.TIMEOUT, response.outcome());
        assertEquals(0, requests.get());
        assertTrue(elapsedMs < 100, "elapsed " + elapsedMs + " ms");
    }

    @Test
    void anAnswerCompleteBeforeTheDeadlineCountsEvenIfTheCallerLooksLater() throws IOException {
        // Fake clock: the answer is complete at 66 ms, the caller only checks at 1,517 ms (deadline 1,000 ms).
        LlmResponse response = chatWithDelayedCaller(66, 1_517, 1_000);

        assertEquals(ProviderOutcome.OK, response.outcome());
        assertEquals("e4", response.content());
    }

    @Test
    void anAnswerCompleteOnlyAfterTheDeadlineIsATimeoutEvenIfItIsThereWhenTheCallerLooks() throws IOException {
        LlmResponse response = chatWithDelayedCaller(1_200, 1_517, 1_000);

        assertEquals(ProviderOutcome.TIMEOUT, response.outcome());
        assertTrue(response.timedOut());
    }

    /**
     * Sends a request with a fake clock: the server sets the clock to {@code completeAtMs} before it answers (so the
     * answer is complete at that time), and the caller resumes only at {@code callerResumesAtMs}, after the
     * response future is done.
     */
    private LlmResponse chatWithDelayedCaller(long completeAtMs, long callerResumesAtMs, long deadlineMs)
            throws IOException {
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong();
        byte[] body = "{\"choices\":[{\"message\":{\"content\":\"e4\"}}]}".getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            clock.set(Duration.ofMillis(completeAtMs).toNanos());
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
            exchange.close();
        });
        server.start();
        OpenRouterService service = service("http://127.0.0.1:" + server.getAddress().getPort());
        service.nanoClock = clock::get;
        service.sendObserver = sent -> {
            try {
                sent.get(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            clock.set(Duration.ofMillis(callerResumesAtMs).toNanos());
        };

        return service.chat("model-a", List.of(new OpenRouterService.ChatMsg("user", "move")),
                OpenRouterService.AnswerDeadline.after(Duration.ofMillis(deadlineMs), 0L), 100);
    }

    @Test
    void modelValidationHonoursFailOpenWhenTheCatalogIsUnavailable() throws IOException {
        OpenRouterService service = serviceAnswering(503, "");
        server.createContext("/models", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });

        service.modelValidationFailOpen = true;
        assertTrue(service.isModelAllowed("openai/gpt-4o-mini"));

        service.modelValidationFailOpen = false;
        assertFalse(service.isModelAllowed("openai/gpt-4o-mini"));
    }

    @Test
    void httpStatusClassification() {
        assertEquals(ProviderOutcome.RETRYABLE, OpenRouterService.classifyHttpStatus(408));
        assertEquals(ProviderOutcome.RETRYABLE, OpenRouterService.classifyHttpStatus(429));
        assertEquals(ProviderOutcome.RETRYABLE, OpenRouterService.classifyHttpStatus(502));
        assertEquals(ProviderOutcome.FATAL, OpenRouterService.classifyHttpStatus(400));
        assertEquals(ProviderOutcome.FATAL, OpenRouterService.classifyHttpStatus(403));
    }

    private void assertFailure(int status, String body, ProviderOutcome outcome, String detail) throws IOException {
        stopServer();
        OpenRouterService service = serviceAnswering(status, body);

        LlmResponse response = chat(service);

        assertEquals(outcome, response.outcome(), "HTTP " + status);
        assertEquals(detail, response.errorMessage());
        assertNull(response.content());
        assertTrue(response.isProviderFailure());
    }

    private OpenRouterService serviceAnswering(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/chat/completions", exchange -> {
            lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
            exchange.close();
        });
        server.start();
        return service("http://127.0.0.1:" + server.getAddress().getPort());
    }

    /**
     * Sends status and headers immediately, then a valid completion in {@code chunks} pieces with
     * {@code pauseMs} between them (the way OpenRouter starts long answers early).
     */
    private OpenRouterService serviceStreamingSlowly(int chunks, long pauseMs) throws IOException {
        return serviceStreaming(200, "{\"choices\":[{\"message\":{\"content\":\"e4\"}}]}", chunks, pauseMs);
    }

    /** Like {@link #serviceStreamingSlowly}, with any status and body; counts the requests it receives. */
    private OpenRouterService serviceStreaming(int status, String text, int chunks, long pauseMs) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/chat/completions", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                int chunkSize = (body.length + chunks - 1) / chunks;
                for (int offset = 0; offset < body.length; offset += chunkSize) {
                    Thread.sleep(pauseMs);
                    out.write(body, offset, Math.min(chunkSize, body.length - offset));
                    out.flush();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // The client gave up and closed the connection.
            }
            exchange.close();
        });
        server.start();
        return service("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static OpenRouterService service(String baseUrl) {
        OpenRouterService service = new OpenRouterService();
        service.baseUrl = baseUrl;
        service.keyStore = OpenRouterKeyStoreFixture.keys("test-key", java.nio.file.Path.of("target/test-keys"));
        service.objectMapper = new ObjectMapper();
        service.defaultTemperature = 0.7;
        service.defaultMaxTokens = 100;
        return service;
    }

    private static OpenRouterService.AnswerDeadline in(Duration time) {
        return OpenRouterService.AnswerDeadline.after(time, System.nanoTime());
    }

    /** An object mapper whose serialization takes {@code delayMs}: request preparation that eats move time. */
    private static ObjectMapper slowObjectMapper(long delayMs) {
        return new ObjectMapper() {
            @Override
            public String writeValueAsString(Object value) throws com.fasterxml.jackson.core.JsonProcessingException {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.writeValueAsString(value);
            }
        };
    }

    private static LlmResponse chat(OpenRouterService service) {
        return service.chat("model-a", List.of(new OpenRouterService.ChatMsg("user", "move")), Duration.ofSeconds(5));
    }
}
