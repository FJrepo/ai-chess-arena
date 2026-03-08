package dev.aichessarena.service;

import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Game.GameStatus;
import dev.aichessarena.repository.GameRepository;
import com.github.bhlangonijr.chesslib.Board;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class GameEngineService {

    private static final Logger LOG = Logger.getLogger(GameEngineService.class);

    @Inject
    ChessService chessService;

    @Inject
    GameRepository gameRepository;

    @Inject
    GameLifecycleService gameLifecycleService;

    @Inject
    GameMoveService gameMoveService;

    @Inject
    GameTurnService gameTurnService;

    @Inject
    GameConversationService gameConversationService;

    private final Map<UUID, Boolean> runningGames = new ConcurrentHashMap<>();

    public void startGame(UUID gameId) {
        startGame(gameId, null, null);
    }

    public void startGame(UUID gameId, String lastMoveSan, String lastMessage) {
        if (!tryMarkGameRunning(gameId)) {
            LOG.warnf("Game %s is already running", gameId);
            return;
        }

        Thread.startVirtualThread(() -> {
            ManagedContext requestContext = Arc.container().requestContext();
            requestContext.activate();
            try {
                runGameLoop(gameId, lastMoveSan, lastMessage);
            } catch (Exception e) {
                LOG.errorf(e, "Game loop failed for game %s", gameId);
            } finally {
                if (requestContext.isActive()) {
                    requestContext.terminate();
                }
                runningGames.remove(gameId);
            }
        });
    }

    public void pauseGame(UUID gameId) {
        if (Boolean.TRUE.equals(runningGames.get(gameId))) {
            runningGames.put(gameId, false);
            return;
        }

        runningGames.remove(gameId);
        Game game = gameRepository.findById(gameId);
        if (game != null && game.status == GameStatus.IN_PROGRESS) {
            gameLifecycleService.markGamePaused(gameId);
        }
    }

    public boolean isRunning(UUID gameId) {
        return runningGames.getOrDefault(gameId, false);
    }

    private void runGameLoop(UUID gameId, String lastMoveSan, String lastMessage) {
        boolean resumingAfterHumanMove = lastMoveSan != null;
        if (!resumingAfterHumanMove) {
            gameLifecycleService.markGameStarted(gameId);
        }

        GameConversationService.ConversationState conversation = gameConversationService.initializeConversation(gameId);
        Board board = conversation.board();
        List<OpenRouterService.ChatMsg> whiteHistory = conversation.whiteHistory();
        List<OpenRouterService.ChatMsg> blackHistory = conversation.blackHistory();
        String whitePromptVersion = conversation.whitePrompt().version();
        String blackPromptVersion = conversation.blackPrompt().version();
        String whitePromptHash = conversation.whitePrompt().hash();
        String blackPromptHash = conversation.blackPrompt().hash();
        Game game = gameRepository.findById(gameId);

        while (Boolean.TRUE.equals(runningGames.get(gameId))) {
            if (chessService.isGameOver(board)) {
                endGame(gameId, board);
                return;
            }

            String sideToMove = chessService.getSideToMove(board);
            int moveNumber = chessService.getMoveNumber(board);
            List<String> legalMoves = chessService.getLegalMovesAsSan(board);
            String fen = board.getFen();
            // Get current player info
            game = gameRepository.findById(gameId);
            String pgn = game.pgn;
            String modelId = "WHITE".equals(sideToMove) ? game.whiteModelId : game.blackModelId;
            String playerName = "WHITE".equals(sideToMove) ? game.whitePlayerName : game.blackPlayerName;
            String opponentName = "WHITE".equals(sideToMove) ? game.blackPlayerName : game.whitePlayerName;
            List<OpenRouterService.ChatMsg> history = "WHITE".equals(sideToMove) ? whiteHistory : blackHistory;

            if (isHumanTurn(game, sideToMove)) {
                gameTurnService.broadcastHumanTurnReady(gameId, game.totalCostUsd, sideToMove);
                return;
            }

            boolean isFirstMove = moveNumber == 1 && "WHITE".equals(sideToMove);

            int maxRetries = game.tournament != null ? game.tournament.maxRetries : 3;
            int moveTimeoutSeconds = game.tournament != null ? game.tournament.moveTimeoutSeconds : 60;
            GameTurnService.TurnResult turnResult = gameTurnService.playTurn(new GameTurnService.TurnRequest(
                    gameId,
                    pgn,
                    fen,
                    board,
                    sideToMove,
                    moveNumber,
                    legalMoves,
                    modelId,
                    playerName,
                    opponentName,
                    history,
                    lastMoveSan,
                    lastMessage,
                    isFirstMove,
                    maxRetries,
                    moveTimeoutSeconds,
                    "WHITE".equals(sideToMove) ? whitePromptVersion : blackPromptVersion,
                    "WHITE".equals(sideToMove) ? whitePromptHash : blackPromptHash,
                    game.totalCostUsd,
                    () -> Boolean.TRUE.equals(runningGames.get(gameId))
            ));

            if (turnResult.isTerminal()) {
                return;
            }

            if (!turnResult.moveRecorded()) {
                break;
            }

            game = gameRepository.findById(gameId);
            lastMoveSan = turnResult.moveSan();
            lastMessage = turnResult.message();
        }

        // Game was paused
        gameLifecycleService.markGamePaused(gameId);
    }

    public void updateMoveEvaluation(UUID moveId, StockfishService.EvalResult result) {
        gameMoveService.updateMoveEvaluation(moveId, result);
    }

    void endGame(UUID gameId, Board board) {
        ChessService.GameEndInfo endInfo = chessService.getGameEndInfo(board);
        gameLifecycleService.completeGame(gameId, endInfo);
    }

    public void overrideMove(UUID gameId, String san) {
        Game game = gameRepository.findById(gameId);
        if (game == null) {
            throw new WebApplicationException("Game not found", Response.Status.NOT_FOUND);
        }
        if (game.status != GameStatus.PAUSED || Boolean.TRUE.equals(runningGames.get(gameId))) {
            throw new WebApplicationException("Pause the game before overriding moves", Response.Status.CONFLICT);
        }
        gameMoveService.applyOverrideMove(gameId, san);
    }

    public void submitHumanMove(UUID gameId, String san, String from, String to, String promotion, String message) {
        Game game = gameRepository.findById(gameId);
        if (game == null) {
            throw new WebApplicationException("Game not found", Response.Status.NOT_FOUND);
        }
        if (game.status != GameStatus.IN_PROGRESS) {
            throw new WebApplicationException("Game is not awaiting a human move", Response.Status.CONFLICT);
        }
        if (Boolean.TRUE.equals(runningGames.get(gameId))) {
            throw new WebApplicationException("Game is still processing a turn", Response.Status.CONFLICT);
        }
        boolean hasSan = san != null && !san.isBlank();
        boolean hasCoordinates = from != null && !from.isBlank() && to != null && !to.isBlank();
        if (!hasSan && !hasCoordinates) {
            throw new IllegalArgumentException("Move is required");
        }

        Board board = chessService.boardFromFen(game.currentFen);
        String sideToMove = chessService.getSideToMove(board);
        if (!isHumanTurn(game, sideToMove)) {
            throw new WebApplicationException("It is not a human-controlled turn", Response.Status.CONFLICT);
        }

        int moveNumber = chessService.getMoveNumber(board);
        ChessService.ValidMoveResult result = hasCoordinates
                ? chessService.validateAndApplyCoordinates(board, from, to, promotion)
                : chessService.validateAndApply(board, san.trim());
        if (!result.valid()) {
            throw new IllegalArgumentException("Invalid move: " + result.error());
        }

        String playerName = "WHITE".equals(sideToMove) ? game.whitePlayerName : game.blackPlayerName;
        gameMoveService.recordHumanMove(
                gameId,
                moveNumber,
                sideToMove,
                result.san(),
                result.fen(),
                playerName,
                message
        );

        if (chessService.isGameOver(board)) {
            endGame(gameId, board);
            return;
        }

        startGame(gameId, result.san(), message);
    }

    private boolean isHumanTurn(Game game, String color) {
        var participant = "WHITE".equals(color) ? game.whiteParticipant : game.blackParticipant;
        return participant != null && participant.controlType == dev.aichessarena.entity.TournamentParticipant.ControlType.HUMAN;
    }

    boolean tryMarkGameRunning(UUID gameId) {
        return runningGames.putIfAbsent(gameId, true) == null;
    }

}
