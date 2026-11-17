package dev.aichessarena.service;

import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Move;
import dev.aichessarena.entity.TournamentParticipant;
import java.util.List;

/** Non-blocking adjudication using only already stored evaluations, all from White's view. */
public final class WinAdjudication {

    public static final int WINDOW_PLIES = 10;
    public static final int DECISIVE_CP = 1000;

    private WinAdjudication() {}

    /** Human games and sides without a model never adjudicate. Overrides still count as plies. */
    public static boolean enabledFor(Game game) {
        return game.tournament != null && game.tournament.adjudicationEnabled
                && isModel(game.whiteParticipant, game.whiteModelId)
                && isModel(game.blackParticipant, game.blackModelId);
    }

    private static boolean isModel(TournamentParticipant participant, String modelId) {
        return (participant == null || participant.controlType != TournamentParticipant.ControlType.HUMAN)
                && modelId != null && !modelId.isBlank();
    }

    /** Winner (WHITE/BLACK), or null, from canonical plies; missing scores never wait. */
    public static String winner(List<Move> moves) {
        moves = CanonicalPlies.of(moves);
        if (moves.size() < WINDOW_PLIES) {
            return null;
        }
        String winner = null;
        for (Move move : moves.subList(moves.size() - WINDOW_PLIES, moves.size())) {
            String side = decisiveSide(move);
            if (side == null || (winner != null && !winner.equals(side))) {
                return null;
            }
            winner = side;
        }
        return winner;
    }

    private static String decisiveSide(Move move) {
        if (move.evaluationMate != null) {
            // Unsigned mate 0 is a win for the side that just moved, as in move quality.
            if (move.evaluationMate == 0) {
                return move.color;
            }
            return move.evaluationMate > 0 ? "WHITE" : "BLACK";
        }
        if (move.evaluationCp == null) {
            return null;
        }
        if (move.evaluationCp >= DECISIVE_CP) {
            return "WHITE";
        }
        return move.evaluationCp <= -DECISIVE_CP ? "BLACK" : null;
    }
}
