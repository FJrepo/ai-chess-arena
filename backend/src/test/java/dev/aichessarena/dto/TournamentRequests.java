package dev.aichessarena.dto;

import com.fasterxml.jackson.databind.JsonNode;

/** Test shorthand for {@link CreateTournamentRequest}: every field not given is omitted (null). */
public final class TournamentRequests {

    private TournamentRequests() {}

    public static CreateTournamentRequest settings(String name, Integer moveTimeoutSeconds, Integer matchupBestOf,
                                                   Integer maxOutputTokens, String chatMode, String reasoningEffort) {
        return new CreateTournamentRequest(name, null, moveTimeoutSeconds, null, matchupBestOf, null, null, null, null,
                maxOutputTokens, chatMode, null, reasoningEffort, null, null, null, null, null, null, null, null, null,
                null);
    }

    public static CreateTournamentRequest attempts(String name, JsonNode maxAttempts) {
        return new CreateTournamentRequest(name, null, null, maxAttempts, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null);
    }

    /** A request that still sends fields removed in 2.0. */
    public static CreateTournamentRequest removed(String name, JsonNode maxRetries, JsonNode trashTalkEnabled) {
        return new CreateTournamentRequest(name, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, maxRetries, trashTalkEnabled);
    }
}
