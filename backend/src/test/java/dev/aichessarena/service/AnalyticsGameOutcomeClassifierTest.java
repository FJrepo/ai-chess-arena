package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.TournamentParticipant;
import org.junit.jupiter.api.Test;

class AnalyticsGameOutcomeClassifierTest {

    @Test
    void reliabilityClassificationTracksTimeoutForfeitForTheAffectedSide() {
        AnalyticsGameOutcomeClassifier classifier = new AnalyticsGameOutcomeClassifier();
        Game game = new Game();
        game.status = Game.GameStatus.FORFEIT;
        game.result = Game.GameResult.BLACK_FORFEIT;
        game.resultReason = Game.ResultReason.TIMEOUT;

        AnalyticsGameOutcomeClassifier.ReliabilityOutcome white = classifier.classifyReliability(game, true);
        AnalyticsGameOutcomeClassifier.ReliabilityOutcome black = classifier.classifyReliability(game, false);

        assertTrue(white.completed());
        assertFalse(white.forfeited());
        assertTrue(black.completed());
        assertTrue(black.forfeited());
        assertTrue(black.timeoutForfeit());
    }

    @Test
    void invalidMoveForfeitIsAttributedToTheForfeitingSideOnly() {
        AnalyticsGameOutcomeClassifier classifier = new AnalyticsGameOutcomeClassifier();
        Game game = new Game();
        game.status = Game.GameStatus.FORFEIT;
        game.result = Game.GameResult.WHITE_FORFEIT;
        game.resultReason = Game.ResultReason.FORFEIT_INVALID_MOVES;

        assertTrue(classifier.classifyReliability(game, true).invalidMoveForfeit());
        assertFalse(classifier.classifyReliability(game, false).invalidMoveForfeit());

        game.resultReason = Game.ResultReason.TIMEOUT;
        assertFalse(classifier.isInvalidMoveForfeit(game, true));
    }

    @Test
    void byesAreGamesWithAMissingSideOrABye() {
        Game played = new Game();
        played.whiteParticipant = new TournamentParticipant();
        played.blackParticipant = new TournamentParticipant();
        played.whitePlayerName = "Alpha";
        played.blackPlayerName = "Beta";
        assertFalse(AnalyticsGameOutcomeClassifier.isBye(played));

        Game bye = new Game();
        bye.status = Game.GameStatus.COMPLETED;
        bye.whiteParticipant = new TournamentParticipant();
        bye.whitePlayerName = "Alpha";
        bye.blackPlayerName = "BYE";
        assertTrue(AnalyticsGameOutcomeClassifier.isBye(bye));

        Game namedBye = new Game();
        namedBye.whiteParticipant = new TournamentParticipant();
        namedBye.blackParticipant = new TournamentParticipant();
        namedBye.whitePlayerName = "BYE";
        assertTrue(AnalyticsGameOutcomeClassifier.isBye(namedBye));
    }

    @Test
    void comparisonClassificationSeparatesWinsAndForfeitsBySide() {
        AnalyticsGameOutcomeClassifier classifier = new AnalyticsGameOutcomeClassifier();
        Game game = new Game();
        game.status = Game.GameStatus.FORFEIT;
        game.result = Game.GameResult.WHITE_FORFEIT;
        game.resultReason = Game.ResultReason.FORFEIT_INVALID_MOVES;

        AnalyticsGameOutcomeClassifier.ComparisonOutcome white = classifier.classifyComparison(game, true);
        AnalyticsGameOutcomeClassifier.ComparisonOutcome black = classifier.classifyComparison(game, false);

        assertTrue(white.loss());
        assertTrue(white.forfeit());
        assertFalse(white.timeoutForfeit());
        assertTrue(black.win());
        assertTrue(black.blackGame());
        assertTrue(black.blackWin());
    }
}
