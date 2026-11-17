package dev.aichessarena.service;

import dev.aichessarena.dto.DtoMapper;
import dev.aichessarena.dto.GameQualitySummaryDto;
import dev.aichessarena.dto.PlyQualityDto;
import dev.aichessarena.entity.ChatMessage;
import dev.aichessarena.entity.ChatMode;
import dev.aichessarena.entity.Game;
import dev.aichessarena.repository.ChatMessageRepository;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveRepository;
import dev.aichessarena.service.MoveQualityClassifier.MoveQuality;
import dev.aichessarena.websocket.GameWebSocket;
import com.github.bhlangonijr.chesslib.Board;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.TransactionSynchronizationRegistry;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
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

    /** Null outside CDI (unit tests): then evaluation updates go out at once. */
    @Inject
    TransactionSynchronizationRegistry transactionRegistry;

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
            String promptHash,
            String expectedFen,
            ChatMode chatMode
    ) {
        Game game = gameRepository.findByIdForUpdate(gameId);
        if (game == null || game.status != Game.GameStatus.IN_PROGRESS) {
            throw new GameStateConflictException("Game is no longer in progress");
        }
        if (!samePosition(game.currentFen, expectedFen)) {
            throw new GameStateConflictException("Position changed while the model was thinking");
        }

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
        // The mode that applied to this move; with a message it also has a chat message (see AnalyticsService).
        move.chatMode = chatMode;
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
                retryCount,
                false
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
            String message,
            String expectedFen
    ) {
        Game game = gameRepository.findByIdForUpdate(gameId);
        if (game == null || game.status != Game.GameStatus.IN_PROGRESS) {
            throw new GameStateConflictException("Game is not awaiting a human move");
        }
        if (!game.currentFen.equals(expectedFen)) {
            throw new GameStateConflictException("The position changed; reload the game and try again");
        }

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

        gameWebSocket.broadcastMove(gameId, moveNumber, color, san, fen, game.pgn, null, 0, 0, false);
    }

    @Transactional
    public void updateMoveEvaluation(UUID moveId, StockfishService.EvalResult result) {
        LOG.debugf("Updating move %s with evaluation: cp=%d, mate=%d", moveId, result.cp(), result.mate());
        dev.aichessarena.entity.Move move = moveRepository.findById(moveId);
        if (move != null) {
            move.evaluationCp = result.cp();
            move.evaluationMate = result.mate();
            moveRepository.persist(move);
            broadcastEvaluation(move);
        } else {
            LOG.warnf("Could not find move %s to update evaluation", moveId);
        }
    }

    /**
     * Sends the move's evaluation with the qualities it decides: the move's own (this evaluation is its "after")
     * and the next move's (this evaluation is its "before"; evaluations may finish out of order), plus the game's
     * updated summary. Quality is derived from the stored evaluations, so a reload shows the same.
     */
    private void broadcastEvaluation(dev.aichessarena.entity.Move move) {
        List<dev.aichessarena.entity.Move> moves = CanonicalPlies.of(moveRepository.findByGameId(move.game.id));
        int index = -1;
        for (int i = 0; i < moves.size(); i++) {
            if (moves.get(i).id != null && moves.get(i).id.equals(move.id)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            // A later duplicate's score must not replace the canonical ply's evaluation or quality in clients.
            return;
        }
        List<MoveQuality> qualities = MoveQualityClassifier.analyze(moves);
        List<PlyQualityDto> changed = new ArrayList<>();
        for (int i = index; i <= Math.min(index + 1, moves.size() - 1); i++) {
            dev.aichessarena.entity.Move affected = moves.get(i);
            changed.add(new PlyQualityDto(affected.moveNumber, affected.color, DtoMapper.toDto(qualities.get(i))));
        }
        GameQualitySummaryDto summary = DtoMapper.toDto(MoveQualityClassifier.summarize(moves, qualities));
        UUID gameId = move.game.id;
        int moveNumber = move.moveNumber;
        String color = move.color;
        Integer cp = move.evaluationCp;
        Integer mate = move.evaluationMate;
        // After the commit: a viewer that reloads the game on this update must already read the evaluation.
        AfterCommit.run(transactionRegistry,
                () -> gameWebSocket.broadcastEvaluation(gameId, moveNumber, color, cp, mate, changed, summary));
    }

    @Transactional
    public void applyOverrideMove(UUID gameId, String san, String expectedFen) {
        Game game = gameRepository.findByIdForUpdate(gameId);
        if (game == null) {
            throw new WebApplicationException("Game not found", Response.Status.NOT_FOUND);
        }
        if (game.status != Game.GameStatus.PAUSED) {
            throw new GameStateConflictException("Pause the game before overriding moves");
        }
        if (expectedFen != null && !game.currentFen.equals(expectedFen)) {
            throw new GameStateConflictException("The position changed; reload the game and try again");
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
        moveRepository.persistAndFlush(move);

        updateGameState(game, result.fen(), normalizedSan, moveNumber, sideToMove);
        moveEvaluationEvents.fire(new MoveEvaluationRequested(move.id, gameId, moveNumber, sideToMove, result.fen()));
        gameWebSocket.broadcastMove(gameId, moveNumber, sideToMove, normalizedSan, result.fen(), game.pgn,
                "admin-override", 0, 0, true);
    }

    /** Compares piece placement, side to move and castling rights; ignores clocks and en-passant formatting. */
    static boolean samePosition(String fenA, String fenB) {
        if (fenA == null || fenB == null) {
            return false;
        }
        String[] a = fenA.trim().split("\\s+");
        String[] b = fenB.trim().split("\\s+");
        int fields = Math.min(3, Math.min(a.length, b.length));
        for (int i = 0; i < fields; i++) {
            if (!a[i].equals(b[i])) {
                return false;
            }
        }
        return fields == 3;
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
