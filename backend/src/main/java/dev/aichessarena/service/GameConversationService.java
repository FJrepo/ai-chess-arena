package dev.aichessarena.service;

import com.github.bhlangonijr.chesslib.Board;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Move;
import dev.aichessarena.entity.TournamentParticipant;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class GameConversationService {

    @Inject
    ChessService chessService;

    @Inject
    PromptService promptService;

    @Inject
    GameRepository gameRepository;

    @Inject
    MoveRepository moveRepository;

    public ConversationState initializeConversation(UUID gameId) {
        Game game = gameRepository.findById(gameId);
        Board board = initializeBoard(gameId, game);

        PromptService.ResolvedPrompt whitePrompt = resolveSystemPrompt(game, "WHITE");
        PromptService.ResolvedPrompt blackPrompt = resolveSystemPrompt(game, "BLACK");

        List<OpenRouterService.ChatMsg> whiteHistory = new ArrayList<>();
        List<OpenRouterService.ChatMsg> blackHistory = new ArrayList<>();
        whiteHistory.add(new OpenRouterService.ChatMsg("system", whitePrompt.prompt()));
        blackHistory.add(new OpenRouterService.ChatMsg("system", blackPrompt.prompt()));

        return new ConversationState(
                board,
                whiteHistory,
                blackHistory,
                new PromptContext(whitePrompt.version(), promptService.computePromptHash(whitePrompt.prompt())),
                new PromptContext(blackPrompt.version(), promptService.computePromptHash(blackPrompt.prompt()))
        );
    }

    private Board initializeBoard(UUID gameId, Game game) {
        Board board = chessService.boardFromFen(game.currentFen);
        List<Move> moves = moveRepository.findByGameId(gameId);
        if (moves.isEmpty()) {
            return board;
        }

        board.loadFromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
        for (Move move : moves) {
            chessService.validateAndApply(board, move.san);
        }
        return board;
    }

    PromptService.ResolvedPrompt resolveSystemPrompt(Game game, String color) {
        if (game.tournament == null) {
            return promptService.buildSystemPrompt(
                    null,
                    null,
                    color,
                    "WHITE".equals(color) ? game.blackPlayerName : game.whitePlayerName,
                    opponentDescriptor(
                            "WHITE".equals(color) ? game.blackParticipant : game.whiteParticipant,
                            "WHITE".equals(color) ? game.blackModelId : game.whiteModelId
                    )
            );
        }

        var participant = "WHITE".equals(color) ? game.whiteParticipant : game.blackParticipant;
        String opponentName = "WHITE".equals(color) ? game.blackPlayerName : game.whitePlayerName;
        String opponentModel = opponentDescriptor(
                "WHITE".equals(color) ? game.blackParticipant : game.whiteParticipant,
                "WHITE".equals(color) ? game.blackModelId : game.whiteModelId
        );

        String legacyTemplate = null;
        if (participant != null && participant.customSystemPrompt != null && !participant.customSystemPrompt.isBlank()) {
            legacyTemplate = participant.customSystemPrompt;
        } else if (game.tournament.defaultSystemPrompt != null && !game.tournament.defaultSystemPrompt.isBlank()) {
            legacyTemplate = game.tournament.defaultSystemPrompt;
        }

        String customInstructions = null;
        if (participant != null && participant.customInstructions != null && !participant.customInstructions.isBlank()) {
            customInstructions = participant.customInstructions;
        } else if (game.tournament.sharedCustomInstructions != null
                && !game.tournament.sharedCustomInstructions.isBlank()) {
            customInstructions = game.tournament.sharedCustomInstructions;
        }

        return promptService.buildSystemPrompt(
                legacyTemplate,
                customInstructions,
                color,
                opponentName,
                opponentModel
        );
    }

    private String opponentDescriptor(TournamentParticipant participant, String modelId) {
        if (participant != null && participant.controlType == TournamentParticipant.ControlType.HUMAN) {
            return "human player";
        }
        return modelId != null && !modelId.isBlank() ? modelId : "unknown model";
    }

    public record ConversationState(
            Board board,
            List<OpenRouterService.ChatMsg> whiteHistory,
            List<OpenRouterService.ChatMsg> blackHistory,
            PromptContext whitePrompt,
            PromptContext blackPrompt
    ) {
    }

    public record PromptContext(String version, String hash) {
    }
}
