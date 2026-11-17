package dev.aichessarena.dto;

import java.time.LocalDateTime;
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
            LocalDateTime lastPlayed,
            boolean provisional,
            boolean alias,
            List<HistoryPoint> ratingHistory
    ) {}

    public record HistoryPoint(UUID gameId, LocalDateTime completedAt, int rating) {}
}
