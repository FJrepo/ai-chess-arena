package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StockfishServiceTest {

    @Test
    void parseScoreExtractsExactCentipawnValues() {
        StockfishService.EvalResult result = StockfishService.parseScore(
                "info depth 12 seldepth 18 multipv 1 score cp 47 nodes 12345 nps 99999"
        );

        assertEquals(47, result.cp());
        assertNull(result.mate());
    }

    @Test
    void parseScoreExtractsExactMateValues() {
        StockfishService.EvalResult result = StockfishService.parseScore(
                "info depth 16 score mate -3 nodes 999"
        );

        assertNull(result.cp());
        assertEquals(-3, result.mate());
    }

    @Test
    void parseScoreIgnoresBoundScores() {
        assertNull(StockfishService.parseScore("info depth 14 score cp 120 lowerbound nodes 1000"));
        assertNull(StockfishService.parseScore("info depth 14 score mate 4 upperbound nodes 1000"));
    }

    @Test
    void normalizeToWhitePerspectiveLeavesWhiteToMoveScoresUnchanged() {
        StockfishService.EvalResult result = new StockfishService.EvalResult(85, 3);

        StockfishService.EvalResult normalized = StockfishService.normalizeToWhitePerspective(
                result,
                "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        );

        assertEquals(85, normalized.cp());
        assertEquals(3, normalized.mate());
    }

    @Test
    void normalizeToWhitePerspectiveFlipsBlackToMoveScores() {
        StockfishService.EvalResult result = new StockfishService.EvalResult(120, -2);

        StockfishService.EvalResult normalized = StockfishService.normalizeToWhitePerspective(
                result,
                "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR b KQkq - 0 1"
        );

        assertEquals(-120, normalized.cp());
        assertEquals(2, normalized.mate());
    }

    @Test
    void detectsSideToMoveFromFen() {
        assertFalse(StockfishService.isBlackToMove("8/8/8/8/8/8/8/8 w - - 0 1"));
        assertTrue(StockfishService.isBlackToMove("8/8/8/8/8/8/8/8 b - - 0 1"));
    }
}
