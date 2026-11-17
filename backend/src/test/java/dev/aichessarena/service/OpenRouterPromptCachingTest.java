package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.bhlangonijr.chesslib.Board;
import com.sun.net.httpserver.HttpServer;
import dev.aichessarena.config.OpenRouterKeyStoreFixture;
import dev.aichessarena.entity.ChatMode;
import dev.aichessarena.entity.MoveAttempt.Outcome;
import dev.aichessarena.repository.MoveRepository;
import dev.aichessarena.service.OpenRouterService.ChatMsg;
import dev.aichessarena.websocket.GameWebSocket;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Actual HTTP request contracts against an ephemeral loopback stub; no OpenRouter access. */
class OpenRouterPromptCachingTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> EXPLICIT_MODELS = List.of(
            "anthropic/claude-sonnet", "~anthropic/claude-sonnet-latest", "google/gemini-pro",
            "~google/gemini-pro-latest", "~google/gemini-flash-latest");
    private HttpServer server;
    private volatile String lastBody;
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger catalogRequests = new AtomicInteger();
    private String catalog = explicitCatalog();
    private int rejectionStatus;
    private String rejectionBody = "{\"error\":{\"message\":\"No endpoints found that support cache control\"}}";
    private boolean rejectPlain;
    private Runnable afterRejection = () -> { };
    private Runnable afterPlain = () -> { };

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"anthropic/claude-sonnet", "~anthropic/claude-sonnet-latest", "google/gemini-pro",
            "~google/gemini-pro-latest", "~google/gemini-flash-latest"})
    void singleBreakpointStaysBeforeTheCurrentTurnThroughoutRetries(String model) throws Exception {
        OpenRouterService service = stub();
        List<ChatMsg> history = new ArrayList<>(List.of(new ChatMsg("system", "fixed rules")));
        history.add(new ChatMsg("user", "first turn", true));
        JsonNode first = send(service, model, history);
        assertBreakpoint(first, 0, history);

        history.add(new ChatMsg("assistant", "{ \"move\": \"e4\", \"message\": \"♞\" }\n"));
        history.add(new ChatMsg("user", "second turn", true));
        JsonNode second = send(service, model, history);
        assertBreakpoint(second, 2, history);
        history.add(new ChatMsg("assistant", "bad JSON"));
        history.add(new ChatMsg("user", "parse retry"));
        assertBreakpoint(send(service, model, history), 2, history);
        history.add(new ChatMsg("assistant", "illegal move"));
        history.add(new ChatMsg("user", "legality retry"));
        assertBreakpoint(send(service, model, history), 2, history);
        assertEquals(messageText(second), messageText(send(service, model, history)).subList(0, second.size()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"openai/gpt", "x-ai/grok", "deepseek/chat", "other/anthropic/model", "~openai/gpt-latest"})
    void automaticProvidersReceiveOnlyPlainMessages(String model) throws Exception {
        OpenRouterService service = stub();
        List<ChatMsg> messages = List.of(new ChatMsg("system", "fixed rules"),
                new ChatMsg("user", "prior turn", true), new ChatMsg("assistant", "answer"),
                new ChatMsg("user", "current turn", true));
        assertPlain(send(service, model, messages), messages);
    }

    @ParameterizedTest
    @ValueSource(strings = {"anthropic/claude-sonnet", "~anthropic/claude-sonnet-latest", "google/gemini-pro", "openai/gpt"})
    void switchOffRestoresPlainStringContent(String model) throws Exception {
        OpenRouterService service = stub();
        service.promptCaching = false;
        List<ChatMsg> messages = List.of(new ChatMsg("system", "rules"), new ChatMsg("user", "turn", true),
                new ChatMsg("assistant", "illegal"), new ChatMsg("user", "retry"));
        assertPlain(send(service, model, messages), messages);
    }

    @ParameterizedTest
    @ValueSource(strings = {"anthropic/claude-sonnet", "google/gemini-pro"})
    void noStablePrefixMeansNoMarkerAndSimpleCallersCanCacheTheSystem(String model) throws Exception {
        OpenRouterService service = stub();
        List<ChatMsg> noSystem = List.of(new ChatMsg("user", "first turn", true));
        assertPlain(send(service, model, noSystem), noSystem);
        List<ChatMsg> simple = List.of(new ChatMsg("system", "rules"), new ChatMsg("user", "turn"));
        assertBreakpoint(send(service, model, simple), 0, simple);
    }

    @ParameterizedTest
    @ValueSource(strings = {"openai/gpt", "anthropic/claude-sonnet", "~anthropic/claude-sonnet-latest", "google/gemini-pro"})
    void consecutiveWireRequestsReplayTheSameTextUntilReanchoring(String model) throws Exception {
        OpenRouterService service = stub();
        List<ChatMsg> history = new ArrayList<>(List.of(new ChatMsg("system", "You are playing chess. Rules stay fixed.")));
        JsonNode previous = null;
        List<String> previousText = List.of();
        int boundaries = 0;
        for (int turn = 1; turn <= 24; turn++) {
            boolean reanchored = history.size() - 1 > 12;
            ConversationWindow.reanchor(history, 12, 4);
            if (reanchored) {
                boundaries++;
                assertEquals(5, history.size());
            }
            history.add(new ChatMsg("user", "PGN: turn " + turn + "\nFEN: position " + turn, true));
            JsonNode wire = send(service, model, history);
            List<String> text = messageText(wire);
            if (previous != null && !reanchored) {
                assertEquals(previousText, text.subList(0, previousText.size()));
                if (!EXPLICIT_MODELS.contains(model)) {
                    for (int i = 0; i < previous.size(); i++) {
                        assertEquals(previous.get(i).toString(), wire.get(i).toString(), "identical wire bytes");
                    }
                }
            }
            if (EXPLICIT_MODELS.contains(model)) {
                assertBreakpoint(wire, history.size() - 2, history);
            } else {
                assertPlain(wire, history);
            }
            if ("openai/gpt".equals(model) && turn <= 2) {
                System.out.println("Prefix example, turn " + turn + ":");
                for (int i = 0; i < history.size(); i++) {
                    ChatMsg msg = history.get(i);
                    System.out.println("  " + msg.role() + ": "
                            + msg.content().substring(0, Math.min(40, msg.content().length())).replace("\n", "\\n"));
                }
            }
            previous = wire;
            previousText = text;
            history.add(new ChatMsg("assistant", "{ \"move\": \"e4\", \"message\": \"♞\" }\n"));
        }
        assertEquals(4, boundaries);
    }

    @ParameterizedTest
    @CsvSource({"anthropic/claude-sonnet,0,true", "google/gemini-pro,0.000001,true",
            "~google/gemini-pro-latest,0,true", "~google/gemini-flash-latest,0,true",
            "google/gemma-2-27b-it,missing,false", "google/gemma-3,missing,false",
            "google/gemini-pro,null,false", "google/gemini-pro,bad,false",
            "google/gemini-pro,-1,false", "anthropic/claude-sonnet,missing,false",
            "openai/gpt,0,false"})
    void catalogWriteCapabilityIsRequiredAndReadOnlyPricingIsInsufficient(String model, String write, boolean marked)
            throws Exception {
        var entry = JSON.createObjectNode().put("id", model);
        var pricing = entry.putObject("pricing").put("input_cache_read", "0.0000001");
        if ("null".equals(write)) {
            pricing.putNull("input_cache_write");
        } else if (!"missing".equals(write)) {
            pricing.put("input_cache_write", write);
        }
        var data = JSON.createObjectNode();
        data.putArray("data").add(entry);
        catalog = data.toString();
        OpenRouterService service = stub();
        var messages = List.of(new ChatMsg("system", "rules"), new ChatMsg("user", "turn", true));
        JsonNode wire = send(service, model, messages);
        if (marked) {
            assertBreakpoint(wire, 0, messages);
        } else {
            assertPlain(wire, messages);
        }
        assertEquals(1, catalogRequests.get(), "chat must use cached evidence without catalog I/O");
    }

    @Test
    void anUnknownModelOrUnavailableCatalogUsesPlainMessages() throws Exception {
        catalog = "{\"data\":[]}";
        OpenRouterService service = stub();
        var messages = List.of(new ChatMsg("system", "rules"), new ChatMsg("user", "turn", true));
        assertPlain(send(service, "google/gemini-unknown", messages), messages);
        OpenRouterService cold = new OpenRouterService();
        cold.objectMapper = JSON;
        cold.baseUrl = service.baseUrl;
        cold.keyStore = OpenRouterKeyStoreFixture.keys("test-key", java.nio.file.Path.of("target/test-keys"));
        assertPlain(send(cold, "anthropic/claude-sonnet", messages), messages);
        assertEquals(1, catalogRequests.get());
    }

    @ParameterizedTest
    @CsvSource({"400,cache_control is unsupported", "404,No endpoints found that support cache control",
            "400,Invalid cache-control parameter"})
    void cacheRejectionRetriesTheSameAttemptPlainAndRemembersTheExactId(int status, String message) throws Exception {
        OpenRouterService service = stub();
        rejectionStatus = status;
        rejectionBody = JSON.createObjectNode().putObject("error").put("message", message).toString();
        var messages = List.of(new ChatMsg("system", "rules"), new ChatMsg("user", "turn", true));
        assertPlain(send(service, "google/gemini-pro", messages), messages);
        assertEquals(2, requests.size());
        assertBreakpoint(requests.getFirst().path("messages"), 0, messages);
        var marked = (com.fasterxml.jackson.databind.node.ObjectNode) requests.getFirst().deepCopy();
        marked.set("messages", requests.getLast().path("messages"));
        assertEquals(marked, requests.getLast(), "the fallback changes only message encoding and markers");
        // Even a refreshed catalog cannot re-enable a rejected ID during this process.
        service.listModels(false, null, OpenRouterService.NO_LIMIT, null);
        assertPlain(send(service, "google/gemini-pro", messages), messages);
        assertEquals(3, requests.size());
        assertBreakpoint(send(service, "~google/gemini-pro-latest", messages), 0, messages);
    }

    @Test
    void persistentCacheRejectionsRetryOnlyOnceAndAreNeverFatal() throws Exception {
        OpenRouterService service = stub();
        rejectionStatus = 404;
        rejectPlain = true;
        var response = service.chat("google/gemini-pro", List.of(new ChatMsg("system", "rules")));
        assertEquals(OpenRouterService.ProviderOutcome.RETRYABLE, response.outcome());
        assertEquals(2, requests.size());
    }

    @ParameterizedTest
    @CsvSource({"400,Invalid temperature", "404,Model not found", "401,cache_control unsupported"})
    void unrelatedErrorsKeepTheirClassificationAndAreNotRetried(int status, String message) throws Exception {
        OpenRouterService service = stub();
        rejectionStatus = status;
        rejectionBody = message;
        var response = service.chat("google/gemini-pro", List.of(new ChatMsg("system", "rules")));
        assertEquals(OpenRouterService.ProviderOutcome.FATAL, response.outcome());
        assertEquals(1, requests.size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void plainFallbackSharesTheOriginalMoveAndSafetyDeadlines(boolean timed) throws Exception {
        OpenRouterService service = stub();
        AtomicLong nanos = new AtomicLong();
        service.nanoClock = nanos::get;
        service.requestSafetyCap = Duration.ofSeconds(10);
        rejectionStatus = 404;
        afterRejection = () -> nanos.set(Duration.ofSeconds(9).toNanos());
        afterPlain = () -> nanos.set(Duration.ofSeconds(11).toNanos());
        var deadline = timed ? OpenRouterService.AnswerDeadline.after(Duration.ofSeconds(10), 0) : null;
        if (timed) {
            service.requestSafetyCap = Duration.ofSeconds(20);
        }
        var response = service.chat("google/gemini-pro", List.of(new ChatMsg("system", "rules")), deadline, 100);
        assertEquals(OpenRouterService.ProviderOutcome.RETRYABLE, response.outcome());
        assertFalse(response.timedOut());
        assertEquals(404, response.httpStatus());
        assertEquals(rejectionBody, response.rawResponse());
        assertTrue(response.errorMessage().contains("cache"));
        assertEquals(11_000, response.responseTimeMs());
        assertEquals(2, requests.size());
    }

    @Test
    void rejectionAtTheDeadlineRemainsAProviderFailureWithoutSendingAPlainRetry() throws Exception {
        OpenRouterService service = stub();
        AtomicLong nanos = new AtomicLong();
        service.nanoClock = nanos::get;
        rejectionStatus = 400;
        afterRejection = () -> nanos.set(Duration.ofSeconds(10).toNanos());
        var deadline = OpenRouterService.AnswerDeadline.after(Duration.ofSeconds(10), 0);

        var response = service.chat("google/gemini-pro", List.of(new ChatMsg("system", "rules")), deadline, 100);

        assertEquals(OpenRouterService.ProviderOutcome.RETRYABLE, response.outcome());
        assertFalse(response.timedOut());
        assertEquals(400, response.httpStatus());
        assertEquals(rejectionBody, response.rawResponse());
        assertEquals(1, requests.size());
    }

    @Test
    void latePlainFallbackPausesTheRealTurnWithoutRecordingAModelTimeoutOrForfeit() throws Exception {
        OpenRouterService router = stub();
        AtomicLong nanos = new AtomicLong();
        router.nanoClock = nanos::get;
        rejectionStatus = 404;
        afterRejection = () -> nanos.set(Duration.ofSeconds(9).toNanos());
        afterPlain = () -> nanos.set(Duration.ofSeconds(11).toNanos());
        GameTurnService turn = new GameTurnService();
        turn.nanoClock = nanos::get;
        turn.openRouterService = router;
        turn.chessService = new ChessService();
        turn.promptService = new PromptService();
        turn.moveRepository = new MoveRepository() {
            @Override
            public long count(String query, Object... params) { return 0; }
        };
        List<String> pauses = new ArrayList<>();
        turn.gameLifecycleService = new GameLifecycleService() {
            @Override
            public void pauseForProviderFailure(UUID id, String detail) { pauses.add(detail); }

            @Override
            public void forfeitTimeout(UUID id, String color, int seconds) {
                throw new AssertionError("Cache rejection must not cause a timeout loss");
            }
        };
        List<MoveAttemptService.AttemptRecord> attempts = new ArrayList<>();
        turn.moveAttemptService = new MoveAttemptService() {
            @Override
            public void record(AttemptRecord record) { attempts.add(record); }
        };
        turn.gameWebSocket = new GameWebSocket() {
            @Override
            public void broadcastGameStatus(UUID id, String status, String result, String reason, int count,
                                            BigDecimal cost, String color, java.time.Instant start,
                                            java.time.Instant deadline, String detail, String mode, String banterLevel) { }
        };
        Board board = new Board();
        var request = new GameTurnService.TurnRequest(UUID.randomUUID(), "", board.getFen(), board, "WHITE", 1,
                List.of("e4"), "google/gemini-pro", "White", "Black",
                new ArrayList<>(List.of(new ChatMsg("system", "rules"))), null, null, true, 3, 10,
                "v2", "hash", BigDecimal.ZERO, () -> true, 100, ChatMode.OPTIONAL, ChatMode.OPTIONAL);

        var result = turn.playTurn(request);

        assertTrue(result.pausedGame());
        assertFalse(result.isTerminal());
        assertEquals(1, pauses.size());
        assertTrue(pauses.getFirst().contains("cache"));
        assertEquals(List.of(Outcome.PROVIDER_RETRYABLE), attempts.stream()
                .map(MoveAttemptService.AttemptRecord::outcome).toList());
        assertEquals(404, attempts.getFirst().response().httpStatus());
        assertEquals(2, requests.size(), "only the marked request and its plain fallback are sent");
    }

    private static List<String> messageText(JsonNode messages) {
        List<String> result = new ArrayList<>();
        for (JsonNode message : messages) {
            JsonNode content = message.path("content");
            result.add(message.path("role").asText() + ":"
                    + (content.isArray() ? content.get(0).path("text").asText() : content.asText()));
        }
        return result;
    }

    private static void assertPlain(JsonNode wire, List<ChatMsg> messages) {
        assertEquals(messages.size(), wire.size());
        for (int i = 0; i < wire.size(); i++) {
            assertEquals(2, wire.get(i).size(), "only role and content are transmitted");
            assertTrue(wire.get(i).path("content").isTextual());
            assertEquals(messages.get(i).content(), wire.get(i).path("content").asText());
        }
        assertFalse(wire.toString().contains("cache_control"));
    }

    private static void assertBreakpoint(JsonNode wire, int boundary, List<ChatMsg> messages) {
        assertEquals(messages.size(), wire.size());
        int markers = 0;
        for (int i = 0; i < wire.size(); i++) {
            assertEquals(messages.get(i).role(), wire.get(i).path("role").asText());
            assertEquals(2, wire.get(i).size());
            JsonNode content = wire.get(i).path("content");
            if (i == boundary) {
                assertTrue(content.isArray());
                assertEquals(1, content.size());
                JsonNode block = content.get(0);
                assertEquals(3, block.size());
                assertEquals("text", block.path("type").asText());
                assertEquals(messages.get(i).content(), block.path("text").asText());
                assertEquals(JSON.createObjectNode().put("type", "ephemeral"), block.path("cache_control"));
                markers++;
            } else {
                assertTrue(content.isTextual(), "only the marked message needs content-array form");
                assertEquals(messages.get(i).content(), content.asText());
            }
        }
        assertEquals(1, markers, "one breakpoint fits Anthropic's limit and is Gemini's last breakpoint");
    }

    private JsonNode send(OpenRouterService service, String model, List<ChatMsg> messages) throws IOException {
        assertFalse(service.chat(model, messages).isProviderFailure());
        JsonNode body = JSON.readTree(lastBody);
        assertFalse(body.has("cache_control"), "breakpoints are on content blocks, never at the request root");
        return body.path("messages");
    }

    private OpenRouterService stub() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/models", exchange -> {
            catalogRequests.incrementAndGet();
            byte[] bytes = catalog.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
            exchange.close();
        });
        server.createContext("/chat/completions", exchange -> {
            lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(JSON.readTree(lastBody));
            boolean reject = rejectionStatus != 0 && (requests.size() == 1 || rejectPlain);
            if (reject) {
                afterRejection.run();
            } else {
                afterPlain.run();
            }
            byte[] answer = (reject ? rejectionBody : "{\"choices\":[{\"message\":{\"content\":\"e4\"}}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reject ? rejectionStatus : 200, answer.length);
            try (var out = exchange.getResponseBody()) {
                out.write(answer);
            }
            exchange.close();
        });
        server.start();
        OpenRouterService service = new OpenRouterService();
        service.objectMapper = JSON;
        service.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        service.keyStore = OpenRouterKeyStoreFixture.keys("test-key", java.nio.file.Path.of("target/test-keys"));
        service.defaultMaxTokens = 100;
        service.listModels(false, null, OpenRouterService.NO_LIMIT, null);
        return service;
    }

    private static String explicitCatalog() {
        var catalog = JSON.createObjectNode();
        var data = catalog.putArray("data");
        for (String model : EXPLICIT_MODELS) {
            data.addObject().put("id", model).putObject("pricing").put("input_cache_write", "0.000001");
        }
        return catalog.toString();
    }
}
