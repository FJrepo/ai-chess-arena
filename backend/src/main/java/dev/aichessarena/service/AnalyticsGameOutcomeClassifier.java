package dev.aichessarena.service;

import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Game.GameResult;
import dev.aichessarena.entity.Game.GameStatus;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class AnalyticsGameOutcomeClassifier {

    boolean isTerminal(GameStatus status) {
        return status == GameStatus.COMPLETED || status == GameStatus.FORFEIT;
    }

    boolean isActive(Game game) {
        return game.status == GameStatus.IN_PROGRESS || game.status == GameStatus.PAUSED;
    }

    ReliabilityOutcome classifyReliability(Game game, boolean isWhite) {
        boolean forfeited = isForfeitForSide(game, isWhite);
        boolean timeoutForfeit = forfeited && game.resultReason == Game.ResultReason.TIMEOUT;
        return new ReliabilityOutcome(isTerminal(game.status), forfeited, timeoutForfeit);
    }

    ComparisonOutcome classifyComparison(Game game, boolean isWhite) {
        boolean completed = isTerminal(game.status);
        boolean whiteGame = isWhite;
        boolean blackGame = !isWhite;

        if (game.result == null) {
            return new ComparisonOutcome(completed, false, false, false, false, false, whiteGame, false, blackGame, false);
        }

        return switch (game.result) {
            case WHITE_WINS -> isWhite
                    ? new ComparisonOutcome(completed, true, false, false, false, false, true, true, false, false)
                    : new ComparisonOutcome(completed, false, false, true, false, false, false, false, true, false);
            case BLACK_WINS -> isWhite
                    ? new ComparisonOutcome(completed, false, false, true, false, false, true, false, false, false)
                    : new ComparisonOutcome(completed, true, false, false, false, false, false, false, true, true);
            case DRAW ->
                    new ComparisonOutcome(completed, false, true, false, false, false, whiteGame, false, blackGame, false);
            case WHITE_FORFEIT -> isWhite
                    ? new ComparisonOutcome(completed, false, false, true, true, game.resultReason == Game.ResultReason.TIMEOUT, true, false, false, false)
                    : new ComparisonOutcome(completed, true, false, false, false, false, false, false, true, true);
            case BLACK_FORFEIT -> isWhite
                    ? new ComparisonOutcome(completed, true, false, false, false, false, true, true, false, false)
                    : new ComparisonOutcome(completed, false, false, true, true, game.resultReason == Game.ResultReason.TIMEOUT, false, false, true, false);
        };
    }

    private boolean isForfeitForSide(Game game, boolean isWhite) {
        if (game.result == null) {
            return false;
        }
        if (isWhite) {
            return game.result == GameResult.WHITE_FORFEIT;
        }
        return game.result == GameResult.BLACK_FORFEIT;
    }

    record ReliabilityOutcome(boolean completed, boolean forfeited, boolean timeoutForfeit) {
    }

    record ComparisonOutcome(
            boolean completed,
            boolean win,
            boolean draw,
            boolean loss,
            boolean forfeit,
            boolean timeoutForfeit,
            boolean whiteGame,
            boolean whiteWin,
            boolean blackGame,
            boolean blackWin
    ) {
    }
}
