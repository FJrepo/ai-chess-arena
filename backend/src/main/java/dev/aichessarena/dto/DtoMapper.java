package dev.aichessarena.dto;

import dev.aichessarena.entity.*;

import java.util.List;

public final class DtoMapper {

    private DtoMapper() {}

    public static TournamentDto toDto(Tournament t) {
        return new TournamentDto(
                t.id, t.name, t.status.name(), t.format.name(),
                t.drawPolicy.name(),
                t.defaultSystemPrompt, t.moveTimeoutSeconds,
                t.maxRetries, t.matchupBestOf, t.finalsBestOf, t.trashTalkEnabled,
                t.createdAt, t.updatedAt,
                t.participants.stream().map(DtoMapper::toDto).toList(),
                t.games.stream().map(g -> toDto(g, false)).toList()
        );
    }

    public static ParticipantDto toDto(TournamentParticipant p) {
        return new ParticipantDto(p.id, p.playerName, p.modelId, p.customSystemPrompt, p.seed);
    }

    public static GameDto toDto(Game g, boolean includeMoves) {
        return new GameDto(
                g.id,
                g.tournament != null ? g.tournament.id : null,
                g.whitePlayerName, g.whiteModelId,
                g.blackPlayerName, g.blackModelId,
                g.status.name(),
                g.result != null ? g.result.name() : null,
                g.resultReason != null ? g.resultReason.name() : null,
                g.pgn, g.currentFen,
                g.bracketRound, g.bracketPosition, g.seriesId, g.seriesGameNumber, g.seriesBestOf,
                g.totalCostUsd, g.createdAt, g.startedAt, g.completedAt,
                includeMoves ? g.moves.stream().map(DtoMapper::toDto).toList() : List.of(),
                includeMoves ? g.chatMessages.stream().map(DtoMapper::toDto).toList() : List.of()
        );
    }

    public static MoveDto toDto(Move m) {
        return new MoveDto(
                m.id, m.moveNumber, m.color, m.san, m.fen,
                m.modelId, m.promptVersion, m.promptHash, m.promptTokens, m.completionTokens,
                m.costUsd, m.responseTimeMs, m.retryCount,
                m.isOverride, m.evaluationCp, m.evaluationMate,
                m.createdAt
        );
    }

    public static ChatMessageDto toDto(ChatMessage c) {
        return new ChatMessageDto(c.id, c.moveNumber, c.senderModel, c.senderColor, c.message, c.createdAt);
    }
}
