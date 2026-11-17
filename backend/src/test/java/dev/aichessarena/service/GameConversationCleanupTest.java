package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.bhlangonijr.chesslib.Board;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Move;
import dev.aichessarena.entity.Tournament;
import dev.aichessarena.entity.TournamentParticipant;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveRepository;
import dev.aichessarena.repository.TournamentRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GameConversationCleanupTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deletionStopsTheOwnerBeforeEvictionWhileAHumanHandoffReleasesItsClaim(boolean eligibilityAlreadyRead)
            throws Exception {
        Game game = new Game();
        game.id = UUID.randomUUID();
        game.status = Game.GameStatus.IN_PROGRESS;
        game.whiteParticipant = new TournamentParticipant();
        game.whiteParticipant.controlType = TournamentParticipant.ControlType.HUMAN;
        Tournament tournament = new Tournament();
        tournament.id = UUID.randomUUID();
        game.tournament = tournament;
        AtomicBoolean deleted = new AtomicBoolean();
        CountDownLatch handoffRead = new CountDownLatch(1);
        CountDownLatch continueHandoff = new CountDownLatch(1);
        CountDownLatch handoffExited = new CountDownLatch(1);
        GameEngineService engine = new GameEngineService();
        GameEngineService.GameLoop owner = engine.tryClaimLoop(game.id);
        engine.gameRepository = new GameRepository() {
            @Override
            public Game findById(UUID id) {
                if (!eligibilityAlreadyRead) {
                    handoffRead.countDown();
                    await(continueHandoff);
                }
                return game; // The turn holds this stale entity even after deletion commits.
            }

            @Override
            public Set<UUID> findConversationEligibleIds(Set<UUID> ids) {
                Set<UUID> snapshot = deleted.get() ? Set.of() : Set.of(game.id);
                if (eligibilityAlreadyRead) {
                    handoffRead.countDown();
                    await(continueHandoff);
                }
                return snapshot;
            }

            @Override
            public void detachAll() { }

            @Override
            public List<UUID> findIdsByTournamentId(UUID id) { return List.of(game.id); }

            @Override
            public long delete(String query, Object... params) {
                deleted.set(true);
                return 1;
            }
        };
        engine.chessService = new ChessService();
        engine.moveRepository = new MoveRepository() {
            @Override
            public List<Move> findByGameId(UUID id) { return List.of(); }
        };
        engine.gameConversationService = new GameConversationService() {
            @Override
            public ConversationState initializeConversation(UUID id) {
                return new ConversationState(new Board(), new ArrayList<>(), new ArrayList<>(),
                        new PromptContext("v2", "white"), new PromptContext("v2", "black"));
            }
        };
        engine.gameTurnService = new GameTurnService() {
            @Override
            public void broadcastHumanTurnReady(UUID id, BigDecimal cost, String color) { }
        };
        AtomicBoolean evictionGated = new AtomicBoolean();
        var cache = new ConcurrentHashMap<UUID, GameConversationService.ConversationState>() {
            @Override
            public GameConversationService.ConversationState remove(Object id) {
                var removed = super.remove(id);
                if (deleted.get() && evictionGated.compareAndSet(false, true)) {
                    // Force the reported gap: eviction happens, then the handoff stores and releases its claim,
                    // all before requestStop can continue. The owner must already have been flagged here.
                    continueHandoff.countDown();
                    await(handoffExited);
                    assertFalse(owner.shouldContinue(), "flag the owner before evicting, even if it exits meanwhile");
                }
                return removed;
            }
        };
        var field = GameEngineService.class.getDeclaredField("humanConversations");
        field.setAccessible(true);
        field.set(engine, cache);
        TournamentService tournaments = new TournamentService();
        tournaments.gameRepository = engine.gameRepository;
        tournaments.gameEngineService = engine;
        tournaments.tournamentRepository = new TournamentRepository() {
            @Override
            public Tournament findByIdForUpdate(UUID id) { return tournament; }

            @Override
            public void delete(Tournament value) { }
        };

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var handoff = executor.submit(() -> {
                try {
                    engine.runGameLoop(game.id, owner, null, null);
                } finally {
                    engine.releaseLoop(game.id, owner);
                    handoffExited.countDown();
                }
            });
            try {
                await(handoffRead);
                var deletion = executor.submit(() -> tournaments.deleteTournament(tournament.id));
                assertTrue(deletion.get(10, TimeUnit.SECONDS));
                handoff.get(10, TimeUnit.SECONDS);
                assertTrue(evictionGated.get());
                assertTrue(cache.isEmpty(), "the deleted game's history must not survive the concurrent handoff");
                assertFalse(engine.isRunning(game.id));
            } finally {
                continueHandoff.countDown();
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "handoff/deletion ordering latch released");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
