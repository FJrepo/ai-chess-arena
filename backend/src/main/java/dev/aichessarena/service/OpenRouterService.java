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
import java.io.IOException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

@ApplicationScoped
public class OpenRouterService {

    private static final Logger LOG = Logger.getLogger(OpenRouterService.class);
    private static final int DEFAULT_LIMIT = 120;
    private static final int MAX_LIMIT = 300;
    /** {@code limit} value for "no cap": the whole (normalized) catalog in one response. */
    public static final int NO_LIMIT = 0;
    static final int DESCRIPTION_MAX_LENGTH = 400;
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

    @ConfigProperty(name = "game.default-max-tokens", defaultValue = "4096")
    int defaultMaxTokens;

    /**
     * Technical upper bound for one chat request, also when the tournament has no move time limit, so a hung
     * connection cannot block a game forever. Reaching it is a provider problem, not the model's timeout.
     */
    @ConfigProperty(name = "openrouter.request-safety-cap", defaultValue = "PT10M")
    Duration requestSafetyCap = Duration.ofMinutes(10);

    /** Monotonic clock for deadlines ({@link System#nanoTime()}); replaceable in tests. */
    LongSupplier nanoClock = System::nanoTime;

    /** Sees each chat request's response future right after it is sent; a test seam for a delayed caller. */
    Consumer<CompletableFuture<?>> sendObserver = sent -> { };

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

    /** Sends one chat completion whose complete answer must arrive within {@code answerTime} from now. */
    public LlmResponse chat(String modelId, List<ChatMsg> messages, Duration answerTime) {
        return chat(modelId, messages, AnswerDeadline.after(answerTime, nanoClock.getAsLong()), defaultMaxTokens);
    }

