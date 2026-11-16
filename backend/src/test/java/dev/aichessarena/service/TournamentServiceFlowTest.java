package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.BracketDrawMode;
import dev.aichessarena.entity.Tournament;
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
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TournamentServiceFlowTest {

    @Test
    void randomDrawReassignsSeedsAndPairsTheSeededPermutation() {
        DrawFixture fixture = drawFixture(4);

        List<Game> games = fixture.service.generateBracket(fixture.tournament.id, BracketDrawMode.RANDOM);

        assertEquals(List.of("Player 3", "Player 1", "Player 0", "Player 2"), fixture.drawnNames());
        assertEquals(List.of(2, 1, 3, 0), fixture.participants.stream().map(p -> p.seed).toList());
        assertSame(fixture.participants.get(3), games.get(0).whiteParticipant);
        assertSame(fixture.participants.get(1), games.get(0).blackParticipant);
        assertSame(fixture.participants.get(0), games.get(1).whiteParticipant);
        assertSame(fixture.participants.get(2), games.get(1).blackParticipant);
        assertEquals(3, games.size());
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 5, 6, 7, 9})
    void randomDrawGivesByesToTheLowestDrawnSeedsAndAdvancesThem(int count) {
        DrawFixture fixture = drawFixture(count);

        List<Game> games = fixture.service.generateBracket(fixture.tournament.id, BracketDrawMode.RANDOM);

        List<TournamentParticipant> drawn = fixture.drawnParticipants();
        assertEquals(java.util.stream.IntStream.range(0, count).boxed().toList(),
                drawn.stream().map(p -> p.seed).toList());
        assertEquals(count, drawn.stream().map(p -> p.id).distinct().count());
        int bracketSize = Integer.highestOneBit(count - 1) * 2;
        int byes = bracketSize - count;
        assertEquals(bracketSize - 1, games.size());
        for (int i = 0; i < byes; i++) {
            Game bye = games.get(i);
            assertSame(drawn.get(i), bye.whiteParticipant);
            assertNull(bye.blackParticipant);
            assertEquals(Game.GameStatus.COMPLETED, bye.status);
            assertSame(drawn.get(i), bye.advancedParticipant);
            Game next = fixture.games.findByRoundAndPosition(
                    TournamentService.getNextRound(bye.bracketRound), i / 2);
            assertSame(drawn.get(i), i % 2 == 0 ? next.whiteParticipant : next.blackParticipant);
        }
        for (int i = byes; i < bracketSize / 2; i++) {
            int seed = byes + (i - byes) * 2;
            assertSame(drawn.get(seed), games.get(i).whiteParticipant);
            assertSame(drawn.get(seed + 1), games.get(i).blackParticipant);
        }
    }

    @Test
    void higherSeedPolicyUsesDrawnSeedsInsteadOfOriginalSeeds() {
        DrawFixture fixture = drawFixture(4);
        fixture.tournament.drawPolicy = Tournament.DrawPolicy.HIGHER_SEED_ADVANCES;
        Game game = fixture.service.generateBracket(fixture.tournament.id, BracketDrawMode.RANDOM).getFirst();
        game.status = Game.GameStatus.COMPLETED;
        game.result = Game.GameResult.DRAW;

        fixture.service.advanceWinner(game.id);

        // Player 1 was added earlier, but Player 3 drew seed 0 and wins this level series.
        assertSame(fixture.participants.get(3), game.advancedParticipant);
        assertSame(fixture.participants.get(3), fixture.games.findByRoundAndPosition("Final", 0).whiteParticipant);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void defaultAndExplicitOrderKeepOriginalSeedsAndPairings(boolean explicit) {
        DrawFixture fixture = drawFixture(4);
        // Repository order is not significant; only the original seeds decide ORDER.
        fixture.service.participantRepository = new FakeParticipantRepository(List.of(
                fixture.participants.get(3), fixture.participants.get(0),
                fixture.participants.get(2), fixture.participants.get(1)));

        List<Game> games = explicit
                ? fixture.service.generateBracket(fixture.tournament.id, BracketDrawMode.ORDER)
                : fixture.service.generateBracket(fixture.tournament.id);

        assertEquals(List.of(10, 20, 30, 40), fixture.participants.stream().map(p -> p.seed).toList());
        assertSame(fixture.participants.get(0), games.get(0).whiteParticipant);
        assertSame(fixture.participants.get(1), games.get(0).blackParticipant);
        assertSame(fixture.participants.get(2), games.get(1).whiteParticipant);
        assertSame(fixture.participants.get(3), games.get(1).blackParticipant);
    }

    @Test
    void retryCannotShuffleSeedsOrReplaceAnExistingBracket() {
        DrawFixture fixture = drawFixture(5);
        List<Game> games = fixture.service.generateBracket(fixture.tournament.id, BracketDrawMode.RANDOM);
        List<String> originalDraw = fixture.drawnNames();
        assertEquals(List.of("Player 1", "Player 2", "Player 3", "Player 4", "Player 0"), originalDraw);

        WebApplicationException error = assertThrows(WebApplicationException.class,
                () -> fixture.service.generateBracket(fixture.tournament.id, BracketDrawMode.RANDOM));

        assertEquals(409, error.getResponse().getStatus());
        assertEquals(originalDraw, fixture.drawnNames());
        assertEquals(games.size(), fixture.games.findByTournamentId(fixture.tournament.id).size());
    }

    @Test
    void randomDrawRejectsTooFewParticipantsBeforeReassigningSeeds() {
        DrawFixture fixture = drawFixture(1);

        assertThrows(IllegalArgumentException.class,
                () -> fixture.service.generateBracket(fixture.tournament.id, BracketDrawMode.RANDOM));

        assertEquals(10, fixture.participants.getFirst().seed);
        assertEquals(Tournament.TournamentStatus.CREATED, fixture.tournament.status);
        assertEquals(0, fixture.games.findByTournamentId(fixture.tournament.id).size());
    }

    private DrawFixture drawFixture(int count) {
        Tournament tournament = new Tournament();
        tournament.id = UUID.randomUUID();
        List<TournamentParticipant> participants = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            participants.add(participant(tournament, "Player " + i, "model-" + i, (i + 1) * 10));
        }
        InMemoryGameRepository games = new InMemoryGameRepository();
        TournamentService service = new TournamentService(new Random(42));
        service.tournamentRepository = new FakeTournamentRepository(tournament);
        service.participantRepository = new FakeParticipantRepository(participants);
        service.gameRepository = games;
        return new DrawFixture(service, tournament, participants, games);
    }

    private record DrawFixture(TournamentService service, Tournament tournament,
                               List<TournamentParticipant> participants, InMemoryGameRepository games) {
        List<TournamentParticipant> drawnParticipants() {
            return participants.stream().sorted(Comparator.comparingInt(p -> p.seed)).toList();
        }

        List<String> drawnNames() {
            return drawnParticipants().stream().map(p -> p.playerName).toList();
        }
    }

    @Test
    void generateBracketAdvancesByeWinnersIntoSemifinals() {
        UUID tournamentId = UUID.randomUUID();
        Tournament tournament = new Tournament();
        tournament.id = tournamentId;
        tournament.status = Tournament.TournamentStatus.CREATED;

        List<TournamentParticipant> participants = List.of(
                participant(tournament, "Seed 1", "m1", 0),
                participant(tournament, "Seed 2", "m2", 1),
                participant(tournament, "Seed 3", "m3", 2),
                participant(tournament, "Seed 4", "m4", 3),
                participant(tournament, "Seed 5", "m5", 4)
        );

        InMemoryGameRepository gameRepository = new InMemoryGameRepository();
        TournamentService service = new TournamentService();
        service.tournamentRepository = new FakeTournamentRepository(tournament);
        service.participantRepository = new FakeParticipantRepository(participants);
        service.gameRepository = gameRepository;

        List<Game> games = service.generateBracket(tournamentId);

        assertEquals(7, games.size());
        Game semifinalA = gameRepository.findByRoundAndPosition("Semifinal", 0);
        Game semifinalB = gameRepository.findByRoundAndPosition("Semifinal", 1);

        assertEquals("Seed 1", semifinalA.whitePlayerName);
        assertEquals("Seed 2", semifinalA.blackPlayerName);
        assertEquals("Seed 3", semifinalB.whitePlayerName);
        assertEquals(null, semifinalB.blackPlayerName);
    }

    @Test
    void generateBracketUsesFinalsOverrideForFinalSeries() {
        UUID tournamentId = UUID.randomUUID();
        Tournament tournament = new Tournament();
        tournament.id = tournamentId;
        tournament.status = Tournament.TournamentStatus.CREATED;
        tournament.matchupBestOf = 3;
        tournament.finalsBestOf = 5;

        List<TournamentParticipant> participants = List.of(
                participant(tournament, "Seed 1", "m1", 0),
                participant(tournament, "Seed 2", "m2", 1),
                participant(tournament, "Seed 3", "m3", 2),
                participant(tournament, "Seed 4", "m4", 3)
        );

        InMemoryGameRepository gameRepository = new InMemoryGameRepository();
        TournamentService service = new TournamentService();
        service.tournamentRepository = new FakeTournamentRepository(tournament);
        service.participantRepository = new FakeParticipantRepository(participants);
        service.gameRepository = gameRepository;

        service.generateBracket(tournamentId);

        assertEquals(3, gameRepository.findByRoundAndPosition("Semifinal", 0).seriesBestOf);
        assertEquals(3, gameRepository.findByRoundAndPosition("Semifinal", 1).seriesBestOf);
        assertEquals(5, gameRepository.findByRoundAndPosition("Final", 0).seriesBestOf);
    }

    @Test
    void advanceWinnerNeverRewritesTheSlotOfAStartedGame() {
        UUID tournamentId = UUID.randomUUID();
        Tournament tournament = new Tournament();
        tournament.id = tournamentId;
        tournament.status = Tournament.TournamentStatus.CREATED;

        List<TournamentParticipant> participants = List.of(
                participant(tournament, "Seed 1", "m1", 0),
                participant(tournament, "Seed 2", "m2", 1),
                participant(tournament, "Seed 3", "m3", 2),
                participant(tournament, "Seed 4", "m4", 3)
        );

        InMemoryGameRepository gameRepository = new InMemoryGameRepository();
        TournamentService service = new TournamentService();
        service.tournamentRepository = new FakeTournamentRepository(tournament);
        service.participantRepository = new FakeParticipantRepository(participants);
        service.gameRepository = gameRepository;

        service.generateBracket(tournamentId);
        Game semifinal = gameRepository.findByRoundAndPosition("Semifinal", 0);
        semifinal.status = Game.GameStatus.COMPLETED;
        semifinal.result = Game.GameResult.WHITE_WINS;
        service.advanceWinner(semifinal.id);

        Game finalGame = gameRepository.findByRoundAndPosition("Final", 0);
        String seatedWhite = finalGame.whitePlayerName;
        finalGame.status = Game.GameStatus.IN_PROGRESS;

        semifinal.result = Game.GameResult.BLACK_WINS;
        service.advanceWinner(semifinal.id);

        assertEquals(seatedWhite, finalGame.whitePlayerName);
    }

    @Test
    void advanceWinnerCreatesNextSeriesGameAfterDraw() {
        UUID tournamentId = UUID.randomUUID();
        Tournament tournament = new Tournament();
        tournament.id = tournamentId;
        tournament.status = Tournament.TournamentStatus.CREATED;
        tournament.matchupBestOf = 3;

        List<TournamentParticipant> participants = List.of(
                participant(tournament, "Seed 1", "m1", 0),
                participant(tournament, "Seed 2", "m2", 1)
        );

        InMemoryGameRepository gameRepository = new InMemoryGameRepository();
        TournamentService service = new TournamentService();
        service.tournamentRepository = new FakeTournamentRepository(tournament);
        service.participantRepository = new FakeParticipantRepository(participants);
        service.gameRepository = gameRepository;

        List<Game> games = service.generateBracket(tournamentId);
        Game firstGame = games.getFirst();
        firstGame.status = Game.GameStatus.COMPLETED;
        firstGame.result = Game.GameResult.DRAW;

        service.advanceWinner(firstGame.id);

        List<Game> seriesGames = gameRepository.findBySeriesId(firstGame.seriesId);
        assertEquals(2, seriesGames.size());
        Game secondGame = seriesGames.get(1);
        assertEquals(2, secondGame.seriesGameNumber);
        assertEquals("Seed 2", secondGame.whitePlayerName);
        assertEquals("Seed 1", secondGame.blackPlayerName);
        assertEquals(Tournament.TournamentStatus.IN_PROGRESS, tournament.status);
    }

    @Test
    void advanceWinnerAlternatesColorsAndCompletesSeriesBeforeAdvancing() {
        UUID tournamentId = UUID.randomUUID();
        Tournament tournament = new Tournament();
        tournament.id = tournamentId;
        tournament.status = Tournament.TournamentStatus.CREATED;
        tournament.matchupBestOf = 3;

        List<TournamentParticipant> participants = List.of(
                participant(tournament, "Seed 1", "m1", 0),
                participant(tournament, "Seed 2", "m2", 1)
        );

        InMemoryGameRepository gameRepository = new InMemoryGameRepository();
        TournamentService service = new TournamentService();
        service.tournamentRepository = new FakeTournamentRepository(tournament);
        service.participantRepository = new FakeParticipantRepository(participants);
        service.gameRepository = gameRepository;

        List<Game> games = service.generateBracket(tournamentId);
        Game firstGame = games.getFirst();
        firstGame.status = Game.GameStatus.COMPLETED;
        firstGame.result = Game.GameResult.WHITE_WINS;

        service.advanceWinner(firstGame.id);

        List<Game> seriesGames = gameRepository.findBySeriesId(firstGame.seriesId);
        assertEquals(2, seriesGames.size());
        Game secondGame = seriesGames.get(1);
        assertEquals("Seed 2", secondGame.whitePlayerName);
        assertEquals("Seed 1", secondGame.blackPlayerName);

        secondGame.status = Game.GameStatus.COMPLETED;
        secondGame.result = Game.GameResult.BLACK_WINS;

        service.advanceWinner(secondGame.id);

        assertEquals(Tournament.TournamentStatus.COMPLETED, tournament.status);
        assertNotNull(gameRepository.findById(firstGame.id));
        assertEquals(2, gameRepository.findBySeriesId(firstGame.seriesId).size());
    }

    @Test
    void removeParticipantRejectsWhenBracketAlreadyStarted() {
        UUID tournamentId = UUID.randomUUID();
        Tournament tournament = new Tournament();
        tournament.id = tournamentId;
        tournament.status = Tournament.TournamentStatus.IN_PROGRESS;

        TournamentParticipant participant = participant(tournament, "Seed 1", "m1", 0);

        TournamentService service = new TournamentService();
        service.participantRepository = new SingleParticipantRepository(participant);
        service.gameRepository = new CountingGameRepository(1);

        assertThrows(WebApplicationException.class, () -> service.removeParticipant(tournamentId, participant.id));
    }

    private TournamentParticipant participant(Tournament tournament, String name, String modelId, int seed) {
        TournamentParticipant participant = new TournamentParticipant();
        participant.id = UUID.randomUUID();
        participant.tournament = tournament;
        participant.playerName = name;
        participant.modelId = modelId;
        participant.seed = seed;
        return participant;
    }

    private static final class FakeTournamentRepository extends TournamentRepository {

        @Override
        public Tournament findByIdForUpdate(UUID id) {
            return findById(id);
        }
        private final Tournament tournament;

        private FakeTournamentRepository(Tournament tournament) {
            this.tournament = tournament;
        }

        @Override
        public Tournament findById(UUID id) {
            return tournament != null && tournament.id.equals(id) ? tournament : null;
        }

        @Override
        public void persist(Tournament entity) {
        }
    }

    private static final class FakeParticipantRepository extends TournamentParticipantRepository {
        private final List<TournamentParticipant> participants;

        private FakeParticipantRepository(List<TournamentParticipant> participants) {
            this.participants = new ArrayList<>(participants);
        }

        @Override
        public List<TournamentParticipant> findByTournamentId(UUID tournamentId) {
            return new ArrayList<>(participants);
        }
    }

    private static final class SingleParticipantRepository extends TournamentParticipantRepository {
        private final TournamentParticipant participant;

        private SingleParticipantRepository(TournamentParticipant participant) {
            this.participant = participant;
        }

        @Override
        public TournamentParticipant findById(UUID id) {
            return participant != null && participant.id.equals(id) ? participant : null;
        }
    }

    private static final class CountingGameRepository extends GameRepository {
        private final long count;

        private CountingGameRepository(long count) {
            this.count = count;
        }

        @Override
        public long count(String query, Object... params) {
            return count;
        }
    }

    private static final class InMemoryGameRepository extends GameRepository {
        private final Map<UUID, Game> games = new HashMap<>();

        @Override
        public void refreshForUpdate(Game game) {
            // In-memory games are always current.
        }

        @Override
        public void persist(Game entity) {
            if (entity.id == null) {
                entity.id = UUID.randomUUID();
            }
            games.put(entity.id, entity);
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
                    .sorted((left, right) -> Integer.compare(left.seriesGameNumber, right.seriesGameNumber))
                    .toList();
        }

        private Game findByRoundAndPosition(String round, int position) {
            return games.values().stream()
                    .filter(game -> round.equals(game.bracketRound) && Integer.valueOf(position).equals(game.bracketPosition))
                    .findFirst()
                    .orElseThrow();
        }
    }
}
