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
    private int chatStatus = 200;
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

    @ParameterizedTest
    @CsvSource({"80,20", "0,0", "null,null"})
    void parsesNullableCacheCountersAndKeepsReportedCost(String cached, String writes) throws IOException {
        OpenRouterService service = serviceWithCatalog("{\"data\":[]}");
        chatResponse = """
                {"choices":[{"message":{"content":"e4"}}],"usage":{"prompt_tokens":100,
                "completion_tokens":10,"cost":0.000123,"prompt_tokens_details":{
                "cached_tokens":%s,"cache_write_tokens":%s}}}
                """.formatted(cached, writes);
        var response = service.chat("example/model", messages());
        assertEquals("null".equals(cached) ? null : Integer.valueOf(cached), response.cachedPromptTokens());
        assertEquals("null".equals(writes) ? null : Integer.valueOf(writes), response.cacheWriteTokens());
        assertEquals(0.000123, response.costUsd());
        assertEquals(100, response.promptTokens());
        assertFalse(JSON.readTree(lastChatBody).has("cache_control"));
        assertEquals(JSON.readTree("[{\"role\":\"user\",\"content\":\"move\"}]"),
                JSON.readTree(lastChatBody).path("messages"));
    }

    @ParameterizedTest
    @CsvSource({"'{}'", "'{\"prompt_tokens\":100}'", "'{\"prompt_tokens_details\":{}}'"})
    void absentCacheCountersStayUnknown(String usage) throws IOException {
        OpenRouterService service = serviceWithCatalog("{\"data\":[]}");
        chatResponse = "{\"choices\":[{\"message\":{\"content\":\"e4\"}}],\"usage\":" + usage + "}";
        var response = service.chat("example/model", messages());
        assertNull(response.cachedPromptTokens());
        assertNull(response.cacheWriteTokens());
        assertNull(response.costUsd());
    }

    @ParameterizedTest
    @CsvSource({"200,true", "200,false", "503,true", "400,true"})
    void providerErrorsRetainUsageWhenPresent(int status, boolean error) throws IOException {
        OpenRouterService service = serviceWithCatalog("{\"data\":[]}");
        chatStatus = status;
        chatResponse = (error ? "{\"error\":{\"message\":\"failure\"}," : "{\"choices\":[{}],") + """
                "usage":{"prompt_tokens":100,"cost":0.002,"prompt_tokens_details":{
                "cached_tokens":80,"cache_write_tokens":20}}}
                """;
        var response = service.chat("example/model", messages());
        assertTrue(response.isProviderFailure());
        assertEquals(80, response.cachedPromptTokens());
        assertEquals(20, response.cacheWriteTokens());
        assertEquals(0.002, response.costUsd());
    }

    @ParameterizedTest
    @CsvSource({"'0.000003','0.0000003','0.0000027'", "'0','0','0'",
            "'0.000003',null,null", "null,'0.0000003',null", "'-1','0',null", "'bad','0',null"})
    void savingsUsePerTokenCatalogPricesAndPreserveUnknowns(String input, String read, String expected)
            throws IOException {
        OpenRouterService service = serviceWithCatalog("""
                {"data":[{"id":"example/model","pricing":{"prompt":"%s","input_cache_read":"%s"}}]}
                """.formatted(input, read));
        service.listModels(false, null, OpenRouterService.NO_LIMIT, null);
        var savings = service.cacheReadSavingsSnapshot().get("example/model");
        if ("null".equals(expected)) {
            assertNull(savings);
        } else {
            assertEquals(0, new java.math.BigDecimal(expected).compareTo(savings));
        }
        assertNull(service.cacheReadSavingsSnapshot().get("unknown/model"));
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
            exchange.sendResponseHeaders(chatStatus, bytes.length);
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
