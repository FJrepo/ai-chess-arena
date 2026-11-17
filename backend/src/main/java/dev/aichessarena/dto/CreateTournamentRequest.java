package dev.aichessarena.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.List;
import java.util.UUID;

/**
 * Create (POST) or update (PUT) a tournament. {@code participants} and {@code clientRequestId} are only
 * used on create: the tournament and all participants are stored together or not at all, and repeating a
 * request with the same {@code clientRequestId} returns the tournament created by the first one.
 * The original creation's bracket choice is retained on retries. {@code drawMode} defaults to ORDER;
 * {@code generateBracket} generates only when explicitly true, which requires at least two participants.
 * Omitted, null or false preserves draft creation for API clients. These fields are create-only.
 *
 * <p>{@code chatMode} is OFF, OPTIONAL or MANDATORY. The older {@code trashTalkEnabled} is still accepted: alone,
 * false means OFF and true means OPTIONAL; when both are sent, {@code chatMode} wins.
 *
 * <p>{@code banterLevel} accepts POLITE, PLAYFUL, SPICY or explicit JSON null (clear to original tone). Omitted
 * retains the current tone on update; chat OFF and legacy full templates ignore it.
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
 */
public record CreateTournamentRequest(
        String name,
        String sharedCustomInstructions,
        Integer moveTimeoutSeconds,
        Integer maxRetries,
        Integer matchupBestOf,
        Integer finalsBestOf,
        Boolean trashTalkEnabled,
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
        JsonNode banterLevel
) {
    /** Compatibility constructor for callers without creation-time bracket or banter settings. */
    public CreateTournamentRequest(String name, String sharedCustomInstructions, Integer moveTimeoutSeconds,
                                   Integer maxRetries, Integer matchupBestOf, Integer finalsBestOf,
                                   Boolean trashTalkEnabled, String drawPolicy,
                                   List<AddParticipantRequest> participants, UUID clientRequestId,
                                   Integer maxOutputTokens, String chatMode, Boolean resignationAllowed,
                                   String reasoningEffort, Boolean adjudicationEnabled,
                                   String openingMode, String openingId) {
        this(name, sharedCustomInstructions, moveTimeoutSeconds, maxRetries, matchupBestOf, finalsBestOf,
                trashTalkEnabled, drawPolicy, participants, clientRequestId, maxOutputTokens, chatMode,
                resignationAllowed, reasoningEffort, adjudicationEnabled, openingMode, openingId, null, null, null);
    }

    public CreateTournamentRequest(String name, String sharedCustomInstructions, Integer moveTimeoutSeconds,
                                   Integer maxRetries, Integer matchupBestOf, Integer finalsBestOf,
                                   Boolean trashTalkEnabled, String drawPolicy,
                                   List<AddParticipantRequest> participants, UUID clientRequestId,
                                   Integer maxOutputTokens, String chatMode, Boolean resignationAllowed,
                                   String reasoningEffort, Boolean adjudicationEnabled) {
        this(name, sharedCustomInstructions, moveTimeoutSeconds, maxRetries, matchupBestOf, finalsBestOf,
                trashTalkEnabled, drawPolicy, participants, clientRequestId, maxOutputTokens, chatMode,
                resignationAllowed, reasoningEffort, adjudicationEnabled, null, null);
    }

    /** Compatibility constructor for callers without adjudication settings. */
    public CreateTournamentRequest(String name, String sharedCustomInstructions, Integer moveTimeoutSeconds,
                                   Integer maxRetries, Integer matchupBestOf, Integer finalsBestOf,
                                   Boolean trashTalkEnabled, String drawPolicy,
                                   List<AddParticipantRequest> participants, UUID clientRequestId,
                                   Integer maxOutputTokens, String chatMode, Boolean resignationAllowed,
                                   String reasoningEffort) {
        this(name, sharedCustomInstructions, moveTimeoutSeconds, maxRetries, matchupBestOf, finalsBestOf,
                trashTalkEnabled, drawPolicy, participants, clientRequestId, maxOutputTokens, chatMode,
                resignationAllowed, reasoningEffort, null);
    }

    /**
     * A request without {@code resignationAllowed} and {@code reasoningEffort}: create keeps the defaults, update the
     * current values.
     */
    public CreateTournamentRequest(String name, String sharedCustomInstructions, Integer moveTimeoutSeconds,
                                   Integer maxRetries, Integer matchupBestOf, Integer finalsBestOf,
                                   Boolean trashTalkEnabled, String drawPolicy,
                                   List<AddParticipantRequest> participants, UUID clientRequestId,
                                   Integer maxOutputTokens, String chatMode) {
        this(name, sharedCustomInstructions, moveTimeoutSeconds, maxRetries, matchupBestOf, finalsBestOf,
                trashTalkEnabled, drawPolicy, participants, clientRequestId, maxOutputTokens, chatMode, null, null, null);
    }
}
