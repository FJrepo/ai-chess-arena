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
        ConversationState empty =
                new ConversationState(board, new ArrayList<>(), new ArrayList<>(), null, null, null, false);
        return refreshSystemPrompts(game, empty);
    }

    /** Retains model exchanges across a human wait, but always restores the committed board. */
    public ConversationState continueConversation(UUID gameId, ConversationState state) {
        Game game = gameRepository.findById(gameId);
        return new ConversationState(initializeBoard(gameId, game), state.whiteHistory(), state.blackHistory(),
                state.whitePrompt(), state.blackPrompt(), state.chatMode(), state.resignationAllowed());
    }

    /**
     * Resolves every rendered input each turn. A changed hash or prompt metadata clears that side's exchanges;
     * unchanged prompts retain the exact prefix. No history may carry answers under superseded instructions.
     */
    public ConversationState refreshSystemPrompts(Game game, ConversationState state) {
        ChatMode chatMode = configuredChatMode(game);
        boolean resignationAllowed = configuredResignationAllowed(game);
        PromptService.ResolvedPrompt whitePrompt = resolveSystemPrompt(game, "WHITE", chatMode, resignationAllowed);
        PromptService.ResolvedPrompt blackPrompt = resolveSystemPrompt(game, "BLACK", chatMode, resignationAllowed);
        PromptContext whiteContext = promptContext(whitePrompt);
        PromptContext blackContext = promptContext(blackPrompt);
        if (!whiteContext.equals(state.whitePrompt())) {
            setSystemPrompt(state.whiteHistory(), whitePrompt.prompt());
        }
        if (!blackContext.equals(state.blackPrompt())) {
            setSystemPrompt(state.blackHistory(), blackPrompt.prompt());
        }
        return new ConversationState(
                state.board(),
                state.whiteHistory(),
                state.blackHistory(),
                whiteContext,
                blackContext,
                chatMode,
                resignationAllowed
        );
    }

    /** The tournament's chat mode; games outside a tournament allow optional chat. */
    static ChatMode configuredChatMode(Game game) {
        return game.tournament != null && game.tournament.chatMode != null
                ? game.tournament.chatMode
                : ChatMode.OPTIONAL;
    }

    /** Whether the tournament lets models resign; games outside a tournament do not. */
    static boolean configuredResignationAllowed(Game game) {
        return game.tournament != null && game.tournament.resignationAllowed;
    }

    private PromptContext promptContext(PromptService.ResolvedPrompt prompt) {
        return new PromptContext(prompt.version(), promptService.computePromptHash(prompt.prompt()), prompt.chatMode(),
                prompt.resignationOffered());
    }

    private static void setSystemPrompt(List<OpenRouterService.ChatMsg> history, String prompt) {
        history.clear();
        history.add(new OpenRouterService.ChatMsg("system", prompt));
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
        return resolveSystemPrompt(game, color, configuredChatMode(game), configuredResignationAllowed(game));
    }

    private PromptService.ResolvedPrompt resolveSystemPrompt(Game game, String color, ChatMode chatMode,
                                                             boolean resignationAllowed) {
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
                    chatMode,
                    false
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
                chatMode,
                resignationAllowed,
                game.tournament.banterLevel,
                game.hasLegalMoveHints()
        );
    }

    private String opponentDescriptor(TournamentParticipant participant, String modelId) {
        if (participant != null && participant.controlType == TournamentParticipant.ControlType.HUMAN) {
            return "human player";
        }
        return modelId != null && !modelId.isBlank() ? modelId : "unknown model";
    }

    /**
     * The game loop's conversation. {@code chatMode} and {@code resignationAllowed} are the tournament's settings the
     * prompts were built for; each side's {@link PromptContext} says what applies to that side's answers.
     */
    public record ConversationState(
            Board board,
            List<OpenRouterService.ChatMsg> whiteHistory,
            List<OpenRouterService.ChatMsg> blackHistory,
            PromptContext whitePrompt,
            PromptContext blackPrompt,
            ChatMode chatMode,
            boolean resignationAllowed
    ) {
        public ConversationState(Board board, List<OpenRouterService.ChatMsg> whiteHistory,
                                 List<OpenRouterService.ChatMsg> blackHistory, PromptContext whitePrompt,
                                 PromptContext blackPrompt) {
            this(board, whiteHistory, blackHistory, whitePrompt, blackPrompt, ChatMode.OPTIONAL, false);
        }

        public ConversationState(Board board, List<OpenRouterService.ChatMsg> whiteHistory,
                                 List<OpenRouterService.ChatMsg> blackHistory, PromptContext whitePrompt,
                                 PromptContext blackPrompt, ChatMode chatMode) {
            this(board, whiteHistory, blackHistory, whitePrompt, blackPrompt, chatMode, false);
        }
    }

    /**
     * A side's system prompt: version, hash, the chat mode that applies to its answers and whether it offers
     * resignation (never with a legacy custom template).
     */
    public record PromptContext(String version, String hash, ChatMode chatMode, boolean resignationOffered) {
        public PromptContext(String version, String hash) {
            this(version, hash, ChatMode.OPTIONAL, false);
        }

        public PromptContext(String version, String hash, ChatMode chatMode) {
            this(version, hash, chatMode, false);
        }
    }
}
