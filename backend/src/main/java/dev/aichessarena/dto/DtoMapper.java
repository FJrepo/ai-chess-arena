package dev.aichessarena.dto;

import dev.aichessarena.entity.*;
import dev.aichessarena.service.MoveQualityClassifier;
import dev.aichessarena.service.MoveQualityClassifier.GameSummary;
import dev.aichessarena.service.MoveQualityClassifier.MoveQuality;
import dev.aichessarena.service.MoveQualityClassifier.SideSummary;

import java.util.ArrayList;
import java.util.List;

public final class DtoMapper {

    private DtoMapper() {}

    public static TournamentDto toDto(Tournament t) {
        ChatMode chatMode = t.chatMode != null ? t.chatMode : ChatMode.OPTIONAL;
        return new TournamentDto(
                t.id, t.name, t.status.name(), t.format.name(),
                t.drawPolicy.name(),
                t.sharedCustomInstructions, t.moveTimeoutSeconds,
                t.maxRetries, t.matchupBestOf, t.finalsBestOf, chatMode != ChatMode.OFF, chatMode.name(),
                t.maxOutputTokens,
                t.createdAt, t.updatedAt,
                t.participants.stream().map(DtoMapper::toDto).toList(),
                t.games.stream().map(g -> toDto(g, false)).toList(),
                t.status == Tournament.TournamentStatus.COMPLETED
                        ? t.games.stream()
                                .filter(g -> "Final".equals(g.bracketRound) && g.advancedParticipant != null)
                                .map(g -> g.advancedParticipant.playerName)
                                .findFirst()
                                .orElse(null)
                        : null
        );
    }

    public static ParticipantDto toDto(TournamentParticipant p) {
        return new ParticipantDto(
                p.id,
                p.playerName,
                p.controlType.name(),
                p.modelId,
                p.customInstructions,
                p.seed
        );
    }

    /** With {@code includeMoves}, the moves carry their quality and the game its per-side quality summary. */
    public static GameDto toDto(Game g, boolean includeMoves) {
        List<MoveDto> moves = List.of();
        GameQualitySummaryDto qualitySummary = null;
        if (includeMoves) {
            List<Move> played = g.moves;
            List<MoveQuality> qualities = MoveQualityClassifier.analyze(played);
            List<MoveDto> mapped = new ArrayList<>(played.size());
            for (int i = 0; i < played.size(); i++) {
                mapped.add(toDto(played.get(i), qualities.get(i)));
            }
            moves = mapped;
            qualitySummary = toDto(MoveQualityClassifier.summarize(played, qualities));
        }
        return new GameDto(
                g.id,
                g.tournament != null ? g.tournament.id : null,
                g.whitePlayerName,
                g.whiteParticipant != null ? g.whiteParticipant.controlType.name() : "AI",
                g.whiteModelId,
                g.whiteParticipant != null ? g.whiteParticipant.id : null,
                g.blackPlayerName,
                g.blackParticipant != null ? g.blackParticipant.controlType.name() : "AI",
                g.blackModelId,
                g.blackParticipant != null ? g.blackParticipant.id : null,
                g.status.name(),
                g.result != null ? g.result.name() : null,
                g.resultReason != null ? g.resultReason.name() : null,
                g.statusDetail,
                g.advancedParticipant != null ? g.advancedParticipant.playerName : null,
                g.advancedParticipant != null ? g.advancedParticipant.id : null,
                g.pgn, g.currentFen,
                g.bracketRound, g.bracketPosition, g.seriesId, g.seriesGameNumber, g.seriesBestOf,
                g.totalCostUsd, g.createdAt, g.startedAt, g.completedAt,
                moves,
                includeMoves ? g.chatMessages.stream().map(DtoMapper::toDto).toList() : List.of(),
                qualitySummary
        );
    }

    public static MoveAttemptDto toDto(MoveAttempt a) {
        return new MoveAttemptDto(
                a.id, a.moveNumber, a.color, a.attemptNumber, a.modelId, a.outcome.name(), a.proposedMove,
                a.promptTokens, a.completionTokens, a.reasoningTokens, a.costUsd, a.responseTimeMs,
                a.finishReason, a.errorMessage, a.createdAt
        );
    }

    public static MoveDto toDto(Move m) {
        return toDto(m, null);
    }

    public static MoveDto toDto(Move m, MoveQuality quality) {
        return new MoveDto(
                m.id, m.moveNumber, m.color, m.san, m.fen,
                m.modelId, m.promptVersion, m.promptHash, m.promptTokens, m.completionTokens,
                m.costUsd, m.responseTimeMs, m.retryCount,
                m.isOverride, m.evaluationCp, m.evaluationMate,
                m.createdAt,
                toDto(quality),
                m.chatMode != null ? m.chatMode.name() : null
        );
    }

    /** Null for a move that is not judged. Winning chances are rounded to four decimals. */
    public static MoveQualityDto toDto(MoveQuality quality) {
        if (quality == null) {
            return null;
        }
        return new MoveQualityDto(
                quality.judgement() != null ? quality.judgement().name() : null,
                quality.centipawnLoss(),
                round4(quality.winChanceBefore()),
                round4(quality.winChanceAfter()),
                quality.excluded() != null ? quality.excluded().name() : null
        );
    }

    public static GameQualitySummaryDto toDto(GameSummary summary) {
        return new GameQualitySummaryDto(toDto(summary.white()), toDto(summary.black()));
    }

    public static SideQualityDto toDto(SideSummary side) {
        return new SideQualityDto(
                side.analyzedMoves(), side.averageCentipawnLoss(), side.inaccuracies(), side.mistakes(), side.blunders()
        );
    }

    private static double round4(double value) {
        return Math.round(value * 10_000d) / 10_000d;
    }

    public static ChatMessageDto toDto(ChatMessage c) {
        return new ChatMessageDto(c.id, c.moveNumber, c.senderModel, c.senderColor, c.message, c.createdAt);
    }
}