    /**
     * Sends one chat completion and waits for the complete answer. {@code maxOutputTokens} caps the generated
     * tokens of the answer (OpenRouter {@code max_tokens}, which includes reasoning tokens for reasoning models).
     *
     * <p>{@code answerDeadline} is the absolute point by which the whole answer, up to the last byte of the
     * response body, must have arrived; preparing the request counts against it, and a deadline that has already
     * passed fails at once without sending anything. Exceeding it is the model's timeout
     * ({@link ProviderOutcome#TIMEOUT}), unless the response had already started with an error status: then the
     * provider classification of that status applies. {@code null} means no move time limit. Every request is
     * also bounded by the technical safety cap ({@code openrouter.request-safety-cap}); reaching that cap first
     * is a provider problem ({@link ProviderOutcome#RETRYABLE}), never the model's timeout.
     */
    public LlmResponse chat(String modelId, List<ChatMsg> messages, AnswerDeadline answerDeadline,
                            int maxOutputTokens) {
        long startNanos = nanoClock.getAsLong();
        AnswerDeadline safetyDeadline = AnswerDeadline.after(requestSafetyCap, startNanos);
        boolean limitedByMoveTime = answerDeadline != null && answerDeadline.isBefore(safetyDeadline);
        AnswerDeadline deadline = limitedByMoveTime ? answerDeadline : safetyDeadline;
        if (deadline.remainingNanos(startNanos) <= 0) {
            return deadlineExceeded(modelId, limitedByMoveTime, 0, 0);
        }
        // Known as soon as the status line and headers arrive, long before an error body may be complete.
        AtomicInteger responseStatus = new AtomicInteger();
        CompletableFuture<HttpResponse<String>> pending = null;
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", modelId);
            body.put("temperature", defaultTemperature);
            body.put("max_tokens", maxOutputTokens);

            ArrayNode msgs = body.putArray("messages");
            for (ChatMsg msg : messages) {
                ObjectNode m = msgs.addObject();
                m.put("role", msg.role());
                m.put("content", msg.content());
            }

            String jsonBody = objectMapper.writeValueAsString(body);

            long sendBudgetNanos = deadline.remainingNanos(nanoClock.getAsLong());
            if (sendBudgetNanos <= 0) {
                // Preparing the request used up the rest of the time: do not send it.
                return deadlineExceeded(modelId, limitedByMoveTime, 0, elapsedMs(startNanos));
            }
            // The request timeout only bounds the time until the response headers arrive, and OpenRouter starts
            // long answers early: the deadline for the complete body is enforced by waiting on the future below.
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(Duration.ofNanos(sendBudgetNanos))
                    .build();

            // The completion time is taken when the last body byte has been read, before the future completes, so it
            // is known whenever the future is done and the deadline is judged by it, not by when the caller looks.
            AtomicReference<Long> completedAtNanos = new AtomicReference<>();
            pending = httpClient.sendAsync(request, info -> {
                responseStatus.set(info.statusCode());
                return HttpResponse.BodySubscribers.mapping(HttpResponse.BodyHandlers.ofString().apply(info),
                        responseBody -> {
                            completedAtNanos.set(nanoClock.getAsLong());
                            return responseBody;
                        });
            });
            sendObserver.accept(pending);
            HttpResponse<String> response = awaitAnswer(pending, deadline, completedAtNanos);
            long elapsed = elapsedMs(startNanos);

            if (response.statusCode() != 200) {
                LOG.errorf("OpenRouter API error %d: %s", response.statusCode(), truncateForLog(response.body()));
                return LlmResponse.providerFailure(
                        classifyHttpStatus(response.statusCode()),
                        response.statusCode(),
                        describeHttpFailure(response.statusCode(), response.body()),
                        elapsed,
                        response.body()
                );
            }

            return parseCompletion(modelId, response.body(), elapsed);
        } catch (TimeoutException e) {
            pending.cancel(true);
            return deadlineExceeded(modelId, limitedByMoveTime, responseStatus.get(), elapsedMs(startNanos));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() instanceof CompletionException && e.getCause().getCause() != null
                    ? e.getCause().getCause()
                    : e.getCause();
            return transportFailure(modelId, cause, limitedByMoveTime, responseStatus.get(), elapsedMs(startNanos));
        } catch (InterruptedException e) {
            if (pending != null) {
                pending.cancel(true);
            }
            Thread.currentThread().interrupt();
            return LlmResponse.providerFailure(ProviderOutcome.RETRYABLE, 0,
                    "OpenRouter request was interrupted", 0, null);
        } catch (Exception e) {
            if (pending != null) {
                pending.cancel(true);
            }
            LOG.error("OpenRouter API call failed", e);
            return LlmResponse.providerFailure(ProviderOutcome.FATAL, 0,
                    "OpenRouter request failed: " + abbreviate(String.valueOf(e.getMessage())), 0, null);
        }
    }

    /**
     * Waits until the deadline for the complete response. An answer that was complete before the deadline is used
     * even if this thread only looks after it (a delayed caller); only an answer that was really incomplete at the
     * deadline ends in a {@link TimeoutException}.
     */
    private HttpResponse<String> awaitAnswer(CompletableFuture<HttpResponse<String>> pending, AnswerDeadline deadline,
                                             AtomicReference<Long> completedAtNanos)
            throws InterruptedException, ExecutionException, TimeoutException {
        long waitNanos = Math.max(0L, deadline.remainingNanos(nanoClock.getAsLong()));
        HttpResponse<String> response;
        try {
            response = pending.get(waitNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            if (!pending.isDone()) {
                throw e;
            }
            response = pending.get(); // completed right as the wait ended; judged by its completion time below
        }
        Long completedAt = completedAtNanos.get();
        if (completedAt != null && deadline.remainingNanos(completedAt) < 0) {
            throw new TimeoutException("The answer was complete only after the deadline");
        }
        return response;
    }

    private LlmResponse parseCompletion(String modelId, String responseBody, long elapsed) {
        JsonNode json;
        try {
            json = objectMapper.readTree(responseBody);
        } catch (IOException e) {
            LOG.warnf("OpenRouter returned a non-JSON body for model %s", modelId);
            return LlmResponse.providerFailure(ProviderOutcome.RETRYABLE, 200,
                    "OpenRouter returned a malformed response", elapsed, responseBody);
        }
        if (json == null || json.hasNonNull("error") || !json.path("choices").isArray()
                || json.path("choices").isEmpty()) {
            String providerMessage = json == null ? null : json.path("error").path("message").asText(null);
            LOG.warnf("OpenRouter returned no choices for model %s: %s", modelId, providerMessage);
            return LlmResponse.providerFailure(ProviderOutcome.RETRYABLE, 200,
                    providerMessage == null
                            ? "OpenRouter returned a response without choices"
                            : "OpenRouter upstream error: " + abbreviate(providerMessage),
                    elapsed, responseBody);
        }
        JsonNode message = json.path("choices").get(0).path("message");
        if (!message.isObject()) {
            LOG.warnf("OpenRouter returned a choice without a message object for model %s", modelId);
            return LlmResponse.providerFailure(ProviderOutcome.RETRYABLE, 200,
                    "OpenRouter returned a malformed choice", elapsed, responseBody);
        }
        String content = message.path("content").asText(null);
        String finishReason = json.path("choices").get(0).path("finish_reason").asText(null);
        JsonNode reasoningNode = json.at("/usage/completion_tokens_details/reasoning_tokens");
        Integer reasoningTokens = reasoningNode.isNumber() ? reasoningNode.asInt() : null;
        int promptTokens = json.at("/usage/prompt_tokens").asInt(0);
        int completionTokens = json.at("/usage/completion_tokens").asInt(0);

        // Try to get cost from response, or leave null
        Double cost = null;
        if (json.has("usage") && json.get("usage").has("cost")) {
            cost = json.get("usage").get("cost").asDouble();
        }

        return new LlmResponse(content, promptTokens, completionTokens, cost, elapsed, responseBody, false, null,
                ProviderOutcome.OK, 200, finishReason, reasoningTokens);
    }

    private LlmResponse transportFailure(String modelId, Throwable cause, boolean limitedByMoveTime,
                                         int responseStatus, long elapsed) {
        if (cause instanceof HttpConnectTimeoutException) {
            LOG.warnf("Could not connect to OpenRouter for model %s: %s", modelId, cause.getMessage());
            return LlmResponse.providerFailure(ProviderOutcome.RETRYABLE, 0,
                    "Could not connect to OpenRouter (connection timed out)", 0, null);
        }
        if (cause instanceof HttpTimeoutException) {
            // No response headers within the wait: the same deadline as for the complete answer.
            return deadlineExceeded(modelId, limitedByMoveTime, responseStatus, elapsed);
        }
        if (cause instanceof IOException) {
            LOG.warnf("OpenRouter request failed for model %s: %s", modelId, cause.toString());
            return LlmResponse.providerFailure(ProviderOutcome.RETRYABLE, 0,
                    "Could not reach OpenRouter: " + abbreviate(String.valueOf(cause.getMessage())), 0, null);
        }
        LOG.error("OpenRouter API call failed", cause);
        String detail = cause == null ? null : cause.getMessage();
        return LlmResponse.providerFailure(ProviderOutcome.FATAL, 0,
                "OpenRouter request failed: " + abbreviate(String.valueOf(detail)), 0, null);
    }

    /**
     * The wait ended before the complete answer arrived. {@code responseStatus} is the HTTP status if the response
     * had started (0 otherwise): an error status is OpenRouter's failure even when its body stalls, never the
     * model's timeout.
     */
    private LlmResponse deadlineExceeded(String modelId, boolean limitedByMoveTime, int responseStatus,
                                         long elapsed) {
        if (responseStatus != 0 && responseStatus != 200) {
            LOG.warnf("OpenRouter answered HTTP %d for model %s, but the error body did not arrive in time",
                    responseStatus, modelId);
            return LlmResponse.providerFailure(classifyHttpStatus(responseStatus), responseStatus,
                    describeHttpFailure(responseStatus, null), elapsed, null);
        }
        if (limitedByMoveTime) {
            LOG.warnf("Model %s did not finish its answer within the move time (%d ms)", modelId, elapsed);
            return new LlmResponse(null, 0, 0, null, elapsed, null, true, "No complete answer within the move time");
        }
        LOG.warnf("OpenRouter request for model %s exceeded the safety cap of %s", modelId, describe(requestSafetyCap));
        return LlmResponse.providerFailure(ProviderOutcome.RETRYABLE, 0,
                "OpenRouter request did not finish within the safety limit of " + describe(requestSafetyCap),
                elapsed, null);
    }

    private static String describe(Duration duration) {
        long seconds = duration.toSeconds();
        if (seconds >= 60 && seconds % 60 == 0) {
            return seconds / 60 + " min";
        }
        return seconds >= 1 ? seconds + " s" : duration.toMillis() + " ms";
    }

    private long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(nanoClock.getAsLong() - startNanos);
    }

    static ProviderOutcome classifyHttpStatus(int status) {
        if (status == 408 || status == 429 || status >= 500) {
            return ProviderOutcome.RETRYABLE;
        }
        return ProviderOutcome.FATAL;
    }

    private String describeHttpFailure(int status, String body) {
        String providerMessage = null;
        try {
            JsonNode json = body == null ? null : objectMapper.readTree(body);
            if (json != null) {
                providerMessage = json.path("error").path("message").asText(null);
            }
        } catch (IOException ignored) {
            // Non-JSON error body; fall back to the status description.
        }
        String summary = switch (status) {
            case 400 -> "OpenRouter rejected the request";
            case 401 -> "OpenRouter rejected the API key";
            case 402 -> "OpenRouter account has insufficient credits";
            case 403 -> "OpenRouter denied the request";
            case 404 -> "Model not found on OpenRouter";
            case 408 -> "OpenRouter request timed out upstream";
            case 429 -> "OpenRouter rate limit reached";
            default -> status >= 500 ? "OpenRouter server error" : "OpenRouter request failed";
        };
        String detail = summary + " (HTTP " + status + ")";
        if (providerMessage != null && !providerMessage.isBlank()) {
            detail += ": " + abbreviate(providerMessage);
        }
        return detail;
    }

    private static String abbreviate(String text) {
        String singleLine = text.replaceAll("\\s+", " ").trim();
        return singleLine.length() <= 200 ? singleLine : singleLine.substring(0, 200) + "...";
    }

    public OpenRouterModelsResponseDto listModels(boolean featuredOnly, String query, Integer limit, String provider) {
        JsonNode rawModels = fetchRawModels();
        JsonNode data = rawModels.get("data");
        if (data == null || !data.isArray()) {
            String error = rawModels.path("error").asText("Failed to fetch OpenRouter models");
            return new OpenRouterModelsResponseDto(List.of(), 0, 0, error, parseCsv(featuredProvidersConfig));
        }

        ModelFilterPolicy policy = buildModelFilterPolicy();

        List<ModelCandidate> candidates = normalizeCandidates(
                (ArrayNode) data,
                policy.featuredProviderSet(),
                policy.featuredKeywords(),
                policy.excludedKeywords()
        );

        int featuredCount = (int) candidates.stream().filter(candidate -> candidate.option().featured()).count();

        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String normalizedProvider = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        List<ModelCandidate> filtered = candidates.stream()
                .filter(candidate -> !featuredOnly || candidate.option().featured())
                .filter(candidate -> normalizedProvider.isEmpty()
                        || normalizedProvider.equals(candidate.option().provider()))
                .filter(candidate -> normalizedQuery.isEmpty() || candidate.searchText().contains(normalizedQuery))
                .sorted(buildComparator(policy.providerOrder()))
                .toList();

        int totalMatched = filtered.size();
        int normalizedLimit = normalizeLimit(limit);
        List<OpenRouterModelOptionDto> result = filtered.stream()
                .limit(normalizedLimit)
                .map(ModelCandidate::option)
                .toList();

        return new OpenRouterModelsResponseDto(result, totalMatched, featuredCount, null,
                parseCsv(featuredProvidersConfig));
    }

    public boolean isModelAllowed(String modelId) {
        String normalizedModelId = modelId == null ? "" : modelId.trim();
        if (normalizedModelId.isEmpty()) {
            return false;
        }

        JsonNode rawModels = fetchRawModels();
        JsonNode data = rawModels.get("data");
        // The fallback for an unavailable catalog carries an empty "data" array plus an "error".
        if (data == null || !data.isArray() || rawModels.hasNonNull("error")) {
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
        ).stream().anyMatch(candidate -> normalizedModelId.equals(candidate.option().id()));
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
            JsonNode pricing = node.path("pricing");
            boolean featured = featuredProviderSet.contains(provider) || containsAny(idLower, featuredKeywords);
            String searchText = (name + " " + id + " " + provider).toLowerCase(Locale.ROOT);
            JsonNode reasoning = node.path("reasoning");
            JsonNode mandatory = reasoning.path("mandatory");

            result.add(new ModelCandidate(
                    new OpenRouterModelOptionDto(
                            id,
                            name,
                            provider,
                            contextLength,
                            parsePricePerMillion(pricing.path("prompt")),
                            parsePricePerMillion(pricing.path("completion")),
                            featured,
                            parseReleaseDate(node.path("created")),
                            parsePricePerMillion(pricing.path("input_cache_read")),
                            positiveInt(node.path("top_provider").path("max_completion_tokens")),
                            supportsReasoning(node),
                            mandatory.isBoolean() ? mandatory.booleanValue() : null,
                            parseStringList(reasoning.path("supported_efforts")),
                            finiteNumber(node.path("benchmarks").path("artificial_analysis")
                                    .path("intelligence_index")),
                            trimDescription(node.path("description").asText(null))
                    ),
                    searchText
            ));
            seenIds.add(id);
        }

        return result;
    }

    private Comparator<ModelCandidate> buildComparator(List<String> providerOrder) {
        Comparator<OpenRouterModelOptionDto> options = Comparator
                .comparing(OpenRouterModelOptionDto::featured).reversed()
                .thenComparingInt(option -> providerRank(option.provider(), providerOrder))
                .thenComparing(option -> option.contextLength() == null ? 0 : option.contextLength(),
                        Comparator.reverseOrder())
                .thenComparing(OpenRouterModelOptionDto::name);
        return Comparator.comparing(ModelCandidate::option, options);
    }

    private int providerRank(String provider, List<String> providerOrder) {
        int index = providerOrder.indexOf(provider);
        return index == -1 ? Integer.MAX_VALUE : index;
    }

    /** {@code 0} means no cap (the whole matching catalog); other values are clamped to 1..{@value #MAX_LIMIT}. */
    private int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit == NO_LIMIT) {
            return Integer.MAX_VALUE;
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

    /**
     * USD per million tokens from a per-token price (a string such as "0.0000015", or a number). {@code 0} stays
     * {@code 0} (a free model); negative values (OpenRouter uses "-1" for routers with variable pricing),
     * non-numeric, missing and null prices are unknown ({@code null}).
     */
    static Double parsePricePerMillion(JsonNode priceNode) {
        if (priceNode == null || priceNode.isMissingNode() || priceNode.isNull()) {
            return null;
        }
        String rawPrice = priceNode.asText("").trim();
        if (rawPrice.isEmpty()) {
            return null;
        }
        try {
            double pricePerToken = Double.parseDouble(rawPrice);
            if (!Double.isFinite(pricePerToken) || pricePerToken < 0) {
                return null;
            }
            return pricePerToken * 1_000_000d;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** UTC calendar date (YYYY-MM-DD) of a unix timestamp in seconds; null when missing or not positive. */
    static String parseReleaseDate(JsonNode createdNode) {
        if (createdNode == null || !createdNode.isNumber() || createdNode.asLong() <= 0) {
            return null;
        }
        return LocalDate.ofInstant(Instant.ofEpochSecond(createdNode.asLong()), ZoneOffset.UTC).toString();
    }

    private static Integer positiveInt(JsonNode node) {
        if (node == null || !node.isNumber() || !node.canConvertToInt() || node.asInt() <= 0) {
            return null;
        }
        return node.asInt();
    }

    private static Double finiteNumber(JsonNode node) {
        if (node == null || !node.isNumber() || !Double.isFinite(node.asDouble())) {
            return null;
        }
        return node.asDouble();
    }

    private static List<String> parseStringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode element : node) {
            if (element.isTextual() && !element.asText().isBlank()) {
                values.add(element.asText().trim());
            }
        }
        return List.copyOf(values);
    }

    /** A model supports reasoning when it lists the "reasoning" parameter or carries a reasoning object. */
    private static boolean supportsReasoning(JsonNode modelNode) {
        if (modelNode.path("reasoning").isObject()) {
            return true;
        }
        JsonNode parameters = modelNode.path("supported_parameters");
        if (parameters.isArray()) {
            for (JsonNode parameter : parameters) {
                if ("reasoning".equals(parameter.asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Collapses whitespace and shortens the text to at most {@value #DESCRIPTION_MAX_LENGTH} characters, cut at
     * a word boundary and ending with "…"; null for a missing or blank description.
     */
    static String trimDescription(String rawDescription) {
        if (rawDescription == null) {
            return null;
        }
        String text = rawDescription.replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) {
            return null;
        }
        if (text.length() <= DESCRIPTION_MAX_LENGTH) {
            return text;
        }
        int limit = DESCRIPTION_MAX_LENGTH - 1;
        int cut = text.lastIndexOf(' ', limit);
        String head = cut > limit / 2 ? text.substring(0, cut) : text.substring(0, limit);
        return head.replaceAll("[\\s,;:.\\-]+$", "") + "…";
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

    /**
     * An absolute point on the monotonic clock ({@link System#nanoTime()}) by which a complete answer must have
     * arrived. A turn shares one deadline across all its attempts, so request preparation and earlier attempts
     * count against it and no attempt gets extra time.
     */
    public record AnswerDeadline(long atNanos) {
        public static AnswerDeadline after(Duration time, long nowNanos) {
            return new AnswerDeadline(nowNanos + time.toNanos());
        }

        /** Time left at {@code nowNanos}; zero or negative once the deadline has passed. */
        public long remainingNanos(long nowNanos) {
            return atNanos - nowNanos;
        }

        boolean isBefore(AnswerDeadline other) {
            return atNanos - other.atNanos < 0;
        }
    }

    /**
     * How an OpenRouter call ended, from the provider's point of view.
     * Only {@link #OK} responses are the model's own output; {@link #TIMEOUT} means the model
     * did not answer within the move deadline. {@link #RETRYABLE} and {@link #FATAL} are
     * infrastructure failures (network, rate limits, auth, credits) and must never be
     * attributed to the model.
     */
    public enum ProviderOutcome {
        OK,
        TIMEOUT,
        RETRYABLE,
        FATAL
    }

    public record LlmResponse(
            String content,
            int promptTokens,
            int completionTokens,
            Double costUsd,
            long responseTimeMs,
            String rawResponse,
            boolean timedOut,
            String errorMessage,
            ProviderOutcome outcome,
            int httpStatus,
            String finishReason,
            Integer reasoningTokens
    ) {
        public LlmResponse(String content, int promptTokens, int completionTokens, Double costUsd,
                           long responseTimeMs, String rawResponse, boolean timedOut, String errorMessage) {
            this(content, promptTokens, completionTokens, costUsd, responseTimeMs, rawResponse, timedOut,
                    errorMessage, timedOut ? ProviderOutcome.TIMEOUT : ProviderOutcome.OK, timedOut ? 0 : 200,
                    null, null);
        }

        static LlmResponse providerFailure(ProviderOutcome outcome, int httpStatus, String errorMessage,
                                           long responseTimeMs, String rawResponse) {
            return new LlmResponse(null, 0, 0, null, responseTimeMs, rawResponse, false,
                    errorMessage, outcome, httpStatus, null, null);
        }

        /** The answer stopped because it hit the output-token limit. */
        public boolean truncated() {
            return "length".equals(finishReason);
        }

        public boolean isProviderFailure() {
            return outcome == ProviderOutcome.RETRYABLE || outcome == ProviderOutcome.FATAL;
        }
    }

    private record ModelCandidate(OpenRouterModelOptionDto option, String searchText) {}

    private record ModelFilterPolicy(
            Set<String> featuredProviderSet,
            List<String> featuredKeywords,
            List<String> excludedKeywords,
            List<String> providerOrder
    ) {}
}
