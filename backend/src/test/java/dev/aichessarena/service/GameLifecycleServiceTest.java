package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Game.GameResult;
import dev.aichessarena.entity.Game.GameStatus;
import dev.aichessarena.entity.Game.ResultReason;
import dev.aichessarena.entity.Tournament;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveRepository;
import dev.aichessarena.websocket.GameWebSocket;
import jakarta.ws.rs.WebApplicationException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GameLifecycleServiceTest {

    @Test
    void markGameStartedSetsStatusAndBroadcastsMoveCount() {
        Game game = new Game();
        game.id = UUID.randomUUID();
        game.status = GameStatus.WAITING;
        game.totalCostUsd = new BigDecimal("1.25");

        RecordingGameWebSocket websocket = new RecordingGameWebSocket();
        GameLifecycleService service = new GameLifecycleService();
        service.gameRepository = new FakeGameRepository(Map.of(game.id, game));
        service.moveRepository = new CountingMoveRepository(3L);
        service.gameWebSocket = websocket;
        service.tournamentService = new TournamentService();

        service.markGameStarted(game.id);

        assertEquals(GameStatus.IN_PROGRESS, game.status);
        assertNotNull(game.startedAt);
        assertEquals("IN_PROGRESS", websocket.lastStatus);
        assertEquals(3, websocket.lastTotalMoves);
        assertEquals(new BigDecimal("1.25"), websocket.lastTotalCostUsd);
    }

    @Test
    void forfeitTimeoutAdvancesTournamentWinnerAndBroadcastsReason() {
        Game game = new Game();
        game.id = UUID.randomUUID();
        game.status = GameStatus.IN_PROGRESS;
        game.tournament = new Tournament();

        RecordingGameWebSocket websocket = new RecordingGameWebSocket();
        RecordingTournamentService tournamentService = new RecordingTournamentService();
        GameLifecycleService service = new GameLifecycleService();
        service.gameRepository = new FakeGameRepository(Map.of(game.id, game));
        service.moveRepository = new CountingMoveRepository(0L);
        service.gameWebSocket = websocket;
        service.tournamentService = tournamentService;

        service.forfeitTimeout(game.id, "BLACK", 60);

        assertEquals(GameStatus.FORFEIT, game.status);
        assertEquals(GameResult.BLACK_FORFEIT, game.result);
        assertEquals(ResultReason.TIMEOUT, game.resultReason);
        assertNotNull(game.completedAt);
        assertEquals("FORFEIT", websocket.lastStatus);
        assertEquals("BLACK", websocket.lastForfeitColor);
        assertEquals("Move timed out after 60 seconds", websocket.lastForfeitReason);
        assertEquals(game.id, tournamentService.advancedGameId);
    }

    @Test
    void markGameStartedRejectsFinishedAndRunningGamesWithConflict() {
        for (GameStatus status : new GameStatus[] {GameStatus.IN_PROGRESS, GameStatus.COMPLETED, GameStatus.FORFEIT}) {
            Game game = new Game();
            game.id = UUID.randomUUID();
            game.status = status;
            GameLifecycleService service = service(game, new RecordingGameWebSocket(), new RecordingTournamentService());

            WebApplicationException error = assertThrows(WebApplicationException.class,
                    () -> service.markGameStarted(game.id));

            assertEquals(409, error.getResponse().getStatus(), "status " + status);
            assertEquals(status, game.status);
        }
    }

    @Test
    void markGameStartedResumesPausedGameAndClearsPauseDetail() {
        Game game = new Game();
        game.id = UUID.randomUUID();
        game.status = GameStatus.PAUSED;
        game.statusDetail = "OpenRouter rejected the API key (HTTP 401)";
        GameLifecycleService service = service(game, new RecordingGameWebSocket(), new RecordingTournamentService());

        service.markGameStarted(game.id);

        assertEquals(GameStatus.IN_PROGRESS, game.status);
        assertNull(game.statusDetail);
    }

    @Test
    void markGameStartedReturnsNotFoundForUnknownGame() {
        GameLifecycleService service = service(new Game(), new RecordingGameWebSocket(), new RecordingTournamentService());

        WebApplicationException error = assertThrows(WebApplicationException.class,
                () -> service.markGameStarted(UUID.randomUUID()));

        assertEquals(404, error.getResponse().getStatus());
    }

    @Test
    void pauseForProviderFailureStoresAndBroadcastsTheReason() {
        Game game = new Game();
        game.id = UUID.randomUUID();
        game.status = GameStatus.IN_PROGRESS;
        RecordingGameWebSocket websocket = new RecordingGameWebSocket();
        GameLifecycleService service = service(game, websocket, new RecordingTournamentService());

        service.pauseForProviderFailure(game.id, "OpenRouter account has insufficient credits (HTTP 402)");

        assertEquals(GameStatus.PAUSED, game.status);
        assertEquals("OpenRouter account has insufficient credits (HTTP 402)", game.statusDetail);
        assertEquals("PAUSED", websocket.lastStatus);
        assertEquals("OpenRouter account has insufficient credits (HTTP 402)", websocket.lastDetail);
    }

    @Test
    void forfeitOfAnAlreadyFinishedGameDoesNotAdvanceTheBracketAgain() {
        Game game = new Game();
        game.id = UUID.randomUUID();
        game.status = GameStatus.COMPLETED;
        game.result = GameResult.WHITE_WINS;
        game.tournament = new Tournament();
        RecordingTournamentService tournamentService = new RecordingTournamentService();
        GameLifecycleService service = service(game, new RecordingGameWebSocket(), tournamentService);

        service.forfeitTimeout(game.id, "WHITE", 60);

        assertEquals(GameStatus.COMPLETED, game.status);
        assertEquals(GameResult.WHITE_WINS, game.result);
        assertNull(tournamentService.advancedGameId);
    }

    private static GameLifecycleService service(Game game, RecordingGameWebSocket websocket,
                                                TournamentService tournamentService) {
        GameLifecycleService service = new GameLifecycleService();
        service.gameRepository = new FakeGameRepository(game.id == null ? Map.of() : Map.of(game.id, game));
        service.moveRepository = new CountingMoveRepository(0L);
        service.gameWebSocket = websocket;
        service.tournamentService = tournamentService;
        return service;
    }

    private static final class FakeGameRepository extends GameRepository {
        private final Map<UUID, Game> games;

        private FakeGameRepository(Map<UUID, Game> games) {
            this.games = new HashMap<>(games);
        }

        @Override
        public Game findById(UUID id) {
            return games.get(id);
        }

        @Override
        public void persist(Game entity) {
            games.put(entity.id, entity);
        }

        @Override
        public Game findByIdForUpdate(UUID id) {
            return games.get(id);
        }

        @Override
        public boolean markStartedIfStartable(UUID id, LocalDateTime now) {
            Game game = games.get(id);
            if (game == null || (game.status != GameStatus.WAITING && game.status != GameStatus.PAUSED)) {
                return false;
            }
            game.status = GameStatus.IN_PROGRESS;
            if (game.startedAt == null) {
                game.startedAt = now;
            }
            game.statusDetail = null;
            return true;
        }
    }

    private static final class CountingMoveRepository extends MoveRepository {
        private final long count;

        private CountingMoveRepository(long count) {
            this.count = count;
        }

        @Override
        public long count(String query, Object... params) {
            return count;
        }
    }

    private static final class RecordingTournamentService extends TournamentService {
        private UUID advancedGameId;

        @Override
        public void advanceWinner(UUID gameId) {
            advancedGameId = gameId;
        }
    }

    private static final class RecordingGameWebSocket extends GameWebSocket {
        private String lastStatus;
        private int lastTotalMoves;
        private BigDecimal lastTotalCostUsd;
        private String lastForfeitColor;
        private String lastForfeitReason;
        private String lastDetail;

        @Override
        public void broadcastGameStatus(
                UUID gameId,
                String status,
                String result,
                String resultReason,
                int totalMoves,
                BigDecimal totalCostUsd,
                String activeColor,
                java.time.Instant turnStartedAt,
                java.time.Instant turnDeadlineAt,
                String detail
        ) {
            lastStatus = status;
            lastTotalMoves = totalMoves;
            lastTotalCostUsd = totalCostUsd;
            lastDetail = detail;
        }

        @Override
        public void broadcastGameStatus(
                UUID gameId,
                String status,
                String result,
                String resultReason,
                int totalMoves,
                BigDecimal totalCostUsd
        ) {
            lastStatus = status;
            lastTotalMoves = totalMoves;
            lastTotalCostUsd = totalCostUsd;
        }

        @Override
        public void broadcastForfeit(UUID gameId, String forfeitColor, String reason) {
            lastForfeitColor = forfeitColor;
            lastForfeitReason = reason;
        }
    }
}
