package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.aichessarena.service.OpenRouterService.ChatMsg;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Actual HTTP request contracts against an ephemeral loopback stub; no OpenRouter access. */
class OpenRouterPromptCachingTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> EXPLICIT_MODELS = List.of(
            "anthropic/claude-sonnet", "~anthropic/claude-sonnet-latest", "google/gemini-pro");
    private HttpServer server;
    private volatile String lastBody;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"anthropic/claude-sonnet", "~anthropic/claude-sonnet-latest", "google/gemini-pro"})
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
        server.createContext("/chat/completions", exchange -> {
            lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] answer = "{\"choices\":[{\"message\":{\"content\":\"e4\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, answer.length);
            try (var out = exchange.getResponseBody()) {
                out.write(answer);
            }
            exchange.close();
        });
        server.start();
        OpenRouterService service = new OpenRouterService();
        service.objectMapper = JSON;
        service.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        service.apiKey = "test-key";
        service.defaultMaxTokens = 100;
        return service;
    }
}
