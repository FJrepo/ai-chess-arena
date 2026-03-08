package dev.aichessarena.service;

import dev.aichessarena.entity.ChatMessage;
import dev.aichessarena.entity.Game;
import dev.aichessarena.repository.ChatMessageRepository;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveRepository;
import dev.aichessarena.websocket.GameWebSocket;
import com.github.bhlangonijr.chesslib.Board;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.util.UUID;
import org.jboss.logging.Logger;

@ApplicationScoped
public class GameMoveService {

    private static final Logger LOG = Logger.getLogger(GameMoveService.class);

    @Inject
    ChessService chessService;

    @Inject
    GameRepository gameRepository;

    @Inject
    MoveRepository moveRepository;

    @Inject
    ChatMessageRepository chatMessageRepository;

    @Inject
    CostTrackingService costTrackingService;

    @Inject
    GameWebSocket gameWebSocket;

    @Inject
    Event<MoveEvaluationRequested> moveEvaluationEvents;

    @Transactional
    public void recordModelMove(
            UUID gameId,
            int moveNumber,
            String color,
            String san,
            String fen,
            String modelId,
            String playerName,
            String message,
            OpenRouterService.LlmResponse llmResponse,
            int retryCount,
            String promptVersion,
            String promptHash
    ) {
        Game game = gameRepository.findById(gameId);

        dev.aichessarena.entity.Move move = new dev.aichessarena.entity.Move();
        move.game = game;
        move.moveNumber = moveNumber;
        move.color = color;
        move.san = san;
        move.fen = fen;
        move.modelId = modelId;
        move.promptVersion = promptVersion;
        move.promptHash = promptHash;
        move.promptTokens = llmResponse.promptTokens();
        move.completionTokens = llmResponse.completionTokens();
        if (llmResponse.costUsd() != null) {
            move.costUsd = BigDecimal.valueOf(llmResponse.costUsd());
            costTrackingService.addMoveCost(gameId, move.costUsd);
        }
        move.responseTimeMs = llmResponse.responseTimeMs();
        move.retryCount = retryCount;
        move.rawResponse = llmResponse.rawResponse();
        moveRepository.persistAndFlush(move);

        updateGameState(game, fen, san, moveNumber, color);
        moveEvaluationEvents.fire(new MoveEvaluationRequested(move.id, gameId, moveNumber, color, fen));

        if (message != null && !message.isBlank()) {
            persistChatMessage(game, moveNumber, modelId, color, message);
            gameWebSocket.broadcastChat(gameId, moveNumber, color, modelId, playerName, message);
        }

        gameWebSocket.broadcastMove(
                gameId,
                moveNumber,
                color,
                san,
                fen,
                game.pgn,
                modelId,
                llmResponse.responseTimeMs(),
                retryCount
        );
    }

    @Transactional
    public void recordHumanMove(
            UUID gameId,
            int moveNumber,
            String color,
            String san,
            String fen,
            String playerName,
            String message
    ) {
        Game game = gameRepository.findById(gameId);

        dev.aichessarena.entity.Move move = new dev.aichessarena.entity.Move();
        move.game = game;
        move.moveNumber = moveNumber;
        move.color = color;
        move.san = san;
        move.fen = fen;
        move.promptVersion = "human";
        move.promptHash = null;
        move.retryCount = 0;
        moveRepository.persistAndFlush(move);

        updateGameState(game, fen, san, moveNumber, color);
        moveEvaluationEvents.fire(new MoveEvaluationRequested(move.id, gameId, moveNumber, color, fen));

        if (message != null && !message.isBlank()) {
            persistChatMessage(game, moveNumber, null, color, message);
            gameWebSocket.broadcastChat(gameId, moveNumber, color, null, playerName, message);
        }

        gameWebSocket.broadcastMove(gameId, moveNumber, color, san, fen, game.pgn, null, 0, 0);
    }

    @Transactional
    public void updateMoveEvaluation(UUID moveId, StockfishService.EvalResult result) {
        LOG.debugf("Updating move %s with evaluation: cp=%d, mate=%d", moveId, result.cp(), result.mate());
        dev.aichessarena.entity.Move move = moveRepository.findById(moveId);
        if (move != null) {
            move.evaluationCp = result.cp();
            move.evaluationMate = result.mate();
            moveRepository.persist(move);
            gameWebSocket.broadcastEvaluation(move.game.id, move.moveNumber, move.color, result.cp(), result.mate());
        } else {
            LOG.warnf("Could not find move %s to update evaluation", moveId);
        }
    }

    @Transactional
    public void applyOverrideMove(UUID gameId, String san) {
        Game game = gameRepository.findById(gameId);
        if (game == null) {
            throw new WebApplicationException("Game not found", Response.Status.NOT_FOUND);
        }
        if (san == null || san.isBlank()) {
            throw new IllegalArgumentException("Move is required");
        }

        Board board = chessService.boardFromFen(game.currentFen);
        String sideToMove = chessService.getSideToMove(board);
        int moveNumber = chessService.getMoveNumber(board);

        String normalizedSan = san.trim();
        ChessService.ValidMoveResult result = chessService.validateAndApply(board, normalizedSan);
        if (!result.valid()) {
            throw new IllegalArgumentException("Invalid move: " + result.error());
        }

        dev.aichessarena.entity.Move move = new dev.aichessarena.entity.Move();
        move.game = game;
        move.moveNumber = moveNumber;
        move.color = sideToMove;
        move.san = normalizedSan;
        move.fen = result.fen();
        move.promptVersion = "admin-override";
        move.promptHash = null;
        move.isOverride = true;
        moveRepository.persist(move);

        updateGameState(game, result.fen(), normalizedSan, moveNumber, sideToMove);
        gameWebSocket.broadcastMove(gameId, moveNumber, sideToMove, normalizedSan, result.fen(), game.pgn, "admin-override", 0, 0);
    }

    private void updateGameState(Game game, String fen, String san, int moveNumber, String color) {
        game.currentFen = fen;

        String moveStr = "WHITE".equals(color) ? moveNumber + ". " + san : san;
        game.pgn = (game.pgn == null ? "" : game.pgn + " ") + moveStr;
        gameRepository.persist(game);
    }

    private void persistChatMessage(Game game, int moveNumber, String modelId, String color, String message) {
        ChatMessage chat = new ChatMessage();
        chat.game = game;
        chat.moveNumber = moveNumber;
        chat.senderModel = modelId;
        chat.senderColor = color;
        chat.message = message;
        chatMessageRepository.persist(chat);
    }
}
