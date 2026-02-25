package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import org.junit.jupiter.api.Test;

class ChessServiceTest {

    private final ChessService chessService = new ChessService();

    @Test
    void rejectsPawnForwardCaptureIntoOccupiedSquare() {
        Board board = chessService.boardFromFen("4k3/8/3q4/3P4/8/8/8/4K3 w - - 0 1");

        ChessService.ValidMoveResult result = chessService.validateAndApply(board, "d6");

        assertFalse(result.valid());
        assertNotNull(result.error());
        assertEquals(Piece.BLACK_QUEEN, board.getPiece(Square.D6));
        assertEquals(Piece.WHITE_PAWN, board.getPiece(Square.D5));
        assertEquals(Side.WHITE, board.getSideToMove());
    }

    @Test
    void acceptsPawnDiagonalCapture() {
        Board board = chessService.boardFromFen("4k3/8/4q3/3P4/8/8/8/4K3 w - - 0 1");

        ChessService.ValidMoveResult result = chessService.validateAndApply(board, "dxe6");

        assertTrue(result.valid());
        assertNotNull(result.fen());
        assertEquals(Piece.WHITE_PAWN, board.getPiece(Square.E6));
        assertEquals(Piece.NONE, board.getPiece(Square.D5));
        assertEquals(Side.BLACK, board.getSideToMove());
    }

    @Test
    void acceptsPawnForwardMoveToEmptySquare() {
        Board board = chessService.boardFromFen("4k3/8/8/3P4/8/8/8/4K3 w - - 0 1");

        ChessService.ValidMoveResult result = chessService.validateAndApply(board, "d6");

        assertTrue(result.valid());
        assertNotNull(result.fen());
        assertEquals(Piece.WHITE_PAWN, board.getPiece(Square.D6));
        assertEquals(Piece.NONE, board.getPiece(Square.D5));
        assertEquals(Side.BLACK, board.getSideToMove());
    }
}
