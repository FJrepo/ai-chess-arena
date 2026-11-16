package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Verifies the output limit on actual chat requests, without a live provider. */
class OpenRouterOutputTokenTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private HttpServer server;
    private volatile String lastChatBody;
    private String chatResponse = "{\"choices\":[{\"message\":{\"content\":\"e4\"}}]}";
    private final AtomicInteger catalogRequests = new AtomicInteger();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @CsvSource({"8192,262144,8192", "262144,16384,16384", "16384,16384,16384",
            "null,262144,262144", "0,262144,262144", "-1,262144,262144", "'invalid',262144,262144"})
    void requestIsClampedOnlyToAKnownPositiveCatalogMaximum(String cap, int configured, int expected)
            throws IOException {
        OpenRouterService service = serviceWithCatalog("""
                {"data":[{"id":"example/model","top_provider":{"max_completion_tokens":%s}}]}
                """.formatted("invalid".equals(cap) ? "\"invalid\"" : cap));
        service.listModels(false, null, OpenRouterService.NO_LIMIT, null);

        for (int i = 0; i < 2; i++) {
            var response = service.chat("example/model", messages(), (OpenRouterService.AnswerDeadline) null, configured);
            assertEquals(expected, response.effectiveMaxOutputTokens());
            Integer maximum = List.of("null", "0", "-1", "invalid").contains(cap) ? null : Integer.valueOf(cap);
            assertEquals(maximum, response.modelMaxCompletionTokens());
            assertEquals(expected, JSON.readTree(lastChatBody).path("max_tokens").asInt());
        }
        assertEquals(1, catalogRequests.get(), "chat reads the cached catalog without fetching it again");
    }

    @Test
    void unknownModelAndMissingMaximumKeepTheConfiguredLimit() throws IOException {
        OpenRouterService service = serviceWithCatalog("{\"data\":[{\"id\":\"example/model\"}]}");
        service.listModels(false, null, OpenRouterService.NO_LIMIT, null);
        for (String id : List.of("example/model", "unknown/model")) {
            var response = service.chat(id, messages(), (OpenRouterService.AnswerDeadline) null, 262144);
            assertEquals(262144, response.effectiveMaxOutputTokens());
            assertNull(response.modelMaxCompletionTokens());
            assertEquals(262144, JSON.readTree(lastChatBody).path("max_tokens").asInt());
        }
        assertEquals(1, catalogRequests.get());
    }

    @Test
    void noCachedCatalogSendsConfiguredLimitWithoutFetchingDuringTheMove() throws IOException {
        OpenRouterService service = serviceWithCatalog("{\"data\":[]}");
        service.chat("unknown/model", messages(), (OpenRouterService.AnswerDeadline) null, 262144);
        assertEquals(262144, JSON.readTree(lastChatBody).path("max_tokens").asInt());
        assertEquals(0, catalogRequests.get());
    }

    @Test
    void truncatedResponseCarriesTheSentCapAndTheModelMaximum() throws IOException {
        OpenRouterService service = serviceWithCatalog("""
                {"data":[{"id":"example/model","top_provider":{"max_completion_tokens":8192}}]}
                """);
        service.listModels(false, null, OpenRouterService.NO_LIMIT, null);
        chatResponse = """
                {"choices":[{"message":{"content":null},"finish_reason":"length"}]}
                """;

        var response = service.chat("example/model", messages(), (OpenRouterService.AnswerDeadline) null, 16384);

        assertEquals(8192, JSON.readTree(lastChatBody).path("max_tokens").asInt());
        assertTrue(response.truncated());
        assertEquals(8192, response.effectiveMaxOutputTokens());
        assertEquals(8192, response.modelMaxCompletionTokens());
        assertFalse(service.offersReasoningEffort("example/model"));
        assertTrue(service.offersReasoningEffort("unknown/model"));
        assertEquals(1, catalogRequests.get());
    }

    private static List<OpenRouterService.ChatMsg> messages() {
        return List.of(new OpenRouterService.ChatMsg("user", "move"));
    }

    private OpenRouterService serviceWithCatalog(String catalog) throws IOException {
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
            lastChatBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] bytes = chatResponse.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
            exchange.close();
        });
        server.start();
        OpenRouterService service = new OpenRouterService();
        service.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        service.apiKey = "test-key";
        service.objectMapper = JSON;
        service.featuredProvidersConfig = "example";
        service.providerOrderConfig = "example";
        service.featuredKeywordsConfig = "model";
        service.excludedModelKeywordsConfig = "embedding";
        return service;
    }
}
