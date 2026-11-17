package dev.aichessarena.dto;

import dev.aichessarena.entity.*;
import dev.aichessarena.service.MoveQualityClassifier;
import dev.aichessarena.service.MoveQualityClassifier.GameSummary;
import dev.aichessarena.service.MoveQualityClassifier.MoveQuality;
import dev.aichessarena.service.MoveQualityClassifier.SideSummary;
import dev.aichessarena.service.ResignationAnalysis;
import dev.aichessarena.service.WinAdjudication;

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
                t.banterLevel != null ? t.banterLevel.name() : null,
                t.resignationAllowed, t.legalMoveHints, t.adjudicationEnabled,
                t.maxOutputTokens,
                apiValue(t.reasoningEffort),
                t.createdAt, t.updatedAt,
                t.participants.stream().map(DtoMapper::toDto).toList(),
                t.games.stream().map(g -> toDto(g, false)).toList(),
                t.status == Tournament.TournamentStatus.COMPLETED
                        ? t.games.stream()
                                .filter(g -> "Final".equals(g.bracketRound) && g.advancedParticipant != null)
                                .map(g -> g.advancedParticipant.playerName)
                                .findFirst()
                                .orElse(null)
                        : null,
                t.openingMode.name(), t.openingId,
                t.autoRunStatus.name(), t.maxConcurrentGames, t.budgetUsd, t.autoRunPauseReason,
                t.creationDrawMode != null ? t.creationDrawMode.name() : null,
                t.defaultSystemPrompt != null && !t.defaultSystemPrompt.isBlank(),
                t.budgetUsd == null ? null : t.budgetUsd.toPlainString()
        );
    }

    public static ParticipantDto toDto(TournamentParticipant p) {
        return new ParticipantDto(
                p.id,
                p.playerName,
                p.controlType.name(),
                p.modelId,
                p.customInstructions,
                p.seed,
                apiValue(p.reasoningEffort),
                p.customSystemPrompt != null && !p.customSystemPrompt.isBlank()
        );
    }

    private static String apiValue(ReasoningEffort effort) {
        return effort != null ? effort.apiValue() : null;
    }

    /** With {@code includeMoves}, the moves carry their quality and the game its per-side quality summary. */
    public static GameDto toDto(Game g, boolean includeMoves) {
        return toDto(g, includeMoves, null);
    }

    public static GameDto toDto(Game g, boolean includeMoves, GameDto.RunCapacity runCapacity) {
        return toDto(g, includeMoves, runCapacity, null, null);
    }

    public static GameDto toDto(Game g, boolean includeMoves, GameDto.RunCapacity runCapacity,
                                List<MoveAttemptDto> attempts, String streamId) {
        List<MoveDto> moves = List.of();
        GameQualitySummaryDto qualitySummary = null;
        ResignationDto resignation = null;
        if (includeMoves) {
            List<Move> played = g.moves;
            List<MoveQuality> qualities = MoveQualityClassifier.analyze(played);
            List<MoveDto> mapped = new ArrayList<>(played.size());
            for (int i = 0; i < played.size(); i++) {
                mapped.add(toDto(played.get(i), qualities.get(i)));
            }
            moves = mapped;
            qualitySummary = toDto(MoveQualityClassifier.summarize(played, qualities));
            resignation = resignation(g, played);
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
                g.resumeRequestedAt,
                runCapacity,
                g.advancedParticipant != null ? g.advancedParticipant.playerName : null,
                g.advancedParticipant != null ? g.advancedParticipant.id : null,
                g.pgn, g.currentFen,
                g.bracketRound, g.bracketPosition, g.seriesId, g.seriesGameNumber, g.seriesBestOf,
                g.totalCostUsd, g.createdAt, g.startedAt, g.completedAt,
                moves,
                includeMoves ? g.chatMessages.stream().map(DtoMapper::toDto).toList() : List.of(),
                qualitySummary,
                resignation,
                includeMoves ? adjudication(g, g.moves) : null,
                g.statusRevision,
                apiValue(g.requestedReasoningEffort("WHITE")),
                apiValue(g.requestedReasoningEffort("BLACK")),
                g.openingId, g.openingName, g.openingEco, g.hasLegalMoveHints(), g.liveRevision, g.activeColor(),
                g.turnStartedAt, g.turnDeadlineAt,
                g.turnChatMode != null ? g.turnChatMode.name() : null,
                g.turnBanterLevel != null ? g.turnBanterLevel.name() : null,
                attempts, streamId
        );
    }

    /**
     * The resignation of a game that ended by one, from its moves in play order and its chat; null otherwise. Last
     * words are the resigning side's message for the ply it did not play; the opponent's message is its most recent
     * one before the resignation.
     */
    static ResignationDto resignation(Game g, List<Move> movesInPlayOrder) {
        String color = ResignationAnalysis.resigningColor(g);
        if (color == null) {
            return null;
        }
        int moveNumber = ResignationAnalysis.resignationMoveNumber(movesInPlayOrder, color);
        Double winChance = ResignationAnalysis.winChanceAtResignation(movesInPlayOrder, color);
        String lastWords = null;
        ChatMessage opponentMessage = null;
        for (ChatMessage chat : g.chatMessages) {
            if (chat.message == null || chat.message.isBlank()) {
                continue;
            }
            if (color.equals(chat.senderColor)) {
                if (chat.moveNumber == moveNumber) {
                    lastWords = chat.message;
                }
            } else {
                // Chat messages are in creation order; the last one wins.
                opponentMessage = chat;
            }
        }
        return new ResignationDto(
                color,
                moveNumber,
                winChance != null ? round4(winChance) : null,
                ResignationAnalysis.notLosing(winChance),
                lastWords,
                opponentMessage != null ? opponentMessage.message : null,
                opponentMessage != null ? opponentMessage.moveNumber : null
        );
    }

    private static AdjudicationDto adjudication(Game game, List<Move> moves) {
        if (game.resultReason != Game.ResultReason.ADJUDICATION || moves.isEmpty()) {
            return null;
        }
        String color = game.result == Game.GameResult.WHITE_WINS ? "WHITE" : "BLACK";
        Move last = moves.getLast();
        int sign = "WHITE".equals(color) ? 1 : -1;
        return new AdjudicationDto(color, WinAdjudication.WINDOW_PLIES,
                last.evaluationCp != null ? sign * last.evaluationCp : null,
                last.evaluationMate != null ? Math.abs(last.evaluationMate) : null);
    }

    public static MoveAttemptDto toDto(MoveAttempt a) {
        return new MoveAttemptDto(
                a.id, a.moveNumber, a.color, a.attemptNumber, a.modelId, a.outcome.name(), a.proposedMove,
                a.promptTokens, a.completionTokens, a.reasoningTokens, a.costUsd, a.responseTimeMs,
                a.finishReason, a.errorMessage, a.createdAt, apiValue(a.reasoningEffort),
                a.effectiveMaxOutputTokens, a.modelMaxCompletionTokens, a.cachedPromptTokens, a.cacheWriteTokens,
                a.turnStatusRevision
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
                m.chatMode != null ? m.chatMode.name() : null,
                m.isBook
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
                quality.excluded() != null ? quality.excluded().name() : null,
                quality.missedMate(), quality.missedMateIn()
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
