package dev.aichessarena.service;

import com.github.bhlangonijr.chesslib.Board;
import dev.aichessarena.dto.DtoMapper;
import dev.aichessarena.dto.GameDto;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Game.GameStatus;
import dev.aichessarena.entity.Game.PlayerType;
import dev.aichessarena.entity.Move;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@ApplicationScoped
public class PracticeGameService {

    @Inject
    GameRepository games;

    @Inject
    MoveRepository moves;

    @Inject
    ChessService chess;

    @Transactional
    public List<GameDto> list() {
        return games.list("practiceKind is not null order by createdAt desc, id").stream()
                .map(game -> DtoMapper.toDto(game, false)).toList();
    }

    @Transactional
    public GameDto createStockfish(String humanSide, int elo) {
        boolean humanWhite = "WHITE".equals(humanSide);
        Game practice = new Game();
        practice.practiceKind = Game.PracticeKind.HUMAN_STOCKFISH;
        practice.stockfishElo = elo;
        practice.whitePracticeControl = humanWhite ? PlayerType.HUMAN : PlayerType.STOCKFISH;
        practice.blackPracticeControl = humanWhite ? PlayerType.STOCKFISH : PlayerType.HUMAN;
        practice.whitePlayerName = humanWhite ? "You" : "Stockfish";
        practice.blackPlayerName = humanWhite ? "Stockfish" : "You";
        games.persist(practice);
        return DtoMapper.toDto(practice, false);
    }

    @Transactional
    public GameDto fork(UUID sourceId, int ply, String humanSide, UUID requestId, UUID moveId) {
        Game previous = retry(sourceId, requestId, ply, humanSide, moveId);
        if (previous != null) return DtoMapper.toDto(previous, false);

        Game source = games.findByIdForUpdate(sourceId);
        // The source lock serializes creates, so check again after a concurrent create or deletion.
        previous = retry(sourceId, requestId, ply, humanSide, moveId);
        if (previous != null) return DtoMapper.toDto(previous, false);
        if (source == null) throw new NotFoundException("Source game not found");
        if (!AnalyticsGameOutcomeClassifier.isTerminal(source.status)
                || AnalyticsGameOutcomeClassifier.isBye(source)) {
            throw new GameStateConflictException("Practice requires a finished game with two players");
        }
        boolean humanWhite = "WHITE".equals(humanSide);
        String opponentSide = humanWhite ? "BLACK" : "WHITE";
        String modelId = humanWhite ? source.blackModelId : source.whiteModelId;
        String modelName = humanWhite ? source.blackPlayerName : source.whitePlayerName;
        if (source.playerType(opponentSide) != PlayerType.AI || modelId == null || modelId.isBlank()) {
            throw new GameStateConflictException("The opposite side in this game has no model opponent");
        }

        List<Move> history = CanonicalPlies.of(moves.findByGameId(sourceId));
        if (ply > history.size()) throw new IllegalArgumentException("Ply is outside the source history");
        if (moveId != null && (ply == 0 || !moveId.equals(history.get(ply - 1).id))) {
            throw new GameStateConflictException("Select the canonical move for this position");
        }

        Game practice = new Game();
        practice.practiceKind = Game.PracticeKind.FROM_GAME;
        practice.practiceSourceGameId = sourceId;
        practice.practiceSourcePly = ply;
        practice.practiceOriginGameId = sourceId;
        practice.practiceRequestId = requestId;
        practice.practiceRequestMoveId = moveId;
        practice.whitePracticeControl = humanWhite ? PlayerType.HUMAN : PlayerType.AI;
        practice.blackPracticeControl = humanWhite ? PlayerType.AI : PlayerType.HUMAN;
        practice.whitePlayerName = humanWhite ? "You" : modelName;
        practice.blackPlayerName = humanWhite ? modelName : "You";
        practice.whiteModelId = humanWhite ? null : modelId;
        practice.blackModelId = humanWhite ? modelId : null;
        practice.status = GameStatus.WAITING;

        Board board = chess.createBoard();
        List<Move> inherited = new ArrayList<>(ply);
        for (Move row : history.subList(0, ply)) {
            if (chess.isGameOver(board) || row.moveNumber != chess.getMoveNumber(board)
                    || !row.color.equals(chess.getSideToMove(board))) {
                throw new GameStateConflictException("The selected position cannot be replayed from this game's history");
            }
            var played = chess.validateAndApply(board, row.san);
            if (!played.valid() || !GameMoveService.samePosition(row.fen, played.fen())) {
                throw new GameStateConflictException("The selected position cannot be replayed from this game's history");
            }
            Move copy = new Move();
            copy.game = practice;
            copy.moveNumber = row.moveNumber;
            copy.color = row.color;
            copy.san = played.san();
            copy.fen = played.fen();
            copy.isBook = row.isBook;
            copy.isInherited = true;
            inherited.add(copy);
            practice.pgn = GameMoveService.appendPgn(practice.pgn, copy.san, copy.moveNumber, copy.color);
        }
        if (chess.isGameOver(board)) throw new GameStateConflictException("This position has already ended by a board rule");
        practice.currentFen = board.getFen();

        games.persist(practice);
        moves.persist(inherited);
        return DtoMapper.toDto(practice, false);
    }

    private Game retry(UUID sourceId, UUID requestId, int ply, String humanSide, UUID moveId) {
        if (requestId == null) return null;
        Game previous = games.find("practiceOriginGameId = ?1 and practiceRequestId = ?2", sourceId, requestId)
                .firstResult();
        if (previous != null && (previous.practiceSourcePly != ply
                || previous.playerType(humanSide) != PlayerType.HUMAN
                || !Objects.equals(previous.practiceRequestMoveId, moveId))) {
            throw new GameStateConflictException("This request ID was already used for a different practice position or side");
        }
        return previous;
    }
}
