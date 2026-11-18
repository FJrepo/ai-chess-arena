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
        assertFailure(401, "{\"error\":{\"code\":401,\"message\":\"No auth credentials found\"}}", ProviderOutcome.FATAL,
                "OpenRouter rejected the API key (HTTP 401): No auth credentials found");
        assertFailure(402, "{\"error\":{\"code\":402,\"message\":\"Insufficient credits\"}}", ProviderOutcome.FATAL,
                "OpenRouter account has insufficient credits (HTTP 402): Insufficient credits");
        assertFailure(404, "{\"error\":{\"code\":404,\"message\":\"No such model\"}}", ProviderOutcome.FATAL,
                "Model not found on OpenRouter (HTTP 404): No such model");
    }

    /** Rule B: only a complete error document with a status from the non-billable allowlist is a plain failure. */
    @Test
    void errorAnswersThatDoNotProveNothingWasChargedAreDeliveryUncertain() throws IOException {
        String document = "{\"error\":{\"code\":500,\"message\":\"Something happened\"}}";
        for (var answer : List.of(
                java.util.Map.entry(404, "not json"),
                java.util.Map.entry(429, "{}"),
                java.util.Map.entry(429, "{\"error\":{}}"),
                java.util.Map.entry(503, "{\"error\":{\"message\":\"no code\"}}"),
                java.util.Map.entry(503, "{\"error\":{\"code\":503}}"),
                java.util.Map.entry(503, document + " trailing garbage"),
                java.util.Map.entry(503, document + " {\"second\":1}"),
                java.util.Map.entry(500, ""),
                java.util.Map.entry(503, "<html>bad gateway</html>"),
                java.util.Map.entry(408, document),
                java.util.Map.entry(418, document),
                java.util.Map.entry(524, document))) {
            stopServer();
            LlmResponse response = chat(serviceAnswering(answer.getKey(), answer.getValue()));
            assertTrue(response.isProviderFailure(), "HTTP " + answer.getKey());
            assertTrue(response.deliveryUncertain(), "HTTP " + answer.getKey() + " " + answer.getValue());
        }
        for (int status : OpenRouterService.NON_BILLABLE_STATUSES) {
            stopServer();
            LlmResponse response = chat(serviceAnswering(status, document));
            assertTrue(response.isProviderFailure(), "HTTP " + status);
            assertFalse(response.deliveryUncertain(), "HTTP " + status);
        }
        assertEquals(java.util.Set.of(400, 401, 402, 403, 404, 413, 422, 429, 500, 502, 503, 504),
                OpenRouterService.NON_BILLABLE_STATUSES);
    }

    @Test
    void rateLimitsAndServerErrorsAreRetryable() throws IOException {
        assertFailure(429, "{\"error\":{\"code\":429,\"message\":\"Rate limited\"}}", ProviderOutcome.RETRYABLE,
                "OpenRouter rate limit reached (HTTP 429): Rate limited");
        assertFailure(500, "{\"error\":{\"code\":500,\"message\":\"Internal\"}}", ProviderOutcome.RETRYABLE,
                "OpenRouter server error (HTTP 500): Internal");
        assertFailure(503, "{\"error\":{\"code\":503,\"message\":\"upstream down\"}}", ProviderOutcome.RETRYABLE,
                "OpenRouter server error (HTTP 503): upstream down");
    }

    @Test
    void everySuccessStatusWithoutAValidCompletionIsDeliveryUncertain() throws IOException {
        assertUncertain(200, "<html>gateway</html>", "OpenRouter returned a malformed response");
        assertUncertain(200, "{\"choices\":[]}", "OpenRouter returned a response without choices");
        assertUncertain(200, "{\"choices\":[{}]}", "OpenRouter returned a malformed choice");
        assertUncertain(200, "{\"error\":{\"message\":\"Provider returned error\",\"code\":502}}",
                "OpenRouter upstream error: Provider returned error");
    }

    @Test
    void anErrorInsideASuccessfulAnswerIsDeliveryUncertainAndKeepsItsUsage() throws IOException {
        String usage = ",\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":3,\"cost\":0.002}}";
        for (String choice : List.of(
                "{\"message\":{\"content\":\"{\\\"move\\\":\\\"e\"},\"finish_reason\":\"error\","
                        + "\"error\":{\"code\":502,\"message\":\"Upstream stopped\"}}",
                "{\"message\":{\"content\":\"{\\\"move\\\":\\\"e4\\\"}\"},\"finish_reason\":\"error\"}",
                "{\"message\":{\"content\":\"partial\"},\"error\":{\"code\":500,\"message\":\"Stopped\"}}")) {
            stopServer();
            LlmResponse response = chat(serviceAnswering(200, "{\"choices\":[" + choice + "]" + usage));
            assertTrue(response.isProviderFailure(), choice);
            assertTrue(response.deliveryUncertain(), choice);
            assertNull(response.content());
            assertEquals(0.002, response.costUsd(), "usage is preserved");
            assertEquals(3, response.completionTokens());
        }
        // A valid completion followed by more content is not a valid answer either.
        stopServer();
        LlmResponse trailing = chat(serviceAnswering(200,
                "{\"choices\":[{\"message\":{\"content\":\"e4\"}}]} {\"choices\":[]}"));
        assertTrue(trailing.deliveryUncertain());
    }

    private void assertUncertain(int status, String body, String detail) throws IOException {
        assertFailure(status, body, ProviderOutcome.RETRYABLE, detail);
        stopServer();
        assertTrue(chat(serviceAnswering(status, body)).deliveryUncertain(), body);
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
        assertFalse(response.deliveryUncertain(), "a refused connection never delivered the request");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void aConnectionLostAfterTheRequestWasSentIsDeliveryUncertain(boolean truncatedAnswer) throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            Thread provider = Thread.startVirtualThread(() -> {
                try (var connection = socket.accept()) {
                    readRequest(connection.getInputStream());
                    requests.incrementAndGet();
                    if (truncatedAnswer) {
                        OutputStream out = connection.getOutputStream();
                        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 500\r\n\r\n"
                                + "{\"choices\":[").getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                    }
                } catch (IOException ignored) {
                    // The client sees the dropped connection.
                }
            });
            OpenRouterService service = service("http://127.0.0.1:" + socket.getLocalPort());

            LlmResponse response = service.chat("model-a", List.of(new OpenRouterService.ChatMsg("user", "move")),
                    in(Duration.ofSeconds(10)), 100);
            provider.join(5_000);

            assertEquals(1, requests.get());
            assertEquals(ProviderOutcome.RETRYABLE, response.outcome());
            assertTrue(response.deliveryUncertain(), response.errorMessage());
            assertFalse(response.timedOut());
        }
    }

    @Test
    void aTimeoutWhileTheClientStillHoldsTheBodyIsPossiblyCharged() throws Exception {
        // The client took the final body buffer but has not completed the publisher (HTTP/2 can already have sent
        // END_STREAM at this point): OpenRouter may have the whole request.
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        try {
            for (boolean timedTurn : List.of(true, false)) {
                OpenRouterService service = service("http://127.0.0.1:9");
                service.chatSender = (request, handler) -> holdAfterFinalBuffer(request, release);
                service.requestSafetyCap = Duration.ofMillis(300);

                LlmResponse response = service.chat("model-a", List.of(new OpenRouterService.ChatMsg("user", "move")),
                        timedTurn ? in(Duration.ofMillis(200)) : null, 100);

                assertEquals(timedTurn ? ProviderOutcome.TIMEOUT : ProviderOutcome.RETRYABLE, response.outcome());
                assertTrue(response.deliveryUncertain(), "timed turn: " + timedTurn);
            }
        } finally {
            release.countDown();
        }
    }

    static java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<String>> holdAfterFinalBuffer(
            java.net.http.HttpRequest request, java.util.concurrent.CountDownLatch release) {
        Thread.startVirtualThread(() -> request.bodyPublisher().orElseThrow().subscribe(
                new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
                    @Override public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
                        subscription.request(1);
                    }

                    @Override public void onNext(java.nio.ByteBuffer item) {
                        try {
                            release.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }

                    @Override public void onError(Throwable throwable) { }

                    @Override public void onComplete() { }
                }));
        return new java.util.concurrent.CompletableFuture<>();
    }

    /** Reads one HTTP/1.1 request with a Content-Length body. */
    private static void readRequest(java.io.InputStream in) throws IOException {
        StringBuilder head = new StringBuilder();
        while (!head.toString().endsWith("\r\n\r\n")) {
            int b = in.read();
            if (b < 0) throw new IOException("request ended early");
            head.append((char) b);
        }
        int length = 0;
        for (String line : head.toString().split("\r\n")) {
            if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                length = Integer.parseInt(line.substring(15).trim());
            }
        }
        in.readNBytes(length);
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
        // No headers yet, but the request had reached the server: it may be charged.
        assertTrue(response.deliveryUncertain());
    }

    @Test
    void slowlyStreamedBodyTimesOutAtTheDeadlineNotWhenTheBodyEnds() throws IOException {
        // Headers arrive at once, the body trickles in over about 3 s: the old request timeout never fired.
        OpenRouterService service = serviceStreamingSlowly(10, 300);

        long start = System.nanoTime();
        LlmResponse response = service.chat("model-a", List.of(new OpenRouterService.ChatMsg("user", "move")),
                in(Duration.ofMillis(700)), 100);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        // The model's timeout; the answer had started, so its cost is unknown (possibly charged).
        assertEquals(ProviderOutcome.TIMEOUT, response.outcome());
        assertTrue(response.timedOut());
        assertFalse(response.isProviderFailure());
        assertTrue(response.deliveryUncertain());
        assertEquals("No complete answer within the move time; the request may have been charged",
                response.errorMessage());
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
        assertEquals("OpenRouter started the answer, but it did not complete in time", response.errorMessage());
        assertTrue(response.deliveryUncertain());
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
        assertTrue(response.deliveryUncertain(), "the request was sent, so it may be charged");
    }

    @Test
    void errorStatusWithAStalledBodyIsAnIncompleteAnswerNotATimeout() throws IOException {
        // Status and headers arrive at once, the error body would take about 3 s.
        String errorBody = "{\"error\":{\"code\":503,\"message\":\"upstream down\"}}";
        for (int status : new int[] {503, 429}) {
            stopServer();
            OpenRouterService service = serviceStreaming(status, errorBody, 10, 300);

            LlmResponse response = service.chat("model-a", List.of(new OpenRouterService.ChatMsg("user", "move")),
                    in(Duration.ofMillis(500)), 100);

            assertEquals(ProviderOutcome.RETRYABLE, response.outcome(), "HTTP " + status);
            assertEquals(status, response.httpStatus());
            assertFalse(response.timedOut());
            assertTrue(response.deliveryUncertain(), "an incomplete error response proves nothing");
            assertTrue(response.isProviderFailure());
        }

        stopServer();
        OpenRouterService service = serviceStreaming(401, errorBody, 10, 300);
        LlmResponse unauthorized = service.chat("model-a", List.of(new OpenRouterService.ChatMsg("user", "move")),
                in(Duration.ofMillis(500)), 100);
        assertTrue(unauthorized.deliveryUncertain());
        assertFalse(unauthorized.timedOut());
        assertEquals("OpenRouter rejected the API key (HTTP 401); the error response was incomplete",
                unauthorized.errorMessage());
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
        assertTrue(response.deliveryUncertain(), "the answer had started");
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
        assertTrue(response.deliveryUncertain(), "late, not used, possibly charged");
        assertNull(response.content());
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
