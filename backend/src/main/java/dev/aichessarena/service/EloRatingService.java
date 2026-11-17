package dev.aichessarena.service;

import dev.aichessarena.dto.LeaderboardDto;
import dev.aichessarena.dto.LeaderboardDto.HistoryPoint;
import dev.aichessarena.entity.Game.GameResult;
import dev.aichessarena.service.ReasoningAnalytics.Side;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Pure replay: no database, catalog, clock, shared mutable state or intermediate rounding. */
@ApplicationScoped
public class EloRatingService {

    public record RatedGame(UUID id, LocalDateTime completedAt, String whiteModelId, String blackModelId,
                            GameResult result) {
        boolean isForfeit() {
            return result == GameResult.WHITE_FORFEIT || result == GameResult.BLACK_FORFEIT;
        }
    }

    public LeaderboardDto calculate(List<RatedGame> games, boolean includeForfeits) {
        return calculate(games, includeForfeits, Map.of());
    }

    LeaderboardDto calculate(List<RatedGame> games, boolean includeForfeits,
                             Map<Side, ReasoningAnalytics.Sample> reasoning) {
        Map<String, Rating> ratings = new HashMap<>();
        Set<UUID> seen = new HashSet<>();
        List<RatedGame> ordered = games.stream()
                .filter(game -> includeForfeits || !game.isForfeit())
                .sorted(Comparator.comparing(RatedGame::completedAt)
                        .thenComparing(game -> game.id().toString()))
                .toList();
        for (RatedGame game : ordered) {
            if (!seen.add(game.id())) {
                continue;
            }
            Rating white = ratings.computeIfAbsent(game.whiteModelId(), Rating::new);
            Rating black = ratings.computeIfAbsent(game.blackModelId(), Rating::new);
            double score = switch (game.result()) {
                case WHITE_WINS, BLACK_FORFEIT -> 1.0;
                case BLACK_WINS, WHITE_FORFEIT -> 0.0;
                case DRAW -> 0.5;
            };
            double expectation = expectedScore(white.value, black.value);
            // Capture both K-factors and deltas before changing either rating (including self-play).
            double whiteDelta = kFactor(white.games) * (score - expectation);
            double blackDelta = kFactor(black.games) * (expectation - score);
            white.value += whiteDelta;
            black.value += blackDelta;
            white.recordResult(score, game.result() == GameResult.WHITE_FORFEIT, game.completedAt());
            black.recordResult(1.0 - score, game.result() == GameResult.BLACK_FORFEIT, game.completedAt());
            white.reasoning.include(reasoning.get(Side.of(game.id(), "WHITE", game.whiteModelId())));
            black.reasoning.include(reasoning.get(Side.of(game.id(), "BLACK", game.blackModelId())));
            white.recordHistory(game);
            if (white != black) {
                black.recordHistory(game);
            }
        }
        List<LeaderboardDto.Model> models = ratings.values().stream()
                .sorted(Comparator.comparingDouble((Rating rating) -> rating.value).reversed()
                        .thenComparing(rating -> rating.modelId))
                .map(Rating::toDto)
                .toList();
        return new LeaderboardDto(includeForfeits, seen.size(), models);
    }

    static double expectedScore(double rating, double opponentRating) {
        return 1.0 / (1.0 + Math.pow(10.0, (opponentRating - rating) / 400.0));
    }

    static int kFactor(long previousGames) {
        return previousGames < 30 ? 32 : 16;
    }

    private static final class Rating {
        private final String modelId;
        private final ReasoningAnalytics.Sample reasoning = new ReasoningAnalytics.Sample();
        private double value = 1500;
        private long games;
        private long wins;
        private long draws;
        private long losses;
        private long forfeits;
        private LocalDateTime lastPlayed;
        private final ArrayDeque<HistoryPoint> history = new ArrayDeque<>();

        private Rating(String modelId) {
            this.modelId = modelId;
        }

        private void recordResult(double score, boolean forfeited, LocalDateTime completedAt) {
            games++;
            if (score == 1.0) {
                wins++;
            } else if (score == 0.5) {
                draws++;
            } else {
                losses++;
            }
            if (forfeited) {
                forfeits++;
            }
            lastPlayed = completedAt;
        }

        private void recordHistory(RatedGame game) {
            history.addLast(new HistoryPoint(game.id(), game.completedAt(), (int) Math.round(value)));
            if (history.size() > 50) {
                history.removeFirst();
            }
        }

        private LeaderboardDto.Model toDto() {
            String displayName = modelId.substring(modelId.lastIndexOf('/') + 1);
            return new LeaderboardDto.Model(modelId, displayName, (int) Math.round(value), games, wins, draws,
                    losses, forfeits, lastPlayed, games < 10, modelId.startsWith("~"), List.copyOf(history), reasoning.effort());
        }
    }
}
