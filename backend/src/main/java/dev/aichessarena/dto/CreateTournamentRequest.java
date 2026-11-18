package dev.aichessarena.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.List;
import java.util.UUID;

/**
 * Create (POST) or update (PUT) a tournament. {@code participants} and {@code clientRequestId} are only
 * used on create: the tournament and all participants are stored together or not at all, and repeating a
 * request with the same {@code clientRequestId} returns the tournament created by the first one.
 * The original creation's bracket choice is retained on retries. {@code drawMode} defaults to ORDER;
 * {@code generateBracket} defaults to true (omitted or null), which requires at least two participants. Send
 * false to create a draft without a bracket. These fields are create-only.
 * Optional {@code runSettings} sets the same limits accepted by the run configuration endpoint without starting it.
 * On create, {@code budgetUsd} also accepts an exact decimal string so clients can preserve stored cents.
 *
 * <p>{@code maxAttempts} is the number of attempts per move, including the first one (1-20).
 *
 * <p>{@code chatMode} is OFF, OPTIONAL or MANDATORY.
 *
 * <p>{@code banterLevel} accepts POLITE, PLAYFUL, SPICY, MIND_GAMES or explicit JSON null (clear to original tone).
 * Omitted retains the current tone on update. Chat OFF and legacy full templates ignore it.
 *
 * <p>{@code resignationAllowed} lets models resign from their 10th move on; null keeps the default (false) on create
 * and the current value on update.
 *
 * <p>{@code adjudicationEnabled} enables decisive-position adjudication for model-vs-model games; null keeps the
 * default (false) on create and the current value on update. A change applies from the next turn.
 *
 * <p>{@code openingMode} is STANDARD (default), RANDOM_BOOK or FIXED. FIXED requires a known {@code openingId}.
 * Null settings retain defaults on create and current settings on update. Only newly created games are affected;
 * regular color-reversed partners reuse the first game's stored opening even after a settings change.
 *
 * <p>{@code reasoningEffort} is OpenRouter's reasoning effort for all models (none, minimal, low, medium, high, xhigh,
 * max; case-insensitive) or {@code "default"} for each model's own default. Null keeps the model default on create
 * and the current value on update; {@code "default"} clears it on update.
 *
 * <p>The fields removed in 2.0 ({@code maxRetries}, {@code trashTalkEnabled}) are bound only so that a request
 * still sending them is rejected with a 400 naming the replacement, instead of being silently ignored.
 */
public record CreateTournamentRequest(
        String name,
        String sharedCustomInstructions,
        Integer moveTimeoutSeconds,
        // Keep the JSON type so fractional or empty input cannot be coerced to an integer or null.
        JsonNode maxAttempts,
        Integer matchupBestOf,
        Integer finalsBestOf,
        String drawPolicy,
        List<AddParticipantRequest> participants,
        UUID clientRequestId,
        Integer maxOutputTokens,
        String chatMode,
        Boolean resignationAllowed,
        String reasoningEffort,
        @JsonDeserialize(using = StrictBooleanDeserializer.class) Boolean adjudicationEnabled,
        String openingMode,
        String openingId,
        String drawMode,
        @JsonDeserialize(using = StrictBooleanDeserializer.class) Boolean generateBracket,
        // Omitted retains the current tone on update; explicit JSON null clears it. Only strings/null are valid.
        JsonNode banterLevel,
        // Omitted defaults to true on create. Explicit null and other non-boolean values are invalid.
        JsonNode legalMoveHints,
        @JsonDeserialize(using = ExactDecimalTreeDeserializer.class) JsonNode runSettings,
        // Removed in 2.0; non-null (also explicit JSON null) when a client still sends the old field.
        @JsonProperty("maxRetries") JsonNode removedMaxRetries,
        @JsonProperty("trashTalkEnabled") JsonNode removedTrashTalkEnabled
) {
    /** Fields removed in 2.0 with the message that names their replacement. */
    public void rejectRemovedFields() {
        if (removedMaxRetries != null) {
            throw new IllegalArgumentException(
                    "maxRetries was removed in 2.0; send maxAttempts (attempts per move including the first, 1-20)");
        }
        if (removedTrashTalkEnabled != null) {
            throw new IllegalArgumentException(
                    "trashTalkEnabled was removed in 2.0; send chatMode (OFF, OPTIONAL or MANDATORY)");
        }
    }
}
