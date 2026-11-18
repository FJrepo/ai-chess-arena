package dev.aichessarena.service;

import dev.aichessarena.dto.OpenRouterModelOptionDto;
import dev.aichessarena.entity.ReasoningEffort;
import dev.aichessarena.dto.OpenRouterModelsResponseDto;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.UnresolvedAddressException;
import java.util.concurrent.Flow;
import javax.net.ssl.SSLHandshakeException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
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
    /** Prefix of OpenRouter's moving "latest" model aliases, which route to the provider's newest model. */
    static final String LATEST_ALIAS_PREFIX = "~";
    /** 9999-12-31T23:59:59Z; later (or misscaled, e.g. nanosecond) timestamps are not release dates. */
    private static final long MAX_RELEASE_EPOCH_SECOND = 253_402_300_799L;
    private static final long MODELS_CACHE_TTL_MS = 5 * 60 * 1000L;
    private static final long MODELS_FAILURE_BACKOFF_MS = 60_000L;

    @Inject
    OpenRouterKeyStore keyStore;

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

    @ConfigProperty(name = "game.default-max-tokens", defaultValue = "16384")
    int defaultMaxTokens;

    /**
     * Technical upper bound for one chat request, also when the tournament has no move time limit, so a hung
     * connection cannot block a game forever. Reaching it is a provider problem, not the model's timeout.
     */
    @ConfigProperty(name = "openrouter.request-safety-cap", defaultValue = "PT10M")
    Duration requestSafetyCap = Duration.ofMinutes(10);

    @ConfigProperty(name = "openrouter.prompt-caching", defaultValue = "true")
    boolean promptCaching = true;

    /** Monotonic clock for deadlines ({@link System#nanoTime()}); replaceable in tests. */
    LongSupplier nanoClock = System::nanoTime;

    /** Wall clock for catalog freshness and retry back-off; replaceable in tests. */
    LongSupplier modelsClock = System::currentTimeMillis;

    /** Called after the initial catalog snapshot read; a test seam for a concurrent refresh. */
    Runnable modelsSnapshotObserver = () -> { };

    /** Sees each chat request's response future right after it is sent; a test seam for a delayed caller. */
    Consumer<CompletableFuture<?>> sendObserver = sent -> { };

    /**
     * Admits every further request one {@link #chat} call sends after its first (the plain fallback after a
     * cache-control rejection); the caller admits the first itself. Unbound: always admitted.
     */
    @FunctionalInterface
    public interface SendGate {
        /**
         * {@code answered} is the complete answer to the previous request (the rejection, with any reported usage):
         * the gate records it before admitting another request, and refuses if it cannot.
         */
        boolean admitAnotherRequest(LlmResponse answered);
    }

    /**
     * Statuses whose complete, parseable error document proves that OpenRouter did not run (and so did not bill) the
     * request: validation (400, 413, 422), authentication and credits (401, 402), moderation or permission (403), an
     * unknown model or endpoint (404), rate limiting (429) and server errors reported before generation (500, 502,
     * 503, 504). Any other status after sending (e.g. 408 or a proxy timeout such as 524) may follow a generation.
     */
    static final Set<Integer> NON_BILLABLE_STATUSES = Set.of(400, 401, 402, 403, 404, 413, 422, 429, 500, 502, 503, 504);

    public static final ScopedValue<SendGate> SEND_GATE = ScopedValue.newInstance();

    @Inject
    ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private volatile ModelsSnapshot modelsSnapshot = new ModelsSnapshot(null, 0, 0);
    private final AtomicBoolean modelsRefreshScheduled = new AtomicBoolean();
    /** Endpoint rejections override catalog hints until the backend process restarts. */
    private final Set<String> explicitCachingRejected = ConcurrentHashMap.newKeySet();

    public LlmResponse chat(String modelId, List<ChatMsg> messages) {
        return chat(modelId, messages, (AnswerDeadline) null, defaultMaxTokens);
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
     * provider classification of that status applies. A cache-control rejection also keeps its provider
     * attribution if its plain retry exhausts the deadline. {@code null} means no move time limit. Every request is
     * also bounded by the technical safety cap ({@code openrouter.request-safety-cap}); reaching that cap first
     * is a provider problem ({@link ProviderOutcome#RETRYABLE}), never the model's timeout.
     */
    public LlmResponse chat(String modelId, List<ChatMsg> messages, AnswerDeadline answerDeadline,
                            int maxOutputTokens) {
        return chat(modelId, messages, answerDeadline, maxOutputTokens, null);
    }

    /**
     * As {@link #chat(String, List, AnswerDeadline, int)}, with OpenRouter's {@code "reasoning": {"effort": ...}}
     * when {@code reasoningEffort} is set; null sends no reasoning parameter (the model's default). The level is sent
     * as given: map it to one the model supports with {@link #resolveReasoningEffort} first.
     */
    public LlmResponse chat(String modelId, List<ChatMsg> messages, AnswerDeadline answerDeadline,
                            int maxOutputTokens, ReasoningEffort reasoningEffort) {
        JsonNode model = cachedCatalogEntry(modelId);
        Integer maximum = model == null ? null
                : positiveInt(model.path("top_provider").path("max_completion_tokens"));
        int effective = maximum == null ? maxOutputTokens : Math.min(maxOutputTokens, maximum);
        if (effective < maxOutputTokens) {
            LOG.infof("Clamping max_tokens for %s from %d to catalog maximum %d", modelId, maxOutputTokens, maximum);
        }
        return chatRequest(modelId, messages, answerDeadline, effective, reasoningEffort)
                .withOutputTokenLimits(effective, maximum);
    }

    private LlmResponse chatRequest(String modelId, List<ChatMsg> messages, AnswerDeadline answerDeadline,
                                    int maxOutputTokens, ReasoningEffort reasoningEffort) {
        if (effectiveKey().isBlank()) {
            return LlmResponse.providerFailure(ProviderOutcome.FATAL, 0, "Connect OpenRouter to run games", 0, null);
        }
        long startNanos = nanoClock.getAsLong();
        AnswerDeadline safetyDeadline = AnswerDeadline.after(requestSafetyCap, startNanos);
        boolean limitedByMoveTime = answerDeadline != null && answerDeadline.isBefore(safetyDeadline);
        AnswerDeadline deadline = limitedByMoveTime ? answerDeadline : safetyDeadline;
        return sendChatRequest(modelId, messages, maxOutputTokens, reasoningEffort, startNanos, deadline,
                limitedByMoveTime, null);
    }

    private LlmResponse sendChatRequest(String modelId, List<ChatMsg> messages, int maxOutputTokens,
                                        ReasoningEffort reasoningEffort, long startNanos, AnswerDeadline deadline,
                                        boolean limitedByMoveTime, CacheRejection cacheRejection) {
        if (deadline.remainingNanos(nanoClock.getAsLong()) <= 0) {
            return deadlineExceeded(modelId, limitedByMoveTime, 0, elapsedMs(startNanos), cacheRejection, false);
        }
        // Known as soon as the status line and headers arrive, long before an error body may be complete.
        AtomicInteger responseStatus = new AtomicInteger();
        // Set once the whole request body was handed to the connection: from then on OpenRouter may have received it.
        AtomicBoolean bodyHandedOver = new AtomicBoolean();
        CompletableFuture<HttpResponse<String>> pending = null;
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", modelId);
            body.put("temperature", defaultTemperature);
            body.put("max_tokens", maxOutputTokens);
            if (reasoningEffort != null) {
                body.putObject("reasoning").put("effort", reasoningEffort.apiValue());
            }

            ArrayNode msgs = body.putArray("messages");
            int breakpoint = cacheRejection == null && promptCaching && needsCacheBreakpoint(modelId)
                    ? stablePrefixEnd(messages) : -1;
            for (int i = 0; i < messages.size(); i++) {
                ChatMsg msg = messages.get(i);
                ObjectNode m = msgs.addObject();
                m.put("role", msg.role());
                if (i == breakpoint) {
                    ObjectNode block = m.putArray("content").addObject();
                    block.put("type", "text");
                    block.put("text", msg.content());
                    block.putObject("cache_control").put("type", "ephemeral");
                } else {
                    m.put("content", msg.content());
                }
            }

            String jsonBody = objectMapper.writeValueAsString(body);

            long sendBudgetNanos = deadline.remainingNanos(nanoClock.getAsLong());
            if (sendBudgetNanos <= 0) {
                // Preparing the request used up the rest of the time: do not send it.
                return deadlineExceeded(modelId, limitedByMoveTime, 0, elapsedMs(startNanos), cacheRejection, false);
            }
            // The request timeout only bounds the time until the response headers arrive, and OpenRouter starts
            // long answers early: the deadline for the complete body is enforced by waiting on the future below.
            String requestKey = effectiveKey();
            if (requestKey.isBlank()) {
                return LlmResponse.providerFailure(ProviderOutcome.FATAL, 0, "Connect OpenRouter to run games", 0, null);
            }
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Authorization", "Bearer " + requestKey)
                    .header("Content-Type", "application/json")
                    .POST(tracked(HttpRequest.BodyPublishers.ofString(jsonBody), bodyHandedOver))
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
                            return responseBody.replace(requestKey, "[redacted]");
                        });
            });
            sendObserver.accept(pending);
            HttpResponse<String> response = awaitAnswer(pending, deadline, completedAtNanos);
            long elapsed = elapsedMs(startNanos);

            if (response.statusCode() != 200) {
                LlmResponse failure = errorResponse(modelId, response.statusCode(), response.body(), elapsed);
                if (breakpoint >= 0 && !failure.deliveryUncertain()
                        && isCacheControlRejection(response.statusCode(), response.body())) {
                    explicitCachingRejected.add(modelId);
                    CacheRejection rejection = new CacheRejection(response.statusCode(), response.body(),
                            SEND_GATE.isBound());
                    if (deadline.remainingNanos(nanoClock.getAsLong()) <= 0) {
                        // No time for a plain retry: the rejection is the answer, recorded by the caller.
                        return deadlineExceeded(modelId, limitedByMoveTime, 0, elapsed,
                                new CacheRejection(rejection.status(), rejection.body(), false), false);
                    }
                    // The rejection (and any usage it reports) is recorded and the plain retry admitted like any request.
                    if (rejection.recorded() && !SEND_GATE.get().admitAnotherRequest(failure)) {
                        LOG.warnf("OpenRouter rejected explicit caching for %s; the game cannot send a plain retry now",
                                modelId);
                        return failure;
                    }
                    LOG.warnf("OpenRouter rejected explicit caching for %s; retrying once with plain messages", modelId);
                    return sendChatRequest(modelId, messages, maxOutputTokens, reasoningEffort, startNanos, deadline,
                            limitedByMoveTime, rejection).withCacheRejectionStatus(response.statusCode());
                }
                return failure;
            }

            return parseCompletion(modelId, response.body(), elapsed);
        } catch (TimeoutException e) {
            pending.cancel(true);
            return deadlineExceeded(modelId, limitedByMoveTime, responseStatus.get(), elapsedMs(startNanos), cacheRejection,
                    bodyHandedOver.get());
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() instanceof CompletionException && e.getCause().getCause() != null
                    ? e.getCause().getCause()
                    : e.getCause();
            return transportFailure(modelId, cause, limitedByMoveTime, responseStatus.get(), elapsedMs(startNanos),
                    cacheRejection, bodyHandedOver.get());
        } catch (InterruptedException e) {
            if (pending != null) {
                pending.cancel(true);
            }
            Thread.currentThread().interrupt();
            LlmResponse interrupted = LlmResponse.providerFailure(ProviderOutcome.RETRYABLE, 0,
                    "OpenRouter request was interrupted", 0, null);
            return bodyHandedOver.get() ? interrupted.withDeliveryUncertain() : interrupted;
        } catch (Exception e) {
            if (pending != null) {
                pending.cancel(true);
            }
            LOG.error("OpenRouter API call failed");
            LlmResponse failed = LlmResponse.providerFailure(ProviderOutcome.FATAL, 0,
                    "OpenRouter request failed", 0, null);
            return bodyHandedOver.get() ? failed.withDeliveryUncertain() : failed;
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

    /**
     * The one place that classifies a complete error response to a sent request. It is a provider failure that may be
     * retried only with a status from {@link #NON_BILLABLE_STATUSES} and OpenRouter's error document
     * ({@code {"error": {...}}}); anything else may follow a generation and is delivery-uncertain.
     */
    private LlmResponse errorResponse(String modelId, int status, String body, long elapsed) {
        LOG.errorf("OpenRouter API error %d: %s", status, truncateForLog(body));
        if (!NON_BILLABLE_STATUSES.contains(status) || !isErrorDocument(body)) {
            return withReportedUsage(LlmResponse.providerFailure(ProviderOutcome.RETRYABLE, status,
                    describeHttpFailure(status, body) + "; the answer does not show that nothing was charged",
                    elapsed, body), body).withDeliveryUncertain();
        }
        return withReportedUsage(LlmResponse.providerFailure(
                isCacheControlRejection(status, body) ? ProviderOutcome.RETRYABLE : classifyHttpStatus(status),
                status, describeHttpFailure(status, body), elapsed, body), body);
    }

    /**
     * OpenRouter's documented error document and nothing else: one JSON object (no trailing content) whose
     * {@code error} object carries a {@code code} and a non-blank {@code message}.
     */
    private boolean isErrorDocument(String body) {
        try {
            JsonNode json = strictTree(body);
            JsonNode error = json == null ? null : json.path("error");
            return json != null && json.isObject() && error.isObject()
                    && (error.path("code").isNumber() || error.path("code").isTextual())
                    && error.path("message").isTextual() && !error.path("message").asText().isBlank();
        } catch (IOException e) {
            return false;
        }
    }

    /** Parses exactly one JSON value; trailing content (garbage or a second value) is an error. */
    private JsonNode strictTree(String body) throws IOException {
        return body == null ? null
                : objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
    }

    /**
     * A complete 200 response. Only a valid completion (a choice with a message object) is an answer; any other
     * body may follow a generation and is delivery-uncertain.
     */
    private LlmResponse parseCompletion(String modelId, String responseBody, long elapsed) {
        JsonNode json;
        try {
            json = strictTree(responseBody);
        } catch (IOException e) {
            LOG.warnf("OpenRouter returned a non-JSON body for model %s", modelId);
            return LlmResponse.providerFailure(ProviderOutcome.RETRYABLE, 200,
                    "OpenRouter returned a malformed response", elapsed, responseBody).withDeliveryUncertain();
        }
        if (json == null || json.hasNonNull("error") || !json.path("choices").isArray()
                || json.path("choices").isEmpty()) {
            String providerMessage = json == null ? null : json.path("error").path("message").asText(null);
            LOG.warnf("OpenRouter returned no choices for model %s: %s", modelId, providerMessage);
            return withReportedUsage(LlmResponse.providerFailure(ProviderOutcome.RETRYABLE, 200,
                    providerMessage == null
                            ? "OpenRouter returned a response without choices"
                            : "OpenRouter upstream error: " + abbreviate(providerMessage),
                    elapsed, responseBody), responseBody).withDeliveryUncertain();
        }
        JsonNode message = json.path("choices").get(0).path("message");
        if (!message.isObject()) {
            LOG.warnf("OpenRouter returned a choice without a message object for model %s", modelId);
            return withReportedUsage(LlmResponse.providerFailure(ProviderOutcome.RETRYABLE, 200,
                    "OpenRouter returned a malformed choice", elapsed, responseBody), responseBody)
                    .withDeliveryUncertain();
        }
        JsonNode choice = json.path("choices").get(0);
        String finishReason = choice.path("finish_reason").asText(null);
        if (choice.hasNonNull("error") || "error".equals(finishReason)) {
            // A generation that failed midway: partial content, but the usage so far may be billed.
            String providerMessage = choice.path("error").path("message").asText(null);
            LOG.warnf("OpenRouter reported an error inside the answer for model %s: %s", modelId, providerMessage);
            return withReportedUsage(LlmResponse.providerFailure(ProviderOutcome.RETRYABLE, 200,
                    providerMessage == null ? "OpenRouter reported an error inside the answer"
                            : "OpenRouter reported an error inside the answer: " + abbreviate(providerMessage),
                    elapsed, responseBody), responseBody).withDeliveryUncertain();
        }
        String content = message.path("content").asText(null);
        return new LlmResponse(content, 0, 0, null, elapsed, responseBody, false, null,
                ProviderOutcome.OK, 200, finishReason, null).withUsage(json.path("usage"));
    }

    /** Error responses can also contain billable usage. A non-JSON error has no usable counters. */
    private LlmResponse withReportedUsage(LlmResponse response, String body) {
        try {
            JsonNode json = objectMapper.readTree(body);
            return json == null ? response : response.withUsage(json.path("usage"));
        } catch (IOException e) {
            return response;
        }
    }

    /**
     * One immutable price snapshot for a comparison, without waiting for catalog I/O. Stale prices remain usable;
     * a single background refresh warms later comparisons. These estimates never replace OpenRouter's billed cost.
     */
    Map<String, BigDecimal> cacheReadSavingsSnapshot() {
        ModelsSnapshot cache = modelsSnapshot;
        JsonNode snapshot = cache.data();
        refreshModelsInBackground(cache);
        if (snapshot == null) {
            return Map.of();
        }
        Map<String, BigDecimal> savings = new HashMap<>();
        for (JsonNode model : snapshot.path("data")) {
            String id = model.path("id").asText(null);
            JsonNode pricing = model.path("pricing");
            BigDecimal input = nonNegativePrice(pricing.path("prompt"));
            BigDecimal read = nonNegativePrice(pricing.path("input_cache_read"));
            if (id != null && input != null && read != null) {
                savings.putIfAbsent(id, input.subtract(read));
            }
        }
        return Map.copyOf(savings);
    }

    private void refreshModelsInBackground(ModelsSnapshot snapshot) {
        if (objectMapper == null
                || !snapshot.needsRefresh(modelsClock.getAsLong())
                || !modelsRefreshScheduled.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                fetchRawModels();
            } finally {
                modelsRefreshScheduled.set(false);
            }
        });
    }

    private static BigDecimal nonNegativePrice(JsonNode node) {
        try {
            BigDecimal price = new BigDecimal(node.asText(""));
            return price.signum() < 0 ? null : price;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private LlmResponse transportFailure(String modelId, Throwable cause, boolean limitedByMoveTime,
                                         int responseStatus, long elapsed, CacheRejection cacheRejection,
                                         boolean bodyHandedOver) {
        if (cause instanceof HttpConnectTimeoutException) {
            LOG.warnf("Could not connect to OpenRouter for model %s", modelId);
            return LlmResponse.providerFailure(ProviderOutcome.RETRYABLE, 0,
                    "Could not connect to OpenRouter (connection timed out)", 0, null);
        }
        if (cause instanceof HttpTimeoutException) {
            // No response headers within the wait: the same deadline as for the complete answer.
            return deadlineExceeded(modelId, limitedByMoveTime, responseStatus, elapsed, cacheRejection,
                    bodyHandedOver && !notDelivered(cause));
        }
        if (cause instanceof IOException) {
            if (responseStatus != 0 || (bodyHandedOver && !notDelivered(cause))) {
                LOG.warnf("OpenRouter connection for model %s broke after the request was sent", modelId);
                return LlmResponse.providerFailure(ProviderOutcome.RETRYABLE, responseStatus,
                        "The connection to OpenRouter broke after the request was sent", elapsed, null)
                        .withDeliveryUncertain();
            }
            LOG.warnf("OpenRouter request failed for model %s", modelId);
            return LlmResponse.providerFailure(ProviderOutcome.RETRYABLE, 0,
                    "Could not reach OpenRouter", 0, null);
        }
        LOG.error("OpenRouter API call failed");
        return LlmResponse.providerFailure(ProviderOutcome.FATAL, 0,
                "OpenRouter request failed", 0, null);
    }

    /**
     * The wait ended before the complete answer arrived. {@code responseStatus} is the HTTP status if the response
     * had started (0 otherwise): an error status is OpenRouter's failure even when its body stalls, never the
     * model's timeout. A cache rejection remains the provider's fault if its plain retry exhausts the deadline.
     */
    private LlmResponse deadlineExceeded(String modelId, boolean limitedByMoveTime, int responseStatus,
                                         long elapsed, CacheRejection cacheRejection, boolean sent) {
        if (cacheRejection != null) {
            LOG.warnf("Plain retry for model %s exhausted the deadline after a cache-control rejection (HTTP %d)",
                    modelId, cacheRejection.status());
            String detail = describeHttpFailure(cacheRejection.status(), cacheRejection.body())
                    + "; plain retry did not finish within the original request deadline after cache-control rejection";
            // A rejection the gate has recorded already carries its own usage; never count it twice.
            LlmResponse failure = cacheRejection.recorded()
                    ? LlmResponse.providerFailure(ProviderOutcome.RETRYABLE, cacheRejection.status(), detail, elapsed, null)
                    : withReportedUsage(LlmResponse.providerFailure(ProviderOutcome.RETRYABLE, cacheRejection.status(),
                            detail, elapsed, cacheRejection.body()), cacheRejection.body());
            return sent ? failure.withDeliveryUncertain() : failure;
        }
        if (responseStatus != 0 && responseStatus != 200) {
            // An incomplete error response does not show that nothing was charged.
            LOG.warnf("OpenRouter answered HTTP %d for model %s, but the error body did not arrive in time",
                    responseStatus, modelId);
            return LlmResponse.providerFailure(ProviderOutcome.RETRYABLE, responseStatus,
                    describeHttpFailure(responseStatus, null) + "; the error response was incomplete", elapsed, null)
                    .withDeliveryUncertain();
        }
        if (responseStatus == 200 && sent) {
            // The answer had started: it may be generated and billed even though it did not arrive in time.
            LOG.warnf("OpenRouter started the answer for model %s, but it did not complete in time", modelId);
            return LlmResponse.providerFailure(ProviderOutcome.RETRYABLE, 200,
                    "OpenRouter started the answer, but it did not complete in time", elapsed, null)
                    .withDeliveryUncertain();
        }
        if (limitedByMoveTime) {
            LOG.warnf("Model %s did not finish its answer within the move time (%d ms)", modelId, elapsed);
            return new LlmResponse(null, 0, 0, null, elapsed, null, true, "No complete answer within the move time");
        }
        LOG.warnf("OpenRouter request for model %s exceeded the safety cap of %s", modelId, describe(requestSafetyCap));
        LlmResponse failure = LlmResponse.providerFailure(ProviderOutcome.RETRYABLE, 0,
                "OpenRouter request did not finish within the safety limit of " + describe(requestSafetyCap),
                elapsed, null);
        return sent ? failure.withDeliveryUncertain() : failure;
    }

    /** Failures that happen before a request can reach OpenRouter: connecting, name resolution, TLS handshake. */
    private static boolean notDelivered(Throwable cause) {
        for (Throwable t = cause; t != null; t = t.getCause()) {
            if (t instanceof ConnectException || t instanceof HttpConnectTimeoutException
                    || t instanceof UnresolvedAddressException || t instanceof SSLHandshakeException) {
                return true;
            }
        }
        return false;
    }

    /** Reports when the whole body was handed to the connection; until then OpenRouter cannot have the request. */
    private static HttpRequest.BodyPublisher tracked(HttpRequest.BodyPublisher body, AtomicBoolean handedOver) {
        return new HttpRequest.BodyPublisher() {
            @Override
            public long contentLength() {
                return body.contentLength();
            }

            @Override
            public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
                body.subscribe(new Flow.Subscriber<>() {
                    @Override
                    public void onSubscribe(Flow.Subscription subscription) {
                        subscriber.onSubscribe(subscription);
                    }

                    @Override
                    public void onNext(ByteBuffer item) {
                        subscriber.onNext(item);
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        subscriber.onError(throwable);
                    }

                    @Override
                    public void onComplete() {
                        handedOver.set(true);
                        subscriber.onComplete();
                    }
                });
            }
        };
    }

    /**
     * Attribution carried by the single plain retry; it shares the marked request's original deadlines.
     * {@code recorded}: the send gate has already recorded the rejection.
     */
    private record CacheRejection(int status, String body, boolean recorded) { }

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

    /**
     * The reasoning effort to send to {@code modelId} for the {@code requested} level, decided from the cached model
     * catalog (see {@link #decideReasoningEffort}); null sends nothing. A level changed on the way is logged.
     */
    public ReasoningEffort resolveReasoningEffort(String modelId, ReasoningEffort requested) {
        if (requested == null) {
            return null;
        }
        JsonNode model = catalogEntry(modelId);
        EffortDecision decision = decideReasoningEffort(requested, model);
        if (decision.effort() != requested) {
            LOG.infof("Reasoning effort %s for %s: %s", requested.apiValue(), modelId, decision.reason());
        } else if (model == null) {
            LOG.debugf("Reasoning effort %s for %s: %s", requested.apiValue(), modelId, decision.reason());
        }
        return decision.effort();
    }

    /** Whether the cached catalog permits effort selection; unknown models may support it. */
    boolean offersReasoningEffort(String modelId) {
        return decideReasoningEffort(ReasoningEffort.LOW, cachedCatalogEntry(modelId)).effort() != null;
    }

    /** Uses the cached catalog only: preparing a move must not wait on a catalog refresh. */
    private JsonNode cachedCatalogEntry(String modelId) {
        JsonNode snapshot = modelsSnapshot.data();
        if (snapshot == null || snapshot.hasNonNull("error")) {
            return null;
        }
        for (JsonNode model : snapshot.path("data")) {
            if (modelId.equals(model.path("id").asText(null))) {
                return model;
            }
        }
        return null;
    }

    /** The model's raw catalog entry; null when the catalog is unavailable or does not list the model. */
    private JsonNode catalogEntry(String modelId) {
        if (modelId == null || objectMapper == null) {
            return null;
        }
        try {
            JsonNode rawModels = fetchRawModels();
            JsonNode data = rawModels.get("data");
            if (data == null || !data.isArray() || rawModels.hasNonNull("error")) {
                return null;
            }
            for (JsonNode node : data) {
                if (modelId.equals(node.path("id").asText(null))) {
                    return node;
                }
            }
        } catch (RuntimeException e) {
            LOG.debugf(e, "Could not look up model %s in the OpenRouter catalog", modelId);
        }
        return null;
    }

    /** The level to send (null: send nothing) and why, for the log. */
    record EffortDecision(ReasoningEffort effort, String reason) {}

    /**
     * Decides the reasoning effort to send for {@code requested} from a model's catalog entry ({@code model}; null
     * when the catalog is unavailable or does not list the model):
     * <ul>
     *   <li>no catalog entry: the level as is (OpenRouter decides);</li>
     *   <li>a model without reasoning, or one whose entry has no {@code reasoning.supported_efforts} (OpenRouter: it
     *       does not expose effort selection): nothing;</li>
     *   <li>{@code supported_efforts: null} (all levels accepted): the level as is;</li>
     *   <li>a list: the level if listed, otherwise the nearest listed one (ties go to the lower level).</li>
     * </ul>
     * A model with mandatory reasoning never gets {@code none}, which it would reject.
     */
    static EffortDecision decideReasoningEffort(ReasoningEffort requested, JsonNode model) {
        if (requested == null) {
            return new EffortDecision(null, "model default");
        }
        if (model == null) {
            return new EffortDecision(requested, "model catalog unavailable or model not listed; sent as is");
        }
        if (!supportsReasoning(model)) {
            return new EffortDecision(null, "the model does not support reasoning; nothing sent");
        }
        JsonNode reasoning = model.path("reasoning");
        boolean mandatory = reasoning.path("mandatory").asBoolean(false);
        if (!reasoning.has("supported_efforts")) {
            return new EffortDecision(null, "the model does not offer reasoning effort levels; nothing sent");
        }
        Set<ReasoningEffort> supported = EnumSet.noneOf(ReasoningEffort.class);
        JsonNode efforts = reasoning.get("supported_efforts");
        if (efforts.isNull()) {
            supported.addAll(EnumSet.allOf(ReasoningEffort.class));
        } else {
            for (String value : parseStringList(efforts)) {
                ReasoningEffort effort = ReasoningEffort.fromApiValue(value);
                if (effort != null) {
                    supported.add(effort);
                }
            }
        }
        if (mandatory) {
            supported.remove(ReasoningEffort.NONE);
        }
        ReasoningEffort nearest = ReasoningEffort.nearest(requested, supported);
        if (nearest == null) {
            return new EffortDecision(null, "the model lists no known reasoning effort level; nothing sent");
        }
        if (nearest != requested) {
            return new EffortDecision(nearest, "not supported by the model (supported: %s); sent %s instead"
                    .formatted(supported.stream().map(ReasoningEffort::apiValue).toList(), nearest.apiValue()));
        }
        return new EffortDecision(requested, "supported by the model");
    }

    private String effectiveKey() {
        return keyStore == null ? "" : keyStore.key();
    }

    private JsonNode fetchRawModels() {
        ObjectNode fallback = objectMapper.createObjectNode();
        fallback.putArray("data");

        long now = modelsClock.getAsLong();
        ModelsSnapshot snapshot = modelsSnapshot;
        modelsSnapshotObserver.run();
        if (!snapshot.needsRefresh(now)) {
            fallback.put("error", "OpenRouter models refresh is backing off");
            return snapshot.data() != null ? snapshot.data() : fallback;
        }

        synchronized (this) {
            now = modelsClock.getAsLong();
            snapshot = modelsSnapshot;
            if (!snapshot.needsRefresh(now)) {
                fallback.put("error", "OpenRouter models refresh is backing off");
                return snapshot.data() != null ? snapshot.data() : fallback;
            }

            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/models"))
                        .GET()
                        .timeout(Duration.ofSeconds(30))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    LOG.errorf("OpenRouter models API error %d: %s",
                            response.statusCode(), truncateForLog(response.body()));
                    fallback.put("error", "OpenRouter models API returned status " + response.statusCode());
                    modelsSnapshot = snapshot.failedAt(modelsClock.getAsLong());
                    return snapshot.data() != null ? snapshot.data() : fallback;
                }

                JsonNode parsed = objectMapper.readTree(response.body());
                if (parsed == null || !parsed.has("data") || !parsed.get("data").isArray()) {
                    LOG.errorf("Unexpected OpenRouter models response shape: %s", truncateForLog(response.body()));
                    fallback.put("error", "Unexpected OpenRouter models response");
                    modelsSnapshot = snapshot.failedAt(modelsClock.getAsLong());
                    return snapshot.data() != null ? snapshot.data() : fallback;
                }

                modelsSnapshot = new ModelsSnapshot(parsed, modelsClock.getAsLong(), 0);
                return parsed;
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                LOG.error("Failed to fetch OpenRouter models", e);
                fallback.put("error", "Failed to fetch OpenRouter models");
                modelsSnapshot = snapshot.failedAt(modelsClock.getAsLong());
                return snapshot.data() != null ? snapshot.data() : fallback;
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
            boolean featured = featuredProviderSet.contains(provider) || containsAny(idLower, featuredKeywords);
            OpenRouterModelOptionDto option;
            try {
                option = toOption(node, id, provider, featured);
            } catch (RuntimeException e) {
                // One malformed entry must not break listing or validation of the whole catalog.
                LOG.debugf(e, "Ignoring unreadable details of OpenRouter model '%s'", id);
                option = new OpenRouterModelOptionDto(id, buildDisplayName(id, null), provider, null, null, null,
                        featured, null, null, null, false, null, List.of(), null, null, null);
            }
            String searchText = (option.name() + " " + id + " " + provider).toLowerCase(Locale.ROOT);
            result.add(new ModelCandidate(option, searchText));
            seenIds.add(id);
        }

        return result;
    }

    private OpenRouterModelOptionDto toOption(JsonNode node, String id, String provider, boolean featured) {
        JsonNode pricing = node.path("pricing");
        JsonNode reasoning = node.path("reasoning");
        JsonNode mandatory = reasoning.path("mandatory");
        return new OpenRouterModelOptionDto(
                id,
                buildDisplayName(id, node.path("name").asText(null)),
                provider,
                extractContextLength(node),
                parsePricePerMillion(pricing.path("prompt")),
                parsePricePerMillion(pricing.path("completion")),
                featured,
                parseReleaseDate(node.path("created")),
                parsePricePerMillion(pricing.path("input_cache_read")),
                positiveInt(node.path("top_provider").path("max_completion_tokens")),
                supportsReasoning(node),
                mandatory.isBoolean() ? mandatory.booleanValue() : null,
                catalogReasoningEfforts(reasoning),
                finiteNumber(node.path("benchmarks").path("artificial_analysis").path("intelligence_index")),
                trimDescription(node.path("description").asText(null)),
                textOrNull(reasoning.path("default_effort"))
        );
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

    /**
     * The provider part of a model id. A leading "~" marks OpenRouter's moving "latest" aliases (such as
     * {@code ~anthropic/claude-sonnet-latest}); they belong to the provider without the "~".
     */
    static String extractProvider(String modelId) {
        String[] parts = modelId.split("/");
        String provider = parts.length == 0 ? "" : parts[0].trim();
        if (provider.startsWith(LATEST_ALIAS_PREFIX)) {
            provider = provider.substring(LATEST_ALIAS_PREFIX.length()).trim();
        }
        return provider.isEmpty() ? "other" : provider.toLowerCase(Locale.ROOT);
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

    /**
     * UTC calendar date (YYYY-MM-DD) of a unix timestamp in seconds; null when missing, not a whole number, not
     * positive or after the year 9999.
     */
    static String parseReleaseDate(JsonNode createdNode) {
        if (createdNode == null || !createdNode.isNumber() || !createdNode.canConvertToLong()) {
            return null;
        }
        long epochSecond = createdNode.longValue();
        if (epochSecond <= 0 || epochSecond > MAX_RELEASE_EPOCH_SECOND) {
            return null;
        }
        try {
            return LocalDate.ofInstant(Instant.ofEpochSecond(epochSecond), ZoneOffset.UTC).toString();
        } catch (DateTimeException | ArithmeticException e) {
            return null;
        }
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

    private static String textOrNull(JsonNode node) {
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            return null;
        }
        return node.asText().trim();
    }

    /** Explicit null accepts every level; a missing field does not expose effort selection. */
    private static List<String> catalogReasoningEfforts(JsonNode reasoning) {
        JsonNode efforts = reasoning.path("supported_efforts");
        List<String> levels = efforts.isNull()
                ? EnumSet.allOf(ReasoningEffort.class).stream().map(ReasoningEffort::apiValue).toList()
                : parseStringList(efforts);
        return reasoning.path("mandatory").asBoolean(false)
                ? levels.stream().filter(level -> !"none".equals(level)).toList()
                : levels;
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

    /**
     * Conservative catalog gate: only Anthropic/Google entries advertising a valid cache-write price qualify.
     * A read price alone can describe implicit caching. Missing catalog evidence always means plain messages.
     */
    private boolean needsCacheBreakpoint(String modelId) {
        if (modelId == null || explicitCachingRejected.contains(modelId)) {
            return false;
        }
        String provider = extractProvider(modelId);
        JsonNode model = cachedCatalogEntry(modelId);
        return ("anthropic".equals(provider) || "google".equals(provider)) && model != null
                && nonNegativePrice(model.path("pricing").path("input_cache_write")) != null;
    }

    private static boolean isCacheControlRejection(int status, String body) {
        if ((status != 400 && status != 404) || body == null) {
            return false;
        }
        String normalized = body.toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ');
        return normalized.contains("cache control");
    }

    /** One moving breakpoint, before the current turn including all of its retries. Never stored in history. */
    private static int stablePrefixEnd(List<ChatMsg> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).startsTurn()) {
                return i - 1;
            }
        }
        // Simple callers without turn metadata have no retry suffix: their last user message is the current turn.
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).role())) {
                return i - 1;
            }
        }
        return !messages.isEmpty() && "system".equals(messages.getFirst().role()) ? 0 : -1;
    }

    /** Immutable text, replayed verbatim. startsTurn is internal boundary metadata, never sent in the JSON. */
    public record ChatMsg(String role, String content, boolean startsTurn) {
        public ChatMsg(String role, String content) {
            this(role, content, false);
        }
    }

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
            Integer reasoningTokens,
            Integer effectiveMaxOutputTokens,
            Integer modelMaxCompletionTokens,
            Integer cachedPromptTokens,
            Integer cacheWriteTokens,
            boolean promptTokensReported,
            // A plain fallback can return a model answer after a marker rejection used part of the turn deadline.
            Integer cacheRejectionStatus,
            // The request was sent but no usable answer arrived (connection lost, read timeout, broken body): OpenRouter
            // may have charged it, so it must never be retried automatically.
            boolean deliveryUncertain
    ) {
        public LlmResponse(String content, int promptTokens, int completionTokens, Double costUsd,
                           long responseTimeMs, String rawResponse, boolean timedOut, String errorMessage,
                           ProviderOutcome outcome, int httpStatus, String finishReason, Integer reasoningTokens,
                           Integer effectiveMaxOutputTokens, Integer modelMaxCompletionTokens) {
            this(content, promptTokens, completionTokens, costUsd, responseTimeMs, rawResponse, timedOut,
                    errorMessage, outcome, httpStatus, finishReason, reasoningTokens, effectiveMaxOutputTokens,
                    modelMaxCompletionTokens, null, null, promptTokens > 0, null, false);
        }

        LlmResponse withUsage(JsonNode usage) {
            Double cost = finiteNumber(usage.path("cost"));
            return new LlmResponse(content, usage.path("prompt_tokens").asInt(0),
                    usage.path("completion_tokens").asInt(0), cost, responseTimeMs, rawResponse, timedOut,
                    errorMessage, outcome, httpStatus, finishReason,
                    tokenCount(usage.path("completion_tokens_details").path("reasoning_tokens")),
                    effectiveMaxOutputTokens, modelMaxCompletionTokens,
                    tokenCount(usage.path("prompt_tokens_details").path("cached_tokens")),
                    tokenCount(usage.path("prompt_tokens_details").path("cache_write_tokens")),
                    tokenCount(usage.path("prompt_tokens")) != null, cacheRejectionStatus, deliveryUncertain);
        }

        private static Integer tokenCount(JsonNode node) {
            return node.isIntegralNumber() && node.canConvertToInt() && node.intValue() >= 0 ? node.intValue() : null;
        }

        public LlmResponse(String content, int promptTokens, int completionTokens, Double costUsd,
                           long responseTimeMs, String rawResponse, boolean timedOut, String errorMessage,
                           ProviderOutcome outcome, int httpStatus, String finishReason, Integer reasoningTokens) {
            this(content, promptTokens, completionTokens, costUsd, responseTimeMs, rawResponse, timedOut,
                    errorMessage, outcome, httpStatus, finishReason, reasoningTokens, null, null);
        }

        LlmResponse withOutputTokenLimits(int effective, Integer maximum) {
            return new LlmResponse(content, promptTokens, completionTokens, costUsd, responseTimeMs, rawResponse,
                    timedOut, errorMessage, outcome, httpStatus, finishReason, reasoningTokens, effective, maximum,
                    cachedPromptTokens, cacheWriteTokens, promptTokensReported, cacheRejectionStatus, deliveryUncertain);
        }

        private LlmResponse withCacheRejectionStatus(int status) {
            return new LlmResponse(content, promptTokens, completionTokens, costUsd, responseTimeMs, rawResponse,
                    timedOut, errorMessage, outcome, httpStatus, finishReason, reasoningTokens, effectiveMaxOutputTokens,
                    modelMaxCompletionTokens, cachedPromptTokens, cacheWriteTokens, promptTokensReported, status,
                    deliveryUncertain);
        }

        LlmResponse withDeliveryUncertain() {
            return new LlmResponse(content, promptTokens, completionTokens, costUsd, responseTimeMs, rawResponse,
                    timedOut, errorMessage, outcome, httpStatus, finishReason, reasoningTokens, effectiveMaxOutputTokens,
                    modelMaxCompletionTokens, cachedPromptTokens, cacheWriteTokens, promptTokensReported,
                    cacheRejectionStatus, true);
        }

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

    /** Catalog data and its freshness state are published together; data is never mutated after publication. */
    private record ModelsSnapshot(JsonNode data, long fetchedAtMs, long retryAfterMs) {
        boolean needsRefresh(long now) {
            return (data == null || now - fetchedAtMs >= MODELS_CACHE_TTL_MS) && now >= retryAfterMs;
        }

        ModelsSnapshot failedAt(long now) {
            return new ModelsSnapshot(data, fetchedAtMs, now + MODELS_FAILURE_BACKOFF_MS);
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
