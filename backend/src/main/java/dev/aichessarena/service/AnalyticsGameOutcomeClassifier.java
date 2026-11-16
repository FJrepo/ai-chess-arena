package dev.aichessarena.service;

import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Game.GameResult;
import dev.aichessarena.entity.Game.GameStatus;
import dev.aichessarena.entity.TournamentParticipant;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class AnalyticsGameOutcomeClassifier {

    /** Player name TournamentService gives the empty side of a bye. */
    static final String BYE_PLAYER_NAME = "BYE";

    static boolean isTerminal(GameStatus status) {
        return status == GameStatus.COMPLETED || status == GameStatus.FORFEIT;
    }

    /**
     * A bye (or a bracket slot nobody has reached yet): one side is empty. Byes are recorded as completed games
     * without moves and must never count as games played.
     */
    static boolean isBye(Game game) {
        return isEmptySide(game.whiteParticipant, game.whitePlayerName)
                || isEmptySide(game.blackParticipant, game.blackPlayerName);
    }

    /**
     * A side is empty when it has no participant. The name is only a fallback for a side without a participant:
     * a real participant named "BYE" still plays, while a side without a participant reference but with a real
     * player name (a game not linked to bracket participants) still counts.
     */
    private static boolean isEmptySide(TournamentParticipant participant, String playerName) {
        if (participant != null) {
            return false;
        }
        return playerName == null || playerName.isBlank() || BYE_PLAYER_NAME.equals(playerName);
    }

    boolean isActive(Game game) {
        return game.status == GameStatus.IN_PROGRESS || game.status == GameStatus.PAUSED;
    }

    ReliabilityOutcome classifyReliability(Game game, boolean isWhite) {
        boolean forfeited = isForfeitForSide(game, isWhite);
        boolean timeoutForfeit = forfeited && game.resultReason == Game.ResultReason.TIMEOUT;
        return new ReliabilityOutcome(isTerminal(game.status), forfeited, timeoutForfeit, isInvalidMoveForfeit(game, isWhite));
    }

    /** The side lost because it ran out of attempts without a legal move. */
    boolean isInvalidMoveForfeit(Game game, boolean isWhite) {
        return isForfeitForSide(game, isWhite) && game.resultReason == Game.ResultReason.FORFEIT_INVALID_MOVES;
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

    record ReliabilityOutcome(boolean completed, boolean forfeited, boolean timeoutForfeit, boolean invalidMoveForfeit) {
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
