package dev.aichessarena.service;

import dev.aichessarena.dto.ReasoningAnalyticsDto;
import dev.aichessarena.entity.Move;
import dev.aichessarena.entity.MoveAttempt.Outcome;
import dev.aichessarena.entity.ReasoningEffort;
import dev.aichessarena.repository.MoveAttemptRepository.ReasoningAttempt;
import dev.aichessarena.repository.MoveRepository.ReasoningPly;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class ReasoningAnalytics {

    private static final String DEFAULT = "default";
    private static final String UNKNOWN = "unknown";
    private static final String MIXED = "mixed";

    private ReasoningAnalytics() {}

    static String validateFilter(String filter) {
        if (filter == null || DEFAULT.equals(filter) || UNKNOWN.equals(filter) || MIXED.equals(filter)) {
            return filter;
        }
        return ReasoningEffort.parse(filter).apiValue();
    }

    static ReasoningPly modelPly(Move move) {
        return new ReasoningPly(move.game.id, move.color, move.modelId, move.moveNumber, move.retryCount,
                move.isBook, move.isOverride, move.createdAt, move.id);
    }

    static Map<Side, Sample> bySide(List<ReasoningAttempt> attempts, List<ReasoningPly> canonicalMoves) {
        Map<Side, Sample> samples = new HashMap<>();
        Map<Ply, ReasoningAttempt> acceptedByPly = new HashMap<>();
        for (ReasoningAttempt attempt : attempts) {
            Side side = Side.of(attempt.gameId(), attempt.color(), attempt.modelId());
            Sample sample = samples.computeIfAbsent(side, ignored -> new Sample());
            // Without response evidence we cannot tell whether a provider/deadline failure ever sent its request.
            // A different possible effort prevents claiming a uniform known level, but cannot prove Mixed.
            String recorded = attempt.effort() != null ? attempt.effort().apiValue()
                    : attempt.requestTokenLimit() != null ? DEFAULT : null;
            if (!attempt.responseEvidence()) {
                if (recorded != null) sample.possibleEfforts.add(recorded);
                continue;
            }
            if (recorded == null) {
                sample.incomplete = true;
            } else {
                sample.efforts.add(recorded);
            }
            if (attempt.outcome() == Outcome.ACCEPTED) {
                acceptedByPly.putIfAbsent(new Ply(side, attempt.moveNumber(), attempt.attemptNumber()), attempt);
            }
        }

        for (ReasoningPly move : canonicalMoves) {
            if (move.book() || move.override() || move.modelId() == null || move.modelId().isBlank()) {
                continue;
            }
            Side side = Side.of(move.gameId(), move.color(), move.modelId());
            ReasoningAttempt accepted = acceptedByPly.get(new Ply(side, move.moveNumber(), move.retryCount() + 1));
            Sample sample = samples.computeIfAbsent(side, ignored -> new Sample());
            sample.modelMoves++;
            if (accepted == null) {
                sample.incomplete = true;
            } else if (accepted.reasoningTokens() != null && accepted.reasoningTokens() >= 0) {
                sample.reportedMoves++;
                sample.tokens += accepted.reasoningTokens();
            }
        }
        return samples;
    }

    static String effortOf(Sample sample) {
        return sample == null ? UNKNOWN : sample.effort();
    }

    record Side(UUID gameId, String color, String modelId) {
        static Side of(UUID gameId, String color, String modelId) {
            return new Side(gameId, color, modelId == null ? null : modelId.trim());
        }
    }

    private record Ply(Side side, int moveNumber, int attemptNumber) {}

    static final class Sample {
        private final Set<String> efforts = new HashSet<>();
        private final Set<String> possibleEfforts = new HashSet<>();
        private boolean incomplete;
        private long modelMoves;
        private long reportedMoves;
        private long tokens;

        void include(Sample other) {
            if (other == null) {
                incomplete = true;
                return;
            }
            efforts.addAll(other.efforts);
            incomplete |= UNKNOWN.equals(other.effort());
            modelMoves += other.modelMoves;
            reportedMoves += other.reportedMoves;
            tokens += other.tokens;
        }

        String effort() {
            if (efforts.size() > 1) {
                return MIXED;
            }
            return incomplete || efforts.isEmpty() || !efforts.containsAll(possibleEfforts)
                    ? UNKNOWN : efforts.iterator().next();
        }

        ReasoningAnalyticsDto toDto(String selectedEffort) {
            BigDecimal average = reportedMoves == 0
                    ? null : BigDecimal.valueOf(tokens).divide(BigDecimal.valueOf(reportedMoves), 1, RoundingMode.HALF_UP);
            return new ReasoningAnalyticsDto(selectedEffort == null ? effort() : selectedEffort,
                    average, reportedMoves, modelMoves);
        }
    }
}
