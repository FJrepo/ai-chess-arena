package dev.aichessarena.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** All-time ratings, replayed using exactly the selected forfeit policy. */
public record LeaderboardDto(boolean includeForfeits, long gamesCount, List<Model> models) {

    public record Model(
            String modelId,
            String displayName,
            int rating,
            long games,
            long wins,
            long draws,
            long losses,
            long forfeits,
            Instant lastPlayed,
            boolean provisional,
            boolean alias,
            List<HistoryPoint> ratingHistory,
            String reasoningEffort
    ) {}

    public record HistoryPoint(UUID gameId, Instant completedAt, int rating) {}
}
