package dev.aichessarena.service;

import static dev.aichessarena.entity.ReasoningEffort.HIGH;
import static dev.aichessarena.entity.ReasoningEffort.LOW;
import static dev.aichessarena.entity.ReasoningEffort.MAX;
import static dev.aichessarena.entity.ReasoningEffort.MEDIUM;
import static dev.aichessarena.entity.ReasoningEffort.MINIMAL;
import static dev.aichessarena.entity.ReasoningEffort.NONE;
import static dev.aichessarena.entity.ReasoningEffort.XHIGH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.aichessarena.config.OpenRouterKeyStoreFixture;
import dev.aichessarena.entity.ReasoningEffort;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Reasoning effort: which level is sent for a requested one (from the model catalog) and how it is sent.
 */
class OpenRouterReasoningEffortTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** Meta Muse Ember as listed by OpenRouter: mandatory reasoning, default effort max, no "none". */
    private static final String MUSE_EMBER = """
            {"id": "meta/muse-ember", "supported_parameters": ["max_tokens", "reasoning", "reasoning_effort"],
             "reasoning": {"mandatory": true, "supported_efforts": ["max", "xhigh", "high", "medium", "low", "minimal"],
                           "default_effort": "max"}}""";
    /** Kimi K3.1 Pro as listed by OpenRouter: optional reasoning with four levels. */
    private static final String KIMI = """
            {"id": "moonshotai/kimi-k3.1-pro", "supported_parameters": ["reasoning", "reasoning_effort"],
             "reasoning": {"mandatory": false, "default_enabled": true,
                           "supported_efforts": ["max", "high", "low", "none"], "default_effort": "max"}}""";

    private HttpServer server;
    private volatile String lastChatBody;
    private final AtomicInteger catalogRequests = new AtomicInteger();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void aSupportedLevelIsSentAsIs() throws IOException {
        assertEquals(LOW, decide(LOW, MUSE_EMBER));
        assertEquals(NONE, decide(NONE, KIMI));
        assertEquals(MAX, decide(MAX, KIMI));
    }

    @Test
    void anUnsupportedLevelIsMappedToTheNearestSupportedOne() throws IOException {
        assertEquals(LOW, decide(MEDIUM, KIMI), "tie between low and high goes to the lower level");
        assertEquals(LOW, decide(MINIMAL, KIMI), "none only when asked for: it switches reasoning off");
        assertEquals(HIGH, decide(XHIGH, KIMI));
        // Mandatory reasoning: "none" would be rejected, so it becomes the lowest supported level.
        assertEquals(MINIMAL, decide(NONE, MUSE_EMBER));
    }

    @Test
    void aMandatoryModelWithNoneListedStillNeverGetsNone() throws IOException {
        String odd = """
                {"id": "x/odd", "reasoning": {"mandatory": true, "supported_efforts": ["high", "low", "none"]}}""";
        assertEquals(LOW, decide(NONE, odd));
    }

    @Test
    void modelsWithoutReasoningOrWithoutEffortLevelsGetNothing() throws IOException {
        assertNull(decide(LOW, "{\"id\": \"x/plain\", \"supported_parameters\": [\"max_tokens\"]}"),
                "no reasoning support");
        assertNull(decide(LOW, "{\"id\": \"x/plain\", \"reasoning\": null}"), "reasoning null");
        assertNull(decide(LOW, "{\"id\": \"x/budget\", \"reasoning\": {\"mandatory\": false}}"),
                "supported_efforts omitted: the model does not expose effort selection");
        assertNull(decide(LOW, "{\"id\": \"x/param\", \"supported_parameters\": [\"reasoning\"]}"),
                "reasoning parameter without a reasoning object");
        assertNull(decide(LOW, "{\"id\": \"x/unknown\", \"reasoning\": {\"supported_efforts\": [\"ultra\"]}}"),
                "no known level listed");
    }

    @Test
    void aNullLevelListAcceptsEveryLevel() throws IOException {
        String all = "{\"id\": \"x/all\", \"reasoning\": {\"supported_efforts\": null}}";
        assertEquals(MEDIUM, decide(MEDIUM, all));
        assertEquals(NONE, decide(NONE, all));
        String allMandatory = "{\"id\": \"x/all\", \"reasoning\": {\"mandatory\": true, \"supported_efforts\": null}}";
        assertEquals(MINIMAL, decide(NONE, allMandatory));
    }

    @Test
    void withoutACatalogEntryTheLevelIsSentAsIs() {
        assertEquals(XHIGH, OpenRouterService.decideReasoningEffort(XHIGH, null).effort());
        assertNull(OpenRouterService.decideReasoningEffort(null, null).effort());
    }

    @Test
    void resolveUsesTheCachedCatalog() throws IOException {
        OpenRouterService service = serviceWithCatalog(fixture());

        // Claude Haiku 5.5 lists max, xhigh, high, medium, low, none.
        assertEquals(MEDIUM, service.resolveReasoningEffort("anthropic/claude-haiku-5.5", MEDIUM));
        // Gemini 4 Carbon: mandatory reasoning with high, medium and low only.
        assertEquals(LOW, service.resolveReasoningEffort("google/gemini-4-carbon", NONE));
        assertEquals(HIGH, service.resolveReasoningEffort("google/gemini-4-carbon", MAX));
        // Grok 4.20 reasons but lists no levels; the bare model does not reason.
        assertNull(service.resolveReasoningEffort("x-ai/grok-4.20", LOW));
        assertNull(service.resolveReasoningEffort("example/bare-model", LOW));
        // A model the catalog does not list: sent as is.
        assertEquals(LOW, service.resolveReasoningEffort("unlisted/model", LOW));
        // Nothing requested: nothing sent, without looking anything up.
        assertNull(service.resolveReasoningEffort("google/gemini-4-carbon", null));
        assertEquals(1, catalogRequests.get(), "the catalog is fetched once and then cached");
    }

    @Test
    void withTheCatalogUnavailableTheLevelIsSentAsIs() throws IOException {
        OpenRouterService failing = serviceWithCatalogStatus(503);
        assertEquals(NONE, failing.resolveReasoningEffort("google/gemini-4-carbon", NONE));

        OpenRouterService withoutKey = new OpenRouterService();
        assertEquals(LOW, withoutKey.resolveReasoningEffort("google/gemini-4-carbon", LOW),
                "no API key (and no object mapper): no catalog");
    }

    @Test
    void theRequestCarriesTheEffortOnlyWhenOneIsGiven() throws IOException {
        OpenRouterService service = serviceAnsweringChat();

        service.chat("model-a", List.of(new OpenRouterService.ChatMsg("user", "move")),
                OpenRouterService.AnswerDeadline.after(Duration.ofSeconds(5), System.nanoTime()), 4096, LOW);
        JsonNode withEffort = JSON.readTree(lastChatBody);
        assertEquals("low", withEffort.at("/reasoning/effort").asText());
        assertEquals(1, withEffort.path("reasoning").size(), "only the effort, never also a token budget");
        assertEquals(4096, withEffort.path("max_tokens").asInt());

        service.chat("model-a", List.of(new OpenRouterService.ChatMsg("user", "move")),
                OpenRouterService.AnswerDeadline.after(Duration.ofSeconds(5), System.nanoTime()), 4096, null);
        assertFalse(JSON.readTree(lastChatBody).has("reasoning"), "model default: no reasoning parameter");

        service.chat("model-a", List.of(new OpenRouterService.ChatMsg("user", "move")),
                OpenRouterService.AnswerDeadline.after(Duration.ofSeconds(5), System.nanoTime()), 4096);
        assertFalse(JSON.readTree(lastChatBody).has("reasoning"), "the four-argument chat sends none");

        service.chat("model-a", List.of(new OpenRouterService.ChatMsg("user", "move")),
                OpenRouterService.AnswerDeadline.after(Duration.ofSeconds(5), System.nanoTime()), 4096, XHIGH);
        assertEquals("xhigh", JSON.readTree(lastChatBody).at("/reasoning/effort").asText());
    }

    @Test
    void theCatalogDefaultEffortIsListed() throws IOException {
        OpenRouterService service = serviceWithCatalog(fixture());
        var models = service.listModels(false, null, OpenRouterService.NO_LIMIT, null).data();
        var haiku = models.stream().filter(m -> m.id().equals("anthropic/claude-haiku-5.5")).findFirst().orElseThrow();
        var grok = models.stream().filter(m -> m.id().equals("x-ai/grok-4.20")).findFirst().orElseThrow();

        assertEquals("medium", haiku.defaultEffort());
        assertNull(grok.defaultEffort());
        assertTrue(haiku.reasoningEfforts().contains("none"));
    }

    private static ReasoningEffort decide(ReasoningEffort requested, String modelJson) throws IOException {
        return OpenRouterService.decideReasoningEffort(requested, JSON.readTree(modelJson)).effort();
    }

    private byte[] fixture() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/openrouter/models-fixture.json")) {
            assertNotNull(in, "fixture missing");
            return in.readAllBytes();
        }
    }

    private OpenRouterService serviceWithCatalog(byte[] body) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/models", exchange -> {
            catalogRequests.incrementAndGet();
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
            exchange.close();
        });
        server.start();
        return service();
    }

    private OpenRouterService serviceWithCatalogStatus(int status) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/models", exchange -> {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        return service();
    }

    private OpenRouterService serviceAnsweringChat() throws IOException {
        byte[] answer = "{\"choices\":[{\"message\":{\"content\":\"e4\"}}]}".getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/chat/completions", exchange -> {
            lastChatBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, answer.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(answer);
            }
            exchange.close();
        });
        server.start();
        return service();
    }

    private OpenRouterService service() {
        OpenRouterService service = new OpenRouterService();
        service.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        service.keyStore = OpenRouterKeyStoreFixture.keys("test-key", java.nio.file.Path.of("target/test-keys"));
        service.objectMapper = new ObjectMapper();
        service.defaultTemperature = 0.7;
        service.defaultMaxTokens = 100;
        service.featuredProvidersConfig = "openai,anthropic,google,x-ai";
        service.providerOrderConfig = "openai,anthropic,google,x-ai";
        service.featuredKeywordsConfig = "gpt,claude,gemini,grok";
        service.excludedModelKeywordsConfig = "embedding,embeddings,rerank,moderation";
        return service;
    }
}
