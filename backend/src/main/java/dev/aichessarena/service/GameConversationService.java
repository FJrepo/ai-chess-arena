package dev.aichessarena.service;

import com.github.bhlangonijr.chesslib.Board;
import dev.aichessarena.entity.ChatMode;
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
        ConversationState empty = new ConversationState(board, new ArrayList<>(), new ArrayList<>(), null, null, null);
        return refreshSystemPrompts(game, empty);
    }

    /**
     * Builds both system prompts for the game's current chat mode and puts them at the start of the histories,
     * replacing earlier ones. The game loop calls this again when the tournament's chat mode changes, so a side's
     * prompt, the handling of its messages and the mode recorded on its moves always agree.
     */
    public ConversationState refreshSystemPrompts(Game game, ConversationState state) {
        return refreshSystemPrompts(game, state, configuredChatMode(game));
    }

    /** As {@link #refreshSystemPrompts(Game, ConversationState)}, for a chat mode the caller read itself. */
    public ConversationState refreshSystemPrompts(Game game, ConversationState state, ChatMode chatMode) {
        PromptService.ResolvedPrompt whitePrompt = resolveSystemPrompt(game, "WHITE", chatMode);
        PromptService.ResolvedPrompt blackPrompt = resolveSystemPrompt(game, "BLACK", chatMode);
        setSystemPrompt(state.whiteHistory(), whitePrompt.prompt());
        setSystemPrompt(state.blackHistory(), blackPrompt.prompt());
        return new ConversationState(
                state.board(),
                state.whiteHistory(),
                state.blackHistory(),
                promptContext(whitePrompt),
                promptContext(blackPrompt),
                chatMode
        );
    }

    /** The tournament's chat mode; games outside a tournament allow optional chat. */
    static ChatMode configuredChatMode(Game game) {
        return game.tournament != null && game.tournament.chatMode != null
                ? game.tournament.chatMode
                : ChatMode.OPTIONAL;
    }

    private PromptContext promptContext(PromptService.ResolvedPrompt prompt) {
        return new PromptContext(prompt.version(), promptService.computePromptHash(prompt.prompt()), prompt.chatMode());
    }

    private static void setSystemPrompt(List<OpenRouterService.ChatMsg> history, String prompt) {
        OpenRouterService.ChatMsg system = new OpenRouterService.ChatMsg("system", prompt);
        if (!history.isEmpty() && "system".equals(history.getFirst().role())) {
            history.set(0, system);
        } else {
            history.addFirst(system);
        }
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
        return resolveSystemPrompt(game, color, configuredChatMode(game));
    }

    private PromptService.ResolvedPrompt resolveSystemPrompt(Game game, String color, ChatMode chatMode) {
        if (game.tournament == null) {
            return promptService.buildSystemPrompt(
                    null,
                    null,
                    color,
                    "WHITE".equals(color) ? game.blackPlayerName : game.whitePlayerName,
                    opponentDescriptor(
                            "WHITE".equals(color) ? game.blackParticipant : game.whiteParticipant,
                            "WHITE".equals(color) ? game.blackModelId : game.whiteModelId
                    ),
                    chatMode
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
                opponentModel,
                chatMode
        );
    }

    private String opponentDescriptor(TournamentParticipant participant, String modelId) {
        if (participant != null && participant.controlType == TournamentParticipant.ControlType.HUMAN) {
            return "human player";
        }
        return modelId != null && !modelId.isBlank() ? modelId : "unknown model";
    }

    /**
     * The game loop's conversation. {@code chatMode} is the tournament's chat mode the prompts were built for; each
     * side's {@link PromptContext#chatMode()} is the mode that applies to that side's answers.
     */
    public record ConversationState(
            Board board,
            List<OpenRouterService.ChatMsg> whiteHistory,
            List<OpenRouterService.ChatMsg> blackHistory,
            PromptContext whitePrompt,
            PromptContext blackPrompt,
            ChatMode chatMode
    ) {
        public ConversationState(Board board, List<OpenRouterService.ChatMsg> whiteHistory,
                                 List<OpenRouterService.ChatMsg> blackHistory, PromptContext whitePrompt,
                                 PromptContext blackPrompt) {
            this(board, whiteHistory, blackHistory, whitePrompt, blackPrompt, ChatMode.OPTIONAL);
        }
    }

    /** A side's system prompt: version, hash and the chat mode that applies to its answers. */
    public record PromptContext(String version, String hash, ChatMode chatMode) {
        public PromptContext(String version, String hash) {
            this(version, hash, ChatMode.OPTIONAL);
        }
    }
}
