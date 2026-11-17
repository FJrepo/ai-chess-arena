package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Tournament;
import dev.aichessarena.entity.Tournament.DrawPolicy;
import dev.aichessarena.entity.TournamentParticipant;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.TournamentParticipantRepository;
import dev.aichessarena.repository.TournamentRepository;
import jakarta.ws.rs.WebApplicationException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/**
 * Best-of-N rules: at most N regular games, a draw is a game without a winner, the series ends once the lead
 * cannot be caught, and a level series after N games is decided by the draw policy (REPLAY_GAME: sudden death).
 *
 * <p>Outcomes are written from the bracket's point of view: A = "Seed 1" (game-1 white) wins,
 * B = "Seed 2" wins, D = draw.
 */
class TournamentSeriesRulesTest {

    @ParameterizedTest(name = "best of {0}, {1}, games {2} -> {3} after {4} games")
    @CsvSource({
            // decided before all games are played
            "3, WHITE_ADVANCES,       AA,   Seed 1, 2",
            "3, WHITE_ADVANCES,       BB,   Seed 2, 2",
            "5, BLACK_ADVANCES,       AAA,  Seed 1, 3",
            // draws count as games: no extension beyond N
            "3, BLACK_ADVANCES,       ADD,  Seed 1, 3",
            "3, WHITE_ADVANCES,       DBD,  Seed 2, 3",
            "5, WHITE_ADVANCES,       DDADD, Seed 1, 5",
            // level after N games: the draw policy decides
            "3, WHITE_ADVANCES,       DDD,  Seed 1, 3",
            "3, BLACK_ADVANCES,       DDD,  Seed 2, 3",
            "3, HIGHER_SEED_ADVANCES, DDD,  Seed 1, 3",
            "3, WHITE_ADVANCES,       BAD,  Seed 1, 3",
            "3, BLACK_ADVANCES,       BAD,  Seed 2, 3",
            "1, WHITE_ADVANCES,       D,    Seed 1, 1",
            "1, BLACK_ADVANCES,       D,    Seed 2, 1",
            // REPLAY_GAME: sudden death until a decisive game
            "3, REPLAY_GAME,          DDDB, Seed 2, 4",
            "3, REPLAY_GAME,          ABDDDA, Seed 1, 6",
            // a single game under REPLAY_GAME is a best-of-1 series: tiebreak games until one is decisive
            "1, REPLAY_GAME,          DA,   Seed 1, 2",
            "1, REPLAY_GAME,          DDB,  Seed 2, 3",
    })
    void seriesIsDecidedByTheBestOfRules(int bestOf, DrawPolicy policy, String outcomes, String winner, int games) {
        Fixture fixture = new Fixture(bestOf, policy);

        for (char outcome : outcomes.toCharArray()) {
            assertNull(fixture.advanced(), "series decided too early");
            fixture.play(outcome);
        }

        assertEquals(winner, fixture.advanced());
        assertEquals(games, fixture.seriesGames().size());
        assertEquals(0, fixture.pendingSeriesGames(), "no extra game after the series is decided");
    }

    /** A win by resignation (lower case: a = Seed 1's opponent resigned) counts exactly like a checkmate win. */
    @ParameterizedTest(name = "best of {0}, {1}, games {2} -> {3} after {4} games")
    @CsvSource({
            "1, WHITE_ADVANCES, a,    Seed 1, 1",
            "1, WHITE_ADVANCES, b,    Seed 2, 1",
            "3, WHITE_ADVANCES, ab,   ,       2",
            "3, WHITE_ADVANCES, abB,  Seed 2, 3",
            "3, BLACK_ADVANCES, Aa,   Seed 1, 2",
            "3, WHITE_ADVANCES, bDA,  Seed 1, 3",
            // sudden-death tiebreaks are decided by a resignation too
            "1, REPLAY_GAME,    Db,   Seed 2, 2",
            "3, REPLAY_GAME,    aBDa, Seed 1, 4",
    })
    void aWinByResignationCountsLikeAnyOtherWin(int bestOf, DrawPolicy policy, String outcomes, String winner,
                                                int games) {
        Fixture fixture = new Fixture(bestOf, policy);

        for (char outcome : outcomes.toCharArray()) {
            assertNull(fixture.advanced(), "series decided too early");
            fixture.play(outcome);
        }

        assertEquals(winner, fixture.advanced());
        assertEquals(games, fixture.seriesGames().size() - (int) fixture.pendingSeriesGames());
    }

