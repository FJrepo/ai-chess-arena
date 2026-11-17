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
                move.isBook, move.isOverride);
    }

    static Map<Side, Sample> bySide(List<ReasoningAttempt> attempts, List<ReasoningPly> canonicalMoves) {
        Map<Side, Sample> samples = new HashMap<>();
        Map<Ply, ReasoningAttempt> acceptedByPly = new HashMap<>();
        for (ReasoningAttempt attempt : attempts) {
            Side side = Side.of(attempt.gameId(), attempt.color(), attempt.modelId());
            Sample sample = samples.computeIfAbsent(side, ignored -> new Sample());
            // An explicit level is recorded on the request attempt. Null only proves no explicit level if
            // the row also has request metadata, introduced after effort tracking.
            if (!attempt.responseEvidence()) {
                continue;
            } else if (attempt.effort() != null) {
                sample.efforts.add(attempt.effort().apiValue());
            } else if (attempt.requestTokenLimit() != null) {
                sample.efforts.add(DEFAULT);
            } else {
                sample.incomplete = true;
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
            incomplete |= other.incomplete || other.efforts.isEmpty();
            modelMoves += other.modelMoves;
            reportedMoves += other.reportedMoves;
            tokens += other.tokens;
        }

        String effort() {
            if (efforts.size() > 1) {
                return MIXED;
            }
            return incomplete || efforts.isEmpty() ? UNKNOWN : efforts.iterator().next();
        }

        ReasoningAnalyticsDto toDto() {
            BigDecimal average = reportedMoves == 0
                    ? null : BigDecimal.valueOf(tokens).divide(BigDecimal.valueOf(reportedMoves), 1, RoundingMode.HALF_UP);
            return new ReasoningAnalyticsDto(effort(), average, reportedMoves, modelMoves);
        }
    }
}
