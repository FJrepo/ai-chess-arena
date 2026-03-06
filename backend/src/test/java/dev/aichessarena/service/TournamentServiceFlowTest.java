package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Tournament;
import dev.aichessarena.entity.TournamentParticipant;
import dev.aichessarena.repository.ChatMessageRepository;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveRepository;
import dev.aichessarena.repository.TournamentParticipantRepository;
import dev.aichessarena.repository.TournamentRepository;
import jakarta.ws.rs.WebApplicationException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TournamentServiceFlowTest {

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
        service.moveRepository = new MoveRepository();
        service.chatMessageRepository = new ChatMessageRepository();

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
        service.moveRepository = new MoveRepository();
        service.chatMessageRepository = new ChatMessageRepository();

        service.generateBracket(tournamentId);

        assertEquals(3, gameRepository.findByRoundAndPosition("Semifinal", 0).seriesBestOf);
        assertEquals(3, gameRepository.findByRoundAndPosition("Semifinal", 1).seriesBestOf);
        assertEquals(5, gameRepository.findByRoundAndPosition("Final", 0).seriesBestOf);
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
        service.moveRepository = new MoveRepository();
        service.chatMessageRepository = new ChatMessageRepository();

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
        service.moveRepository = new MoveRepository();
        service.chatMessageRepository = new ChatMessageRepository();

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
