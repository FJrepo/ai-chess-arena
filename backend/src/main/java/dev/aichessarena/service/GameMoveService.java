package dev.aichessarena.service;

import dev.aichessarena.dto.DtoMapper;
import dev.aichessarena.dto.GameQualitySummaryDto;
import dev.aichessarena.dto.PlyQualityDto;
import dev.aichessarena.entity.ChatMode;
import dev.aichessarena.entity.ChatMessage;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Move;
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
    LiveGameUpdates liveUpdates;

    @Inject
    Event<MoveEvaluationRequested> moveEvaluationEvents;

    /** Null outside CDI (unit tests), where live updates go out immediately. */
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
        GameLifecycleService.requireActiveTournament(game);
        if (!samePosition(game.currentFen, expectedFen)) {
            throw new GameStateConflictException("Position changed while the model was thinking");
        }

        Move move = new Move();
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
        persistMove(move, playerName, message);
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
        GameLifecycleService.requireActiveTournament(game);
        if (!game.currentFen.equals(expectedFen)) {
            throw new GameStateConflictException("The position changed; reload the game and try again");
        }

        Move move = new Move();
        move.game = game;
        move.moveNumber = moveNumber;
        move.color = color;
        move.san = san;
        move.fen = fen;
        move.promptVersion = "human";
        move.promptHash = null;
        move.retryCount = 0;
        persistMove(move, playerName, message);
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void updateMoveEvaluation(UUID moveId, StockfishService.EvalResult result) {
        LOG.debugf("Updating move %s with evaluation: cp=%d, mate=%d", moveId, result.cp(), result.mate());
        Move move = moveRepository.findById(moveId);
        if (move != null) {
            Game game = liveUpdates == null ? move.game : gameRepository.findByIdForUpdate(move.game.id);
            if (game == null) return;
            if (liveUpdates != null) {
                moveRepository.getEntityManager().refresh(move);
                if (java.util.Objects.equals(move.evaluationCp, result.cp())
                        && java.util.Objects.equals(move.evaluationMate, result.mate())) return;
            }
            move.evaluationCp = result.cp();
            move.evaluationMate = result.mate();
            moveRepository.persist(move);
            broadcastEvaluation(move, game);
        } else {
            LOG.warnf("Could not find move %s to update evaluation", moveId);
        }
    }

    /**
     * Sends the move's evaluation with the qualities it decides: the move's own (this evaluation is its "after")
     * and the next move's (this evaluation is its "before"; evaluations may finish out of order), plus the game's
     * updated summary. Quality is derived from the stored evaluations, so a reload shows the same.
     */
    private void broadcastEvaluation(Move move, Game game) {
        List<Move> moves = CanonicalPlies.of(moveRepository.findByGameId(move.game.id));
        int index = -1;
        for (int i = 0; i < moves.size(); i++) {
            if (moves.get(i).id != null && moves.get(i).id.equals(move.id)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            // A legacy duplicate remains visible in history but never changes the canonical ply or quality.
            if (liveUpdates != null) announceEvaluation(move, game, List.of(), null);
            return;
        }
        List<MoveQuality> qualities = MoveQualityClassifier.analyze(moves);
        List<PlyQualityDto> changed = new ArrayList<>();
        for (int i = index; i <= Math.min(index + 1, moves.size() - 1); i++) {
            Move affected = moves.get(i);
            changed.add(new PlyQualityDto(affected.moveNumber, affected.color, DtoMapper.toDto(qualities.get(i)), affected.id));
        }
        GameQualitySummaryDto summary = DtoMapper.toDto(MoveQualityClassifier.summarize(moves, qualities));
        if (liveUpdates != null) announceEvaluation(move, game, changed, summary);
        UUID gameId = move.game.id;
        int moveNumber = move.moveNumber;
        String color = move.color;
        Integer cp = move.evaluationCp;
        Integer mate = move.evaluationMate;
        // After the commit: a viewer that reloads the game on this update must already read the evaluation.
        AfterCommit.run(transactionRegistry,
                () -> gameWebSocket.broadcastEvaluation(gameId, moveNumber, color, cp, mate, changed, summary));
    }

    private void announceEvaluation(Move move, Game game, List<PlyQualityDto> changed,
                                    GameQualitySummaryDto summary) {
        liveUpdates.changed(game, liveUpdates.evaluationChange(game.id, move.id, move.moveNumber, move.color, move.evaluationCp,
                move.evaluationMate, changed, summary));
    }

    @Transactional
    public void applyOverrideMove(UUID gameId, String san, String expectedFen) {
        Game game = gameRepository.findByIdForUpdate(gameId);
        if (game == null) {
            throw new WebApplicationException("Game not found", Response.Status.NOT_FOUND);
        }
        GameLifecycleService.requireActiveTournament(game);
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

        ChessService.ValidMoveResult result = chessService.validateAndApply(board, san.trim());
        if (!result.valid()) {
            throw new IllegalArgumentException("Invalid move: " + result.error());
        }

        Move move = new Move();
        move.game = game;
        move.moveNumber = moveNumber;
        move.color = sideToMove;
        move.san = result.san();
        move.fen = result.fen();
        move.promptVersion = "admin-override";
        move.promptHash = null;
        move.isOverride = true;
        persistMove(move, null, null);
    }

    private void persistMove(Move move, String playerName, String message) {
        moveRepository.persistAndFlush(move);
        Game game = move.game;
        UUID gameId = game.id;
        int moveNumber = move.moveNumber;
        String color = move.color;
        String san = move.san;
        String fen = move.fen;
        updateGameState(game, fen, san, moveNumber, color);
        game.clearTurn();
        ChatMessage storedChat = chatMessageRepository.persistMessage(game, moveNumber, color, move.modelId, message);
        UUID chatId = storedChat == null ? null : storedChat.id;

        // Capture this ply's scoresheet while attached. Chat and move share one ordered callback.
        String pgn = game.pgn;
        long statusRevision = game.statusRevision;
        boolean isOverride = move.isOverride;
        String modelId = isOverride ? "admin-override" : move.modelId;
        long responseTimeMs = move.responseTimeMs == null ? 0 : move.responseTimeMs;
        int retryCount = move.retryCount;
        if (liveUpdates != null) {
            liveUpdates.changed(game, liveUpdates.entityChange("committedMove", gameId, "move",
                    DtoMapper.toDto(move)).put("pgn", pgn));
            if (chatId != null) {
                liveUpdates.changed(game, liveUpdates.entityChange("committedChat", gameId, "chat",
                        DtoMapper.toDto(storedChat)));
            }
        }
        AfterCommit.run(transactionRegistry, () -> {
            if (chatId != null) {
                gameWebSocket.broadcastChat(gameId, chatId, moveNumber, color, modelId, playerName, message);
            }
            gameWebSocket.broadcastMove(gameId, moveNumber, color, san, fen, pgn, modelId,
                    responseTimeMs, retryCount, isOverride, statusRevision);
        });
        moveEvaluationEvents.fire(new MoveEvaluationRequested(move.id, gameId, moveNumber, color, fen));
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

        game.pgn = appendPgn(game.pgn, san, moveNumber, color);
        gameRepository.persist(game);
    }

    static String appendPgn(String pgn, String san, int moveNumber, String color) {
        String moveStr = "WHITE".equals(color) ? moveNumber + ". " + san : san;
        return (pgn == null ? "" : pgn + " ") + moveStr;
    }
}
