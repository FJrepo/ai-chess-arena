package dev.aichessarena.dto;

import java.util.List;
import java.util.UUID;

/**
 * Create (POST) or update (PUT) a tournament. {@code participants} and {@code clientRequestId} are only
 * used on create: the tournament and all participants are stored together or not at all, and repeating a
 * request with the same {@code clientRequestId} returns the tournament created by the first one.
 *
 * <p>{@code chatMode} is OFF, OPTIONAL or MANDATORY. The older {@code trashTalkEnabled} is still accepted: alone,
 * false means OFF and true means OPTIONAL; when both are sent, {@code chatMode} wins.
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
        String chatMode
) {}
