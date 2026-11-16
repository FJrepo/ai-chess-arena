package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.sun.net.httpserver.HttpServer;
import dev.aichessarena.dto.OpenRouterModelOptionDto;
import dev.aichessarena.dto.OpenRouterModelsResponseDto;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Catalog parsing and listing against a small fixture of the OpenRouter {@code /models} response. */
class OpenRouterServiceModelsTest {

    private static final double DELTA = 1e-9;
    /** Text-output models in the fixture; the embedding model is filtered out. */
    private static final int LISTED_MODELS = 11;

    private HttpServer server;
    private OpenRouterService service;

    @BeforeEach
    void serveFixture() throws IOException {
        byte[] body;
        try (InputStream in = getClass().getResourceAsStream("/openrouter/models-fixture.json")) {
            assertNotNull(in, "fixture missing");
            body = in.readAllBytes();
        }
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/models", exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
            exchange.close();
        });
        server.start();

        service = new OpenRouterService();
        service.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        service.apiKey = "test-key";
        service.objectMapper = new ObjectMapper();
        service.featuredProvidersConfig = "openai,anthropic,google,x-ai";
        service.providerOrderConfig = "openai,anthropic,google,x-ai";
        service.featuredKeywordsConfig = "gpt,claude,gemini,grok";
        service.excludedModelKeywordsConfig = "embedding,embeddings,rerank,moderation";
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void parsesEveryDetailWhenPresent() {
        OpenRouterModelOptionDto model = allModels().get("anthropic/claude-haiku-5.5");

        assertEquals("Anthropic: Claude Haiku 5.5", model.name());
        assertEquals("anthropic", model.provider());
        assertTrue(model.featured());
        assertEquals(1_000_000, model.contextLength());
        assertEquals(0.1, model.promptPricePerMillion(), DELTA);
        assertEquals(0.5, model.completionPricePerMillion(), DELTA);
        assertEquals(0.01, model.cacheReadPricePerMillion(), DELTA);
        assertEquals("2026-10-07", model.releasedAt());
        assertEquals(128_000, model.maxCompletionTokens());
        assertTrue(model.reasoningSupported());
        assertEquals(Boolean.FALSE, model.reasoningMandatory());
        assertEquals(List.of("max", "xhigh", "high", "medium", "low", "none"), model.reasoningEfforts());
        assertEquals(36.5, model.intelligenceIndex(), DELTA);
        assertEquals("Claude Haiku 5.5 is Anthropic's small, fast model for high-volume, cost-sensitive work.",
                model.description());

        OpenRouterModelOptionDto mandatory = allModels().get("google/gemini-4-carbon");
        assertEquals(Boolean.TRUE, mandatory.reasoningMandatory());
        assertEquals(60.0, mandatory.intelligenceIndex(), DELTA);
    }

    @Test
    void explicitNullEffortsExpandToAllLevelsButMissingEffortsStayEmpty() throws IOException {
        byte[] body = """
                {"data": [
                  {"id": "example/all-levels", "reasoning": {"supported_efforts": null}},
                  {"id": "example/mandatory", "reasoning": {"mandatory": true, "supported_efforts": null}},
                  {"id": "example/missing-levels", "reasoning": {"mandatory": true}},
                  {"id": "example/missing-reasoning", "supported_parameters": ["reasoning"]},
                  {"id": "example/null-reasoning", "reasoning": null},
                  {"id": "example/empty-levels", "reasoning": {"supported_efforts": []}}
                ]}
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        server.removeContext("/models");
        server.createContext("/models", exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
            exchange.close();
        });

        Map<String, OpenRouterModelOptionDto> models = allModels();
        assertEquals(List.of("none", "minimal", "low", "medium", "high", "xhigh", "max"),
                models.get("example/all-levels").reasoningEfforts());
        assertEquals(List.of("minimal", "low", "medium", "high", "xhigh", "max"),
                models.get("example/mandatory").reasoningEfforts());
        for (String id : List.of("example/missing-levels", "example/missing-reasoning",
                "example/null-reasoning", "example/empty-levels")) {
            assertEquals(List.of(), models.get(id).reasoningEfforts(), id);
        }
    }

    @Test
    void everyNewFieldIsEmptyWhenMissingOrNull() {
        for (String id : List.of("example/bare-model", "example/null-model")) {
            OpenRouterModelOptionDto model = allModels().get(id);

            assertNotNull(model, id);
            assertEquals("example", model.provider(), id);
            assertNull(model.contextLength(), id);
            assertNull(model.promptPricePerMillion(), id);
            assertNull(model.completionPricePerMillion(), id);
            assertNull(model.releasedAt(), id);
            assertNull(model.cacheReadPricePerMillion(), id);
            assertNull(model.maxCompletionTokens(), id);
            assertFalse(model.reasoningSupported(), id);
            assertNull(model.reasoningMandatory(), id);
            assertEquals(List.of(), model.reasoningEfforts(), id);
            assertNull(model.intelligenceIndex(), id);
            assertNull(model.description(), id);
        }
        assertEquals("Bare Model", allModels().get("example/bare-model").name());
    }

    @Test
    void wronglyTypedValuesAreTreatedAsUnknown() {
        OpenRouterModelOptionDto model = allModels().get("example/odd-values");

        assertNull(model.releasedAt());
        assertNull(model.promptPricePerMillion());
        assertNull(model.completionPricePerMillion());
        assertNull(model.cacheReadPricePerMillion());
        assertNull(model.maxCompletionTokens());
        assertNull(model.intelligenceIndex());
        assertNull(model.description());
        assertTrue(model.reasoningSupported(), "a reasoning object marks reasoning support");
        assertNull(model.reasoningMandatory());
        assertEquals(List.of(), model.reasoningEfforts());
    }

    @Test
    void benchmarksWithoutArtificialAnalysisHaveNoIndex() {
        OpenRouterModelOptionDto model = allModels().get("x-ai/grok-4.20");

        assertNull(model.intelligenceIndex());
        assertTrue(model.reasoningSupported());
        assertEquals(Boolean.FALSE, model.reasoningMandatory());
        assertEquals(List.of(), model.reasoningEfforts());
    }

    @Test
    void reasoningParameterWithoutReasoningObjectStillCountsAsReasoning() {
        OpenRouterModelOptionDto model = allModels().get("openrouter/auto");

        assertTrue(model.reasoningSupported());
        assertNull(model.reasoningMandatory());
        assertEquals(List.of(), model.reasoningEfforts());
        assertNull(model.maxCompletionTokens());
    }

    @Test
    void freeModelsCostZeroWhileVariableAndInvalidPricesAreUnknown() {
        OpenRouterModelOptionDto free = allModels().get("inclusionai/ling-3.2-flash:free");
        assertEquals(0.0, free.promptPricePerMillion(), DELTA);
        assertEquals(0.0, free.completionPricePerMillion(), DELTA);
        assertNull(free.cacheReadPricePerMillion());

        OpenRouterModelOptionDto router = allModels().get("openrouter/auto");
        assertNull(router.promptPricePerMillion());
        assertNull(router.completionPricePerMillion());

        JsonNodeFactory json = JsonNodeFactory.instance;
        assertEquals(0.0, OpenRouterService.parsePricePerMillion(json.textNode("0")), DELTA);
        assertEquals(1.5, OpenRouterService.parsePricePerMillion(json.textNode("0.0000015")), DELTA);
        assertEquals(2.0, OpenRouterService.parsePricePerMillion(json.numberNode(0.000002)), DELTA);
        assertNull(OpenRouterService.parsePricePerMillion(json.textNode("-1")));
        assertNull(OpenRouterService.parsePricePerMillion(json.textNode("abc")));
        assertNull(OpenRouterService.parsePricePerMillion(json.textNode("NaN")));
        assertNull(OpenRouterService.parsePricePerMillion(json.textNode(" ")));
        assertNull(OpenRouterService.parsePricePerMillion(json.nullNode()));
        assertNull(OpenRouterService.parsePricePerMillion(json.missingNode()));
    }

    @Test
    void descriptionsAreTrimmedAtAWordBoundary() {
        String sentence = "Chess engines evaluate positions by searching candidate moves deeply. ";
        String longText = sentence.repeat(10);

        String trimmed = OpenRouterService.trimDescription(longText);

        assertTrue(trimmed.length() <= OpenRouterService.DESCRIPTION_MAX_LENGTH, trimmed);
        assertTrue(trimmed.endsWith("…"), trimmed);
        String head = trimmed.substring(0, trimmed.length() - 1);
        assertTrue(longText.startsWith(head), trimmed);
        char next = longText.charAt(head.length());
        assertTrue(next == ' ' || next == '.', "cut inside a word: " + trimmed);

        assertEquals("short text", OpenRouterService.trimDescription("  short \n\t text "));
        assertNull(OpenRouterService.trimDescription("   "));
        assertNull(OpenRouterService.trimDescription(null));
        String unbroken = "x".repeat(600);
        assertEquals(OpenRouterService.DESCRIPTION_MAX_LENGTH,
                OpenRouterService.trimDescription(unbroken).length());
    }

    @Test
    void limitZeroListsTheWholeCatalog() {
        OpenRouterModelsResponseDto all = service.listModels(false, null, OpenRouterService.NO_LIMIT, null);

        assertNull(all.error());
        assertEquals(LISTED_MODELS, all.data().size());
        assertEquals(LISTED_MODELS, all.totalMatched());
        assertEquals(List.of("openai", "anthropic", "google", "x-ai"), all.featuredProviders());
        assertTrue(all.data().stream().noneMatch(model -> model.id().contains("embedding")));

        OpenRouterModelsResponseDto capped = service.listModels(false, null, 3, null);
        assertEquals(3, capped.data().size());
        assertEquals(LISTED_MODELS, capped.totalMatched());

        assertEquals(1, service.listModels(false, null, -5, null).data().size());
        assertEquals(LISTED_MODELS, service.listModels(false, null, null, null).data().size());
    }

    @Test
    void featuredFilterQueryAndProviderStillApply() {
        OpenRouterModelsResponseDto featured = service.listModels(true, null, OpenRouterService.NO_LIMIT, null);
        assertEquals(List.of("anthropic/claude-haiku-5.5", "~anthropic/claude-sonnet-latest",
                        "google/gemini-4-carbon", "x-ai/grok-4.20"),
                featured.data().stream().map(OpenRouterModelOptionDto::id).toList());
        assertEquals(4, featured.featuredCount());

        assertEquals(List.of("google/gemini-4-carbon"),
                service.listModels(false, "carbon", 0, null).data().stream()
                        .map(OpenRouterModelOptionDto::id).toList());
        assertEquals(List.of("x-ai/grok-4.20"),
                service.listModels(false, null, 0, "x-ai").data().stream()
                        .map(OpenRouterModelOptionDto::id).toList());
    }

    @Test
    void latestAliasesBelongToTheProviderWithoutTheTilde() {
        OpenRouterModelOptionDto alias = allModels().get("~anthropic/claude-sonnet-latest");

        assertEquals("anthropic", alias.provider());
        assertTrue(alias.featured());
        assertEquals(List.of("anthropic/claude-haiku-5.5", "~anthropic/claude-sonnet-latest"),
                service.listModels(false, null, 0, "anthropic").data().stream()
                        .map(OpenRouterModelOptionDto::id).toList());
        assertTrue(service.isModelAllowed("~anthropic/claude-sonnet-latest"));

        assertEquals("openai", OpenRouterService.extractProvider("~openai/gpt-astra-latest"));
        assertEquals("other", OpenRouterService.extractProvider("~/odd"));
        assertEquals("other", OpenRouterService.extractProvider("/odd"));
    }

    @Test
    void outOfRangeCreatedTimestampsHaveNoReleaseDate() {
        assertNull(allModels().get("example/nanosecond-created").releasedAt());
        assertEquals(1.0, allModels().get("example/nanosecond-created").promptPricePerMillion(), DELTA);
        assertNull(allModels().get("example/huge-created").releasedAt());

        JsonNodeFactory json = JsonNodeFactory.instance;
        assertNull(OpenRouterService.parseReleaseDate(json.numberNode(1_791_397_883_000_000_000L)));
        assertNull(OpenRouterService.parseReleaseDate(json.numberNode(Long.MAX_VALUE)));
        assertNull(OpenRouterService.parseReleaseDate(json.numberNode(1e30)));
        assertNull(OpenRouterService.parseReleaseDate(json.numberNode(-5L)));
        assertEquals("9999-12-31", OpenRouterService.parseReleaseDate(json.numberNode(253_402_300_799L)));
        assertNull(OpenRouterService.parseReleaseDate(json.numberNode(253_402_300_800L)));
        assertEquals("2026-10-07", OpenRouterService.parseReleaseDate(json.numberNode(1_791_397_883)));
    }

    @Test
    void anOutOfRangeTimestampDoesNotBreakValidationOfOtherModels() {
        assertTrue(service.isModelAllowed("google/gemini-4-carbon"));
        assertTrue(service.isModelAllowed("example/nanosecond-created"));
        assertEquals(LISTED_MODELS, service.listModels(false, null, 0, null).totalMatched());
    }

    @Test
    void modelValidationUsesTheSameNormalization() {
        assertTrue(service.isModelAllowed("example/bare-model"));
        assertTrue(service.isModelAllowed(" anthropic/claude-haiku-5.5 "));
        assertFalse(service.isModelAllowed("openai/text-embedding-3-small"));
        assertFalse(service.isModelAllowed("unknown/model"));
    }

    private Map<String, OpenRouterModelOptionDto> allModels() {
        return service.listModels(false, null, OpenRouterService.NO_LIMIT, null).data().stream()
                .collect(Collectors.toMap(OpenRouterModelOptionDto::id, Function.identity()));
    }
}
