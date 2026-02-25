package dev.aichessarena.service;

import dev.aichessarena.dto.OpenRouterModelOptionDto;
import dev.aichessarena.dto.OpenRouterModelsResponseDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@ApplicationScoped
public class OpenRouterService {

    private static final Logger LOG = Logger.getLogger(OpenRouterService.class);
    private static final int DEFAULT_LIMIT = 120;
    private static final int MAX_LIMIT = 300;
    private static final long MODELS_CACHE_TTL_MS = 5 * 60 * 1000L;

    @ConfigProperty(name = "openrouter.api-key")
    String apiKey;

    @ConfigProperty(name = "openrouter.base-url")
    String baseUrl;

    @ConfigProperty(
            name = "openrouter.featured-providers",
            defaultValue = "openai,anthropic,google,x-ai,meta-llama,deepseek,mistralai,qwen,cohere"
    )
    String featuredProvidersConfig;

    @ConfigProperty(
            name = "openrouter.provider-order",
            defaultValue = "openai,anthropic,google,x-ai,meta-llama,deepseek,mistralai,qwen,cohere"
    )
    String providerOrderConfig;

    @ConfigProperty(
            name = "openrouter.featured-keywords",
            defaultValue = "gpt,claude,gemini,llama,grok,deepseek,mistral,qwen,command"
    )
    String featuredKeywordsConfig;

    @ConfigProperty(
            name = "openrouter.excluded-model-keywords",
            defaultValue = "embedding,embeddings,rerank,moderation,transcription,speech,tts,image,vision,audio,video,search"
    )
    String excludedModelKeywordsConfig;

    @ConfigProperty(name = "openrouter.model-validation-fail-open", defaultValue = "true")
    boolean modelValidationFailOpen;

    @ConfigProperty(name = "game.default-temperature", defaultValue = "0.7")
    double defaultTemperature;

    @ConfigProperty(name = "game.default-max-tokens", defaultValue = "500")
    int defaultMaxTokens;

    @Inject
    ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private volatile JsonNode cachedModels;
    private volatile long cachedModelsAtMs;

    public LlmResponse chat(String modelId, List<ChatMsg> messages) {
        return chat(modelId, messages, Duration.ofSeconds(120));
    }

    public LlmResponse chat(String modelId, List<ChatMsg> messages, Duration requestTimeout) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", modelId);
            body.put("temperature", defaultTemperature);
            body.put("max_tokens", defaultMaxTokens);

            ArrayNode msgs = body.putArray("messages");
            for (ChatMsg msg : messages) {
                ObjectNode m = msgs.addObject();
                m.put("role", msg.role());
                m.put("content", msg.content());
            }

            String jsonBody = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(requestTimeout)
                    .build();

            long startTime = System.currentTimeMillis();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long elapsed = System.currentTimeMillis() - startTime;

            if (response.statusCode() != 200) {
                LOG.errorf("OpenRouter API error %d: %s", response.statusCode(), response.body());
                return new LlmResponse(
                        null, 0, 0, null, elapsed, response.body(), false,
                        "OpenRouter returned status " + response.statusCode()
                );
            }

            JsonNode json = objectMapper.readTree(response.body());
            String content = json.at("/choices/0/message/content").asText(null);
            int promptTokens = json.at("/usage/prompt_tokens").asInt(0);
            int completionTokens = json.at("/usage/completion_tokens").asInt(0);

            // Try to get cost from response, or leave null
            Double cost = null;
            if (json.has("usage") && json.get("usage").has("cost")) {
                cost = json.get("usage").get("cost").asDouble();
            }

