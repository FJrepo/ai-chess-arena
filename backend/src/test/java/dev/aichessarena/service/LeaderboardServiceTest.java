package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.*;

import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Game.GameStatus;
import dev.aichessarena.entity.TournamentParticipant;
import dev.aichessarena.repository.LeaderboardRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class LeaderboardServiceTest {

    @ParameterizedTest
    @EnumSource(value = GameStatus.class, names = {"WAITING", "IN_PROGRESS", "PAUSED", "CANCELLED"})
    void unfinishedAndCancelledGamesNeverCountEvenWithAResult(GameStatus status) {
        Game game = game();
        game.status = status;
        assertFalse(LeaderboardService.isRatedGame(game));
    }

    @ParameterizedTest
    @EnumSource(Game.ResultReason.class)
    void allFinishedResultReasonsCount(Game.ResultReason reason) {
        Game game = game();
        game.resultReason = reason;
        assertTrue(LeaderboardService.isRatedGame(game));
    }

    @Test
    void byesMissingModelsHumansAndIncompleteMetadataAreExcluded() {
        Game bye = game();
        bye.blackPlayerName = "BYE";
        Game human = game();
        human.whiteParticipant = new TournamentParticipant();
        human.whiteParticipant.controlType = TournamentParticipant.ControlType.HUMAN;
        Game noModel = game();
        noModel.blackModelId = null;
        Game blankModel = game();
        blankModel.whiteModelId = "  ";
        Game noTime = game();
        noTime.completedAt = null;
        Game noResult = game();
        noResult.result = null;
        for (Game game : List.of(bye, human, noModel, blankModel, noTime, noResult)) {
            assertFalse(LeaderboardService.isRatedGame(game));
        }
    }

    @Test
    void aRealParticipantNamedByeStillCountsAndMissingParticipantReferencesAreSupported() {
        Game game = game();
        game.whiteParticipant = new TournamentParticipant();
        game.whitePlayerName = "BYE";
        assertTrue(LeaderboardService.isRatedGame(game));
    }

    @Test
    void adapterFiltersAndNormalizesBeforeReplayAndDoesNotCache() {
        Game finished = game();
        finished.whiteModelId = " a ";
        Game waiting = game();
        waiting.status = GameStatus.WAITING;
        var service = new LeaderboardService();
        service.ratings = new EloRatingService();
        service.repository = new LeaderboardRepository() {
            @Override
            public List<Game> findFinishedModelGames() {
                return List.of(finished, waiting);
            }
        };
        assertEquals(1, service.getLeaderboard(true).gamesCount());
        assertEquals(1516, EloRatingServiceTest.model(service.getLeaderboard(true), "a").rating());
        waiting.status = GameStatus.COMPLETED;
        assertEquals(2, service.getLeaderboard(true).gamesCount());
        finished.result = Game.GameResult.WHITE_FORFEIT;
        assertEquals(1, service.getLeaderboard(false).gamesCount());
    }

    private static Game game() {
        Game game = new Game();
        game.id = UUID.randomUUID();
        game.whiteModelId = "a";
        game.blackModelId = "b";
        game.whitePlayerName = "A";
        game.blackPlayerName = "B";
        game.status = GameStatus.COMPLETED;
        game.result = Game.GameResult.WHITE_WINS;
        game.completedAt = LocalDateTime.of(2026, 1, 1, 12, 0);
        return game;
    }
}