    @Test
    void randomPolicyPicksOneOfTheTwoAfterALevelSeries() {
        Fixture fixture = new Fixture(3, DrawPolicy.RANDOM_ADVANCES);

        for (char outcome : "DDD".toCharArray()) {
            fixture.play(outcome);
        }

        String advanced = fixture.advanced();
        assertEquals(true, "Seed 1".equals(advanced) || "Seed 2".equals(advanced), advanced);
        assertEquals(3, fixture.seriesGames().size());
    }

    @Test
    void replayPolicyKeepsAddingTiebreakGamesWhileDrawsContinue() {
        Fixture fixture = new Fixture(3, DrawPolicy.REPLAY_GAME);

        for (char outcome : "DDDDD".toCharArray()) {
            fixture.play(outcome);
        }

        assertNull(fixture.advanced());
        assertEquals(6, fixture.seriesGames().size());
        assertEquals(1, fixture.pendingSeriesGames());
    }

    @Test
    void aDrawnSingleGameUnderReplayKeepsItsHistoryAndGetsATiebreakGameWithSwappedColors() {
        Fixture fixture = new Fixture(1, DrawPolicy.REPLAY_GAME);
        Game drawn = fixture.slot("Semifinal", 0);

        fixture.play('D');

        assertNull(fixture.advanced());
        assertEquals(Game.GameStatus.COMPLETED, drawn.status);
        assertEquals(Game.GameResult.DRAW, drawn.result);
        List<Game> games = fixture.seriesGames();
        assertEquals(2, games.size());
        Game tiebreak = games.getLast();
        assertEquals(2, tiebreak.seriesGameNumber);
        assertEquals(1, tiebreak.seriesBestOf);
        assertEquals(Game.GameStatus.WAITING, tiebreak.status);
        assertEquals(drawn.blackPlayerName, tiebreak.whitePlayerName);
        assertEquals(drawn.whitePlayerName, tiebreak.blackPlayerName);

        fixture.play('B');

        assertEquals("Seed 2", fixture.advanced());
        assertEquals("Seed 2", tiebreak.advancedParticipant.playerName);
        assertNull(drawn.advancedParticipant);
        assertEquals(Game.GameResult.DRAW, drawn.result);
    }

    @ParameterizedTest(name = "legacy single game without series id: draw, then {0} -> {1}")
    @CsvSource({
            "WHITE_WINS,    Seed 2",
            "BLACK_WINS,    Seed 1",
            "BLACK_FORFEIT, Seed 2",
            "WHITE_FORFEIT, Seed 1",
    })
    void aDrawnSingleGameWithoutSeriesIdIsDecidedByItsFirstTiebreak(Game.GameResult tiebreakResult, String winner) {
        Fixture fixture = new Fixture(1, DrawPolicy.REPLAY_GAME);
        Game drawn = fixture.slot("Semifinal", 0);
        drawn.seriesId = null; // rows from before series support

        drawn.status = Game.GameStatus.COMPLETED;
        drawn.result = Game.GameResult.DRAW;
        fixture.service.advanceWinner(drawn.id);

        assertEquals(drawn.id, drawn.seriesId);
        List<Game> series = fixture.gameRepository.findBySeriesId(drawn.id);
        assertEquals(2, series.size());
        Game tiebreak = series.getLast();
        assertEquals("Seed 2", tiebreak.whitePlayerName);

        boolean forfeit = tiebreakResult == Game.GameResult.WHITE_FORFEIT
                || tiebreakResult == Game.GameResult.BLACK_FORFEIT;
        tiebreak.status = forfeit ? Game.GameStatus.FORFEIT : Game.GameStatus.COMPLETED;
        tiebreak.result = tiebreakResult;
        fixture.service.advanceWinner(tiebreak.id);

        assertEquals(winner, fixture.advanced());
        assertEquals(winner, tiebreak.advancedParticipant.playerName);
        List<Game> after = fixture.gameRepository.findBySeriesId(drawn.id);
        assertEquals(2, after.size(), "no third game after a decisive tiebreak");
        assertEquals(0, after.stream().filter(g -> g.status == Game.GameStatus.WAITING).count());
    }