            return new LlmResponse(content, promptTokens, completionTokens, cost, elapsed, response.body(), false, null);
        } catch (HttpTimeoutException e) {
            LOG.warnf("OpenRouter request timed out for model %s", modelId);
            return new LlmResponse(null, 0, 0, null, 0, null, true, "Request timed out");
        } catch (Exception e) {
            LOG.error("OpenRouter API call failed", e);
            return new LlmResponse(null, 0, 0, null, 0, null, false, e.getMessage());
        }
    }

    public OpenRouterModelsResponseDto listModels(boolean featuredOnly, String query, Integer limit, String provider) {
        JsonNode rawModels = fetchRawModels();
        JsonNode data = rawModels.get("data");
        if (data == null || !data.isArray()) {
            String error = rawModels.path("error").asText("Failed to fetch OpenRouter models");
            return new OpenRouterModelsResponseDto(List.of(), 0, 0, error);
        }

        ModelFilterPolicy policy = buildModelFilterPolicy();

        List<ModelCandidate> candidates = normalizeCandidates(
                (ArrayNode) data,
                policy.featuredProviderSet(),
                policy.featuredKeywords(),
                policy.excludedKeywords()
        );

        int featuredCount = (int) candidates.stream().filter(ModelCandidate::featured).count();

        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String normalizedProvider = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        List<ModelCandidate> filtered = candidates.stream()
                .filter(candidate -> !featuredOnly || candidate.featured())
                .filter(candidate -> normalizedProvider.isEmpty() || normalizedProvider.equals(candidate.provider()))
                .filter(candidate -> normalizedQuery.isEmpty() || candidate.searchText().contains(normalizedQuery))
                .sorted(buildComparator(policy.providerOrder()))
                .toList();

        int totalMatched = filtered.size();
        int normalizedLimit = normalizeLimit(limit);
        List<OpenRouterModelOptionDto> result = filtered.stream()
                .limit(normalizedLimit)
                .map(candidate -> new OpenRouterModelOptionDto(
                        candidate.id(),
                        candidate.name(),
                        candidate.provider(),
                        candidate.contextLength(),
                        candidate.promptPricePerMillion(),
                        candidate.completionPricePerMillion(),
                        candidate.featured()
                ))
                .toList();

        return new OpenRouterModelsResponseDto(result, totalMatched, featuredCount, null);
    }

    public boolean isModelAllowed(String modelId) {
        String normalizedModelId = modelId == null ? "" : modelId.trim();
        if (normalizedModelId.isEmpty()) {
            return false;
        }

        JsonNode rawModels = fetchRawModels();
        JsonNode data = rawModels.get("data");
        if (data == null || !data.isArray()) {
            String reason = rawModels.path("error").asText("model catalog unavailable");
            LOG.warnf("Skipping strict model validation for '%s' (%s)", normalizedModelId, reason);
            return modelValidationFailOpen;
        }

        ModelFilterPolicy policy = buildModelFilterPolicy();
        return normalizeCandidates(
                (ArrayNode) data,
                policy.featuredProviderSet(),
                policy.featuredKeywords(),
                policy.excludedKeywords()
        ).stream().anyMatch(candidate -> normalizedModelId.equals(candidate.id()));
    }

    public boolean checkApiKey() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/models"))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private JsonNode fetchRawModels() {
        ObjectNode fallback = objectMapper.createObjectNode();
        fallback.putArray("data");

        if (apiKey == null || apiKey.isBlank()) {
            fallback.put("error", "OPENROUTER_API_KEY is not configured");
            return fallback;
        }

        long now = System.currentTimeMillis();
        JsonNode snapshot = cachedModels;
        if (snapshot != null && now - cachedModelsAtMs < MODELS_CACHE_TTL_MS) {
            return snapshot;
        }

        synchronized (this) {
            now = System.currentTimeMillis();
            snapshot = cachedModels;
            if (snapshot != null && now - cachedModelsAtMs < MODELS_CACHE_TTL_MS) {
                return snapshot;
            }

            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/models"))
                        .header("Authorization", "Bearer " + apiKey)
                        .GET()
                        .timeout(Duration.ofSeconds(30))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    LOG.errorf("OpenRouter models API error %d: %s",
                            response.statusCode(), truncateForLog(response.body()));
                    fallback.put("error", "OpenRouter models API returned status " + response.statusCode());
                    return snapshot != null ? snapshot : fallback;
                }

                JsonNode parsed = objectMapper.readTree(response.body());
                if (!parsed.has("data") || !parsed.get("data").isArray()) {
                    LOG.errorf("Unexpected OpenRouter models response shape: %s", truncateForLog(response.body()));
                    fallback.put("error", "Unexpected OpenRouter models response");
                    return snapshot != null ? snapshot : fallback;
                }

                cachedModels = parsed;
                cachedModelsAtMs = now;
                return parsed;
            } catch (Exception e) {
                LOG.error("Failed to fetch OpenRouter models", e);
                fallback.put("error", "Failed to fetch OpenRouter models");
                return snapshot != null ? snapshot : fallback;
            }
        }
    }

    private List<ModelCandidate> normalizeCandidates(
            ArrayNode data,
            Set<String> featuredProviderSet,
            List<String> featuredKeywords,
            List<String> excludedKeywords
    ) {
        List<ModelCandidate> result = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();

        for (JsonNode node : data) {
            String id = node.path("id").asText(null);
            if (id == null || id.isBlank() || seenIds.contains(id)) {
                continue;
            }

            String idLower = id.toLowerCase(Locale.ROOT);
            if (containsAny(idLower, excludedKeywords)) {
                continue;
            }

            if (!supportsTextOutput(node)) {
                continue;
            }

            String provider = extractProvider(id);
            String name = buildDisplayName(id, node.path("name").asText(null));
            Integer contextLength = extractContextLength(node);
            Double promptPricePerMillion = parsePricePerMillion(node.path("pricing").path("prompt").asText(null));
            Double completionPricePerMillion = parsePricePerMillion(
                    node.path("pricing").path("completion").asText(null)
            );
            boolean featured = featuredProviderSet.contains(provider) || containsAny(idLower, featuredKeywords);
            String searchText = (name + " " + id + " " + provider).toLowerCase(Locale.ROOT);

            result.add(new ModelCandidate(
                    id,
                    name,
                    provider,
                    contextLength,
                    promptPricePerMillion,
                    completionPricePerMillion,
                    featured,
                    searchText
            ));
            seenIds.add(id);
        }

        return result;
    }

    private Comparator<ModelCandidate> buildComparator(List<String> providerOrder) {
        return Comparator
                .comparing(ModelCandidate::featured).reversed()
                .thenComparingInt(candidate -> providerRank(candidate.provider(), providerOrder))
                .thenComparing(candidate -> candidate.contextLength() == null ? 0 : candidate.contextLength(),
                        Comparator.reverseOrder())
                .thenComparing(ModelCandidate::name);
    }

    private int providerRank(String provider, List<String> providerOrder) {
        int index = providerOrder.indexOf(provider);
        return index == -1 ? Integer.MAX_VALUE : index;
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1) {
            return 1;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private String extractProvider(String modelId) {
        String[] parts = modelId.split("/");
        if (parts.length == 0 || parts[0].isBlank()) {
            return "other";
        }
        return parts[0].trim().toLowerCase(Locale.ROOT);
    }

    private String buildDisplayName(String modelId, String rawName) {
        if (rawName != null && !rawName.isBlank()) {
            return rawName.trim();
        }

        String[] parts = modelId.split("/");
        String modelPart = parts.length == 0 ? modelId : parts[parts.length - 1];
        String[] tokens = modelPart.split("[-_]");
        StringBuilder displayName = new StringBuilder();
        for (String token : tokens) {
            if (token.isBlank()) {
                continue;
            }
            if (!displayName.isEmpty()) {
                displayName.append(' ');
            }
            displayName.append(Character.toUpperCase(token.charAt(0)))
                    .append(token.substring(1));
        }
        return displayName.isEmpty() ? modelId : displayName.toString();
    }

    private Integer extractContextLength(JsonNode modelNode) {
        JsonNode direct = modelNode.get("context_length");
        if (direct != null && direct.canConvertToInt()) {
            return direct.asInt();
        }

        JsonNode topProvider = modelNode.get("top_provider");
        if (topProvider != null) {
            JsonNode fromProvider = topProvider.get("context_length");
            if (fromProvider != null && fromProvider.canConvertToInt()) {
                return fromProvider.asInt();
            }
        }

        return null;
    }

    private Double parsePricePerMillion(String rawPrice) {
        if (rawPrice == null || rawPrice.isBlank()) {
            return null;
        }
        try {
            double pricePerToken = Double.parseDouble(rawPrice);
            if (pricePerToken <= 0) {
                return null;
            }
            return pricePerToken * 1_000_000d;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean supportsTextOutput(JsonNode modelNode) {
        JsonNode architecture = modelNode.get("architecture");
        if (architecture == null || architecture.isMissingNode()) {
            return true;
        }

        String modality = architecture.path("modality").asText("").toLowerCase(Locale.ROOT);
        if (modality.contains("text")) {
            return true;
        }

        JsonNode outputModalities = architecture.get("output_modalities");
        if (outputModalities != null && outputModalities.isArray()) {
            for (JsonNode modalityNode : outputModalities) {
                if ("text".equalsIgnoreCase(modalityNode.asText())) {
                    return true;
                }
            }
            return false;
        }

        return modality.isBlank();
    }

    private boolean containsAny(String value, List<String> keywords) {
        for (String keyword : keywords) {
            if (!keyword.isBlank() && value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private List<String> parseCsv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }

        return List.of(value.split(",")).stream()
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .map(part -> part.toLowerCase(Locale.ROOT))
                .toList();
    }

    private ModelFilterPolicy buildModelFilterPolicy() {
        List<String> featuredProviders = parseCsv(featuredProvidersConfig);
        return new ModelFilterPolicy(
                new HashSet<>(featuredProviders),
                parseCsv(featuredKeywordsConfig),
                parseCsv(excludedModelKeywordsConfig),
                parseCsv(providerOrderConfig)
        );
    }

    private String truncateForLog(String body) {
        if (body == null) {
            return "";
        }
        if (body.length() <= 500) {
            return body;
        }
        return body.substring(0, 500) + "...";
    }

    public record ChatMsg(String role, String content) {}

    public record LlmResponse(
            String content,
            int promptTokens,
            int completionTokens,
            Double costUsd,
            long responseTimeMs,
            String rawResponse,
            boolean timedOut,
            String errorMessage
    ) {}

    private record ModelCandidate(
            String id,
            String name,
            String provider,
            Integer contextLength,
            Double promptPricePerMillion,
            Double completionPricePerMillion,
            boolean featured,
            String searchText
    ) {}

    private record ModelFilterPolicy(
            Set<String> featuredProviderSet,
            List<String> featuredKeywords,
            List<String> excludedKeywords,
            List<String> providerOrder
    ) {}
}
