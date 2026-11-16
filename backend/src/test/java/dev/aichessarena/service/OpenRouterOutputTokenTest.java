package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
            service.chat("example/model", messages(), (OpenRouterService.AnswerDeadline) null, configured);
            assertEquals(expected, JSON.readTree(lastChatBody).path("max_tokens").asInt());
        }
        assertEquals(1, catalogRequests.get(), "chat reads the cached catalog without fetching it again");
    }

    @Test
    void unknownModelAndMissingMaximumKeepTheConfiguredLimit() throws IOException {
        OpenRouterService service = serviceWithCatalog("{\"data\":[{\"id\":\"example/model\"}]}");
        service.listModels(false, null, OpenRouterService.NO_LIMIT, null);
        for (String id : List.of("example/model", "unknown/model")) {
            service.chat(id, messages(), (OpenRouterService.AnswerDeadline) null, 262144);
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
            byte[] bytes = "{\"choices\":[{\"message\":{\"content\":\"e4\"}}]}".getBytes(StandardCharsets.UTF_8);
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