    @Test
    void anExtraGameLeftByAnOlderVersionCannotChangeTheResult() {
        Fixture fixture = new Fixture(3, DrawPolicy.BLACK_ADVANCES);
        for (char outcome : "ADD".toCharArray()) {
            fixture.play(outcome);
        }
        assertEquals("Seed 1", fixture.advanced());

        // Older versions created a 4th game instead of deciding A-D-D; simulate that state and play it.
        Game finalGame = fixture.slot("Final", 0);
        finalGame.whiteParticipant = null;
        finalGame.whitePlayerName = null;
        Game legacy = new Game();
        Game first = fixture.slot("Semifinal", 0);
        legacy.tournament = fixture.tournament;
        legacy.bracketRound = first.bracketRound;
        legacy.bracketPosition = first.bracketPosition;
        legacy.seriesId = first.seriesId;
        legacy.seriesGameNumber = 4;
        legacy.seriesBestOf = 3;
        legacy.whiteParticipant = first.blackParticipant;
        legacy.whitePlayerName = first.blackPlayerName;
        legacy.blackParticipant = first.whiteParticipant;
        legacy.blackPlayerName = first.whitePlayerName;
        legacy.status = Game.GameStatus.WAITING;
        fixture.gameRepository.persist(legacy);

        fixture.play('B');

        assertEquals("Seed 1", fixture.advanced());
    }

    @Test
    void theGameThatDecidesALevelSeriesRecordsWhoAdvanced() {
        Fixture fixture = new Fixture(3, DrawPolicy.RANDOM_ADVANCES);
        for (char outcome : "DDD".toCharArray()) {
            fixture.play(outcome);
        }

        Game deciding = fixture.seriesGames().getLast();
        assertEquals(fixture.advanced(), deciding.advancedParticipant.playerName);
        assertNull(fixture.seriesGames().getFirst().advancedParticipant);
    }

    @Test
    void bracketCanOnlyBeGeneratedOnce() {
        Fixture fixture = new Fixture(1, DrawPolicy.WHITE_ADVANCES);
        int games = fixture.gameRepository.games.size();

        WebApplicationException error = assertThrows(WebApplicationException.class,
                () -> fixture.service.generateBracket(fixture.tournament.id));

        assertEquals(409, error.getResponse().getStatus());
        assertEquals(games, fixture.gameRepository.games.size());
    }

    @Test
    void participantsCannotBeAddedAfterTheBracketIsGenerated() {
        Fixture fixture = new Fixture(1, DrawPolicy.WHITE_ADVANCES);
        fixture.service.validateParticipantModels = false;
        TournamentParticipant late = new TournamentParticipant();
        late.playerName = "Late";
        late.modelId = "m9";

        WebApplicationException error = assertThrows(WebApplicationException.class,
                () -> fixture.service.addParticipant(fixture.tournament.id, late));

        assertEquals(409, error.getResponse().getStatus());
    }

    /** Four seeds; the series under test is Semifinal 0 (Seed 1 vs Seed 2), whose winner is seated as white in the final. */
    private static final class Fixture {
        final Tournament tournament = new Tournament();
        final InMemoryGameRepository gameRepository = new InMemoryGameRepository();
        final TournamentService service = new TournamentService();
        final UUID seriesId;

