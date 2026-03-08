package dev.aichessarena.service;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.move.MoveGenerator;
import com.github.bhlangonijr.chesslib.move.MoveList;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

@ApplicationScoped
public class ChessService {

    public Board createBoard() {
        return new Board();
    }

    public Board boardFromFen(String fen) {
        Board board = new Board();
        board.loadFromFen(fen);
        return board;
    }

    public List<String> getLegalMovesAsSan(Board board) {
        List<Move> legalMoves = MoveGenerator.generateLegalMoves(board);
        return legalMoves.stream()
                .map(move -> encodeToSan(board, move))
                .toList();
    }

    public ValidMoveResult validateAndApply(Board board, String san) {
        try {
            MoveList moveList = new MoveList(board.getFen());
            moveList.addSanMove(san, true, true);
            Move parsedMove = moveList.getLast();

            Move legalMove = MoveGenerator.generateLegalMoves(board).stream()
                    .filter(candidate -> sameMove(candidate, parsedMove))
                    .findFirst()
                    .orElse(null);

            if (legalMove == null) {
                return new ValidMoveResult(false, san, null, "Illegal move in current position");
            }
            if (!board.doMove(legalMove, true)) {
                return new ValidMoveResult(false, san, null, "Illegal move in current position");
            }
            return new ValidMoveResult(true, san, board.getFen(), null);
        } catch (Exception e) {
            return new ValidMoveResult(false, san, null, e.getMessage());
        }
    }

    public ValidMoveResult validateAndApplyCoordinates(Board board, String from, String to, String promotion) {
        try {
            Square fromSquare = Square.valueOf(from.trim().toUpperCase());
            Square toSquare = Square.valueOf(to.trim().toUpperCase());
            Piece promotionPiece = resolvePromotionPiece(board.getSideToMove(), promotion);

            Move requestedMove = promotionPiece != Piece.NONE
                    ? new Move(fromSquare, toSquare, promotionPiece)
                    : new Move(fromSquare, toSquare);

            Move legalMove = MoveGenerator.generateLegalMoves(board).stream()
                    .filter(candidate -> sameMove(candidate, requestedMove))
                    .findFirst()
                    .orElse(null);

            if (legalMove == null) {
                return new ValidMoveResult(false, null, null, "Illegal move in current position");
            }

            String san = encodeToSan(board, legalMove);
            if (!board.doMove(legalMove, true)) {
                return new ValidMoveResult(false, null, null, "Illegal move in current position");
            }

            return new ValidMoveResult(true, san, board.getFen(), null);
        } catch (IllegalArgumentException e) {
            return new ValidMoveResult(false, null, null, "Invalid board coordinates");
        } catch (Exception e) {
            return new ValidMoveResult(false, null, null, e.getMessage());
        }
    }

    public boolean isGameOver(Board board) {
        return board.isMated() || board.isStaleMate() || board.isRepetition()
                || board.isInsufficientMaterial() || board.getHalfMoveCounter() >= 100;
    }

    public GameEndInfo getGameEndInfo(Board board) {
        if (board.isMated()) {
            Side winner = board.getSideToMove().flip();
            return new GameEndInfo(
                    winner == Side.WHITE ? "WHITE_WINS" : "BLACK_WINS",
                    "CHECKMATE"
            );
        }
        if (board.isStaleMate()) return new GameEndInfo("DRAW", "STALEMATE");
        if (board.isRepetition()) return new GameEndInfo("DRAW", "REPETITION");
        if (board.isInsufficientMaterial()) return new GameEndInfo("DRAW", "INSUFFICIENT_MATERIAL");
        if (board.getHalfMoveCounter() >= 100) return new GameEndInfo("DRAW", "FIFTY_MOVE");
        return null;
    }

    public String getSideToMove(Board board) {
        return board.getSideToMove() == Side.WHITE ? "WHITE" : "BLACK";
    }

    public int getMoveNumber(Board board) {
        return board.getMoveCounter();
    }

    public String toAsciiBoard(Board board) {
        StringBuilder sb = new StringBuilder();
        sb.append("  a b c d e f g h\n");
        for (int rank = 7; rank >= 0; rank--) {
            sb.append((rank + 1)).append(" ");
            for (int file = 0; file < 8; file++) {
                Square sq = Square.squareAt(rank * 8 + file);
                Piece piece = board.getPiece(sq);
                sb.append(pieceToChar(piece)).append(" ");
            }
            sb.append(rank + 1).append("\n");
        }
        sb.append("  a b c d e f g h");
        return sb.toString();
    }

    private String encodeToSan(Board board, Move move) {
        MoveList ml = new MoveList(board.getFen());
        ml.add(move);
        return ml.toSanArray()[0];
    }

    private boolean sameMove(Move a, Move b) {
        return a.getFrom() == b.getFrom()
                && a.getTo() == b.getTo()
                && a.getPromotion() == b.getPromotion();
    }

    private Piece resolvePromotionPiece(Side side, String promotion) {
        if (promotion == null || promotion.isBlank()) {
            return Piece.NONE;
        }

        return switch (promotion.trim().toUpperCase()) {
            case "Q" -> side == Side.WHITE ? Piece.WHITE_QUEEN : Piece.BLACK_QUEEN;
            case "R" -> side == Side.WHITE ? Piece.WHITE_ROOK : Piece.BLACK_ROOK;
            case "B" -> side == Side.WHITE ? Piece.WHITE_BISHOP : Piece.BLACK_BISHOP;
            case "N" -> side == Side.WHITE ? Piece.WHITE_KNIGHT : Piece.BLACK_KNIGHT;
            default -> throw new IllegalArgumentException("Unsupported promotion piece");
        };
    }

    private char pieceToChar(Piece piece) {
        return switch (piece) {
            case WHITE_PAWN -> 'P';
            case WHITE_KNIGHT -> 'N';
            case WHITE_BISHOP -> 'B';
            case WHITE_ROOK -> 'R';
            case WHITE_QUEEN -> 'Q';
            case WHITE_KING -> 'K';
            case BLACK_PAWN -> 'p';
            case BLACK_KNIGHT -> 'n';
            case BLACK_BISHOP -> 'b';
            case BLACK_ROOK -> 'r';
            case BLACK_QUEEN -> 'q';
            case BLACK_KING -> 'k';
            default -> '.';
        };
    }

    public record ValidMoveResult(boolean valid, String san, String fen, String error) {}
    public record GameEndInfo(String result, String reason) {}
}
