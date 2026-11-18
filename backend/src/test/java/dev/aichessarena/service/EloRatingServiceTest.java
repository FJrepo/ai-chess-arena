package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.*;

import dev.aichessarena.dto.LeaderboardDto;
import dev.aichessarena.entity.Game.GameResult;
import dev.aichessarena.service.EloRatingService.RatedGame;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class EloRatingServiceTest {

    private static final Instant START = Instant.parse("2026-01-01T12:00:00Z");
    private final EloRatingService service = new EloRatingService();

    @Test
    void logisticExpectationMatchesKnownExamples() {
        assertEquals(0.5, EloRatingService.expectedScore(1500, 1500), 1e-12);
        assertEquals(10.0 / 11, EloRatingService.expectedScore(1900, 1500), 1e-12);
        assertEquals(1.0 / 11, EloRatingService.expectedScore(1500, 1900), 1e-12);
        assertEquals(0.7597469266, EloRatingService.expectedScore(1700, 1500), 1e-10);
    }

    @Test
    void equalOpponentsGainAndLoseSixteenThenUseUnroundedRatings() {
        var first = game(1, GameResult.WHITE_WINS);
        assertEquals(1516, model(service.calculate(List.of(first), true), "a").rating());
        assertEquals(1484, model(service.calculate(List.of(first), true), "b").rating());
        var result = service.calculate(List.of(first, game(2, GameResult.WHITE_WINS)), true);
        assertEquals(1531, model(result, "a").rating());
        assertEquals(1469, model(result, "b").rating());
        assertEquals(List.of(1516, 1531), model(result, "a").ratingHistory().stream()
                .map(point -> point.rating()).toList());
    }

    @Test
    void drawsCountAndAnUnderdogGainsFromADraw() {
        var equal = service.calculate(List.of(game(1, GameResult.DRAW)), true);
        assertEquals(1500, model(equal, "a").rating());
        assertEquals(1, model(equal, "a").draws());
        var result = service.calculate(List.of(game(1, GameResult.WHITE_WINS), game(2, GameResult.DRAW)), true);
        assertEquals(1515, model(result, "a").rating());
        assertEquals(1485, model(result, "b").rating());
    }

    @Test
    void kSwitchesAfterThirtyGamesIndependentlyForEachSide() {
        List<RatedGame> games = new ArrayList<>();
        for (int i = 1; i <= 29; i++) {
            games.add(game(i, GameResult.DRAW));
        }
        assertEquals(32, EloRatingService.kFactor(29));
        games.add(new RatedGame(new UUID(0, 30), START.plusSeconds(30), "a", "new-30", GameResult.WHITE_WINS));
        assertEquals(1516, model(service.calculate(games, true), "a").rating());
        assertEquals(16, EloRatingService.kFactor(30));
        games.add(new RatedGame(new UUID(0, 31), START.plusSeconds(31), "a", "new-31", GameResult.WHITE_WINS));
        var result = service.calculate(games, true);
        assertEquals(1524, model(result, "a").rating());
        assertEquals(1485, model(result, "new-31").rating());
    }

    @ParameterizedTest
    @EnumSource(value = GameResult.class, names = {"WHITE_FORFEIT", "BLACK_FORFEIT"})
    void forfeitIsALossOnlyForTheForfeitingSide(GameResult result) {
        var leaderboard = service.calculate(List.of(game(1, result)), true);
        String loser = result == GameResult.WHITE_FORFEIT ? "a" : "b";
        String winner = loser.equals("a") ? "b" : "a";
        assertEquals(1484, model(leaderboard, loser).rating());
        assertEquals(1, model(leaderboard, loser).losses());
        assertEquals(1, model(leaderboard, loser).forfeits());
        assertEquals(1516, model(leaderboard, winner).rating());
        assertEquals(1, model(leaderboard, winner).wins());
        assertEquals(0, model(leaderboard, winner).forfeits());
    }

    @Test
    void excludingForfeitsReplaysRatingsCountsLastPlayedAndHistory() {
        List<RatedGame> games = List.of(game(1, GameResult.WHITE_FORFEIT), game(2, GameResult.WHITE_WINS),
                game(3, GameResult.BLACK_FORFEIT));
        var included = service.calculate(games, true);
        var excluded = service.calculate(games, false);
        assertEquals(3, included.gamesCount());
        assertEquals(1, excluded.gamesCount());
        assertFalse(excluded.includeForfeits());
        assertEquals(1516, model(excluded, "a").rating());
        assertEquals(1, model(excluded, "a").games());
        assertEquals(0, model(excluded, "a").forfeits());
        assertEquals(START.plusSeconds(2), model(excluded, "a").lastPlayed());
        assertEquals(1, model(excluded, "a").ratingHistory().size());
        assertTrue(service.calculate(List.of(game(1, GameResult.WHITE_FORFEIT)), false).models().isEmpty());
    }

    @Test
    void completionTimeThenLexicographicIdDeterminesReplayOrder() {
        var earlier = new RatedGame(UUID.fromString("7fffffff-0000-0000-0000-000000000000"), START,
                "a", "b", GameResult.WHITE_WINS);
        var laterId = new RatedGame(UUID.fromString("80000000-0000-0000-0000-000000000000"), START,
                "a", "b", GameResult.DRAW);
        var laterTime = new RatedGame(new UUID(0, 1), START.plusSeconds(1), "a", "b", GameResult.WHITE_WINS);
        var result = service.calculate(List.of(laterTime, laterId, earlier), true);
        assertEquals(List.of(earlier.id(), laterId.id(), laterTime.id()), model(result, "a").ratingHistory()
                .stream().map(point -> point.gameId()).toList());
        assertEquals(service.calculate(List.of(earlier, laterId, laterTime), true), result);
    }

    @Test
    void historyIsCappedWithoutDiscardingEarlierRatingUpdates() {
        List<RatedGame> games = new ArrayList<>();
        for (int i = 1; i <= 70; i++) {
            games.add(game(i, GameResult.WHITE_WINS));
        }
        var row = model(service.calculate(games, true), "a");
        assertEquals(70, row.games());
        assertEquals(50, row.ratingHistory().size());
        assertEquals(new UUID(0, 21), row.ratingHistory().getFirst().gameId());
        assertEquals(row.rating(), row.ratingHistory().getLast().rating());
        assertFalse(row.provisional());
        assertNotEquals(model(service.calculate(games.subList(20, 70), true), "a").rating(), row.rating());
    }

    @Test
    void provisionalEndsAtTenRatedAppearancesAndTiesSortByModelId() {
        var nine = Collections.nCopies(9, GameResult.DRAW);
        List<RatedGame> games = new ArrayList<>();
        for (int i = 0; i < nine.size(); i++) {
            games.add(game(i + 1, nine.get(i)));
        }
        assertTrue(model(service.calculate(games, true), "a").provisional());
        games.add(game(10, GameResult.DRAW));
        var result = service.calculate(games, true);
        assertFalse(model(result, "a").provisional());
        assertEquals(List.of("a", "b"), result.models().stream().map(row -> row.modelId()).toList());
    }

    @Test
    void selfPlayCountsBothAppearancesButHasOneUnchangedHistoryPoint() {
        var result = service.calculate(List.of(new RatedGame(new UUID(0, 1), START, "a", "a",
                GameResult.WHITE_FORFEIT)), true);
        var row = model(result, "a");
        assertEquals(1500, row.rating());
        assertEquals(2, row.games());
        assertEquals(1, row.wins());
        assertEquals(1, row.losses());
        assertEquals(1, row.forfeits());
        assertEquals(1, row.ratingHistory().size());
    }

    @Test
    void duplicateGameSnapshotsDoNotChangeRatingAndAliasesRemainSeparate() {
        var game = new RatedGame(new UUID(0, 1), START, "~anthropic/example", "anthropic/example", GameResult.DRAW);
        var result = service.calculate(List.of(game, game), true);
        assertEquals(1, result.gamesCount());
        assertEquals(2, result.models().size());
        assertTrue(model(result, "~anthropic/example").alias());
        assertFalse(model(result, "anthropic/example").alias());
        assertEquals("example", model(result, "~anthropic/example").displayName());
    }

    @Test
    void emptyInputHasNoUnplayedModels() {
        assertEquals(new LeaderboardDto(true, 0, List.of()), service.calculate(List.of(), true));
    }

    private static RatedGame game(int number, GameResult result) {
        return new RatedGame(new UUID(0, number), START.plusSeconds(number), "a", "b", result);
    }

    static LeaderboardDto.Model model(LeaderboardDto result, String id) {
        return result.models().stream().filter(row -> row.modelId().equals(id)).findFirst().orElseThrow();
    }
}
