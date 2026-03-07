package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Game.GameResult;
import dev.aichessarena.entity.Game.GameStatus;
import dev.aichessarena.entity.Game.ResultReason;
import dev.aichessarena.entity.Tournament;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveRepository;
import dev.aichessarena.websocket.GameWebSocket;
import java.math.BigDecimal;
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