        Fixture(int bestOf, DrawPolicy policy) {
            tournament.id = UUID.randomUUID();
            tournament.matchupBestOf = bestOf;
            tournament.drawPolicy = policy;
            List<TournamentParticipant> participants = new ArrayList<>();
            for (int seed = 0; seed < 4; seed++) {
                TournamentParticipant participant = new TournamentParticipant();
                participant.id = UUID.randomUUID();
                participant.tournament = tournament;
                participant.playerName = "Seed " + (seed + 1);
                participant.modelId = "m" + seed;
                participant.seed = seed;
                participants.add(participant);
            }

            service.tournamentRepository = new SingleTournamentRepository(tournament);
            service.participantRepository = new FixedParticipantRepository(participants);
            service.gameRepository = gameRepository;
            service.generateBracket(tournament.id);
            seriesId = slot("Semifinal", 0).seriesId;
        }

        void play(char outcome) {
            Game game = seriesGames().stream()
                    .filter(g -> g.status == Game.GameStatus.WAITING)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no pending game in the series"));
            boolean seedOneIsWhite = "Seed 1".equals(game.whitePlayerName);
            game.status = Game.GameStatus.COMPLETED;
            // Lower case: the same win, by the opponent's resignation instead of checkmate.
            game.resultReason = Character.isLowerCase(outcome) ? Game.ResultReason.RESIGNATION
                    : outcome == 'D' ? Game.ResultReason.REPETITION : Game.ResultReason.CHECKMATE;
            game.result = switch (Character.toUpperCase(outcome)) {
                case 'A' -> seedOneIsWhite ? Game.GameResult.WHITE_WINS : Game.GameResult.BLACK_WINS;
                case 'B' -> seedOneIsWhite ? Game.GameResult.BLACK_WINS : Game.GameResult.WHITE_WINS;
                case 'D' -> Game.GameResult.DRAW;
                default -> throw new IllegalArgumentException("outcome " + outcome);
            };
            service.advanceWinner(game.id);
        }

        String advanced() {
            return slot("Final", 0).whitePlayerName;
        }

        List<Game> seriesGames() {
            return gameRepository.findBySeriesId(seriesId);
        }

        long pendingSeriesGames() {
            return seriesGames().stream().filter(g -> g.status == Game.GameStatus.WAITING).count();
        }

        Game slot(String round, int position) {
            return gameRepository.games.values().stream()
                    .filter(g -> round.equals(g.bracketRound) && Integer.valueOf(position).equals(g.bracketPosition)
                            && g.seriesGameNumber == 1)
                    .findFirst()
                    .orElseThrow();
        }
    }

    private static final class SingleTournamentRepository extends TournamentRepository {
        private final Tournament tournament;

        private SingleTournamentRepository(Tournament tournament) {
            this.tournament = tournament;
        }

        @Override
        public Tournament findById(UUID id) {
            return tournament.id.equals(id) ? tournament : null;
        }

        @Override
        public Tournament findByIdForUpdate(UUID id) {
            return findById(id);
        }

        @Override
        public void persist(Tournament entity) {
        }
    }

    private static final class FixedParticipantRepository extends TournamentParticipantRepository {
        private final List<TournamentParticipant> participants;

        private FixedParticipantRepository(List<TournamentParticipant> participants) {
            this.participants = participants;
        }

        @Override
        public List<TournamentParticipant> findByTournamentId(UUID tournamentId) {
            return new ArrayList<>(participants);
        }
    }

    private static final class InMemoryGameRepository extends GameRepository {
        private final Map<UUID, Game> games = new HashMap<>();

        @Override
        public void refreshForUpdate(Game game) {
        }

        @Override
        public void persist(Game entity) {
            if (entity.id == null) {
                entity.id = UUID.randomUUID();
            }
            games.put(entity.id, entity);
        }

        @Override
        public Game findByIdForUpdate(UUID id) {
            return games.get(id);
        }

        @Override
        public Game findById(UUID id) {
            return games.get(id);
        }

        @Override
        public List<Game> findByTournamentId(UUID tournamentId) {
            return games.values().stream()
                    .filter(game -> game.tournament != null && tournamentId.equals(game.tournament.id))
                    .toList();
        }

        @Override
        public List<Game> findBySeriesId(UUID seriesId) {
            return games.values().stream()
                    .filter(game -> seriesId.equals(game.seriesId))
                    .sorted(Comparator.comparingInt(game -> game.seriesGameNumber))
                    .toList();
        }
    }
}
