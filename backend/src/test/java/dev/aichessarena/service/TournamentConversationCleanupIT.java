package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Tournament;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.TournamentRepository;
import io.quarkus.arc.ClientProxy;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real commit/rollback boundaries with retained human waits and claimed loop owners; no model requests. */
@QuarkusTest
class TournamentConversationCleanupIT {
    @Inject TournamentService tournaments;
    @Inject TournamentRepository tournamentRepository;
    @Inject GameRepository games;
    @Inject GameEngineService engine;
    @Inject GameConversationService conversations;
    @Inject GameLifecycleService lifecycle;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void deletionEvictsEveryDeletedGameAndStopsOwnersOnlyAfterCommit(boolean commit) throws Exception {
        UUID tournamentId = seed();
        List<UUID> ids = gameIds(tournamentId);
        UUID untouchedTournament = seed();
        UUID untouchedId = gameIds(untouchedTournament).getFirst();
        var cache = retainedConversations();
        retain(ids);
        retain(List.of(untouchedId));
        // One idle human wait and one owner: cleanup must cover both, without releasing the owner's claim.
        GameEngineService.GameLoop owner = engine.tryClaimLoop(ids.getLast());
        try {
            Runnable delete = () -> QuarkusTransaction.requiringNew().run(() -> {
                assertTrue(tournaments.deleteTournament(tournamentId));
                assertTrue(owner.shouldContinue());
                assertTrue(ids.stream().allMatch(cache::containsKey), "eviction must wait for commit");
                if (!commit) throw new IllegalStateException("force rollback");
            });
            if (commit) delete.run();
            else assertThrows(IllegalStateException.class, delete::run);

            assertEquals(!commit, owner.shouldContinue());
            assertEquals(!commit, engine.isRunning(ids.getLast()));
            for (UUID id : ids) assertEquals(!commit, cache.containsKey(id));
            assertTrue(cache.containsKey(untouchedId), "other tournaments keep their history");
            assertEquals(commit ? 0L : 2L,
                    QuarkusTransaction.requiringNew().call(() -> games.count("tournament.id", tournamentId)));
            assertEquals(!commit,
                    QuarkusTransaction.requiringNew().call(() -> tournamentRepository.findById(tournamentId) != null));
            assertNull(engine.tryClaimLoop(ids.getLast()), "stop keeps the claim until the owner exits");
        } finally {
            engine.releaseLoop(ids.getLast(), owner);
            ids.forEach(cache::remove);
            cache.remove(untouchedId);
            tournaments.deleteTournament(tournamentId);
            tournaments.deleteTournament(untouchedTournament);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"cancel", "complete", "resign", "timeout", "invalid"})
    void terminalTransitionsReleaseRetainedConversationsAfterCommitButNeverRollback(String transition) throws Exception {
        UUID tournamentId = seed();
        List<UUID> ids = gameIds(tournamentId);
        UUID id = ids.getFirst();
        var cache = retainedConversations();
        retain(ids);
        GameEngineService.GameLoop owner = engine.tryClaimLoop(id);
        try {
            Runnable change = () -> {
                switch (transition) {
                    case "cancel" -> tournaments.cancelTournament(tournamentId);
                    case "complete" -> lifecycle.completeGame(id, new ChessService.GameEndInfo("WHITE_WINS", "CHECKMATE"));
                    case "resign" -> lifecycle.resign(new GameLifecycleService.Resignation(id, 10, "WHITE",
                            "test/model", "White", null, null, new Game().currentFen));
                    case "timeout" -> lifecycle.forfeitTimeout(id, "WHITE", 10);
                    case "invalid" -> lifecycle.forfeitInvalidMoves(id, "WHITE", 3);
                    default -> throw new AssertionError(transition);
                }
                assertTrue(cache.containsKey(id), "terminal cleanup must wait for commit");
                assertTrue(owner.shouldContinue());
            };
            assertThrows(IllegalStateException.class, () -> QuarkusTransaction.requiringNew().run(() -> {
                change.run();
                throw new IllegalStateException("force rollback");
            }));
            assertTrue(cache.containsKey(id));
            assertTrue(owner.shouldContinue());
            assertEquals(Game.GameStatus.IN_PROGRESS,
                    QuarkusTransaction.requiringNew().call(() -> games.findById(id).status));

            QuarkusTransaction.requiringNew().run(change);

            assertFalse(owner.shouldContinue());
            assertFalse(cache.containsKey(id));
            assertEquals(!"cancel".equals(transition), cache.containsKey(ids.getLast()));
        } finally {
            engine.releaseLoop(id, owner);
            ids.forEach(cache::remove);
            tournaments.deleteTournament(tournamentId);
        }
    }

    private UUID seed() {
        return QuarkusTransaction.requiringNew().call(() -> {
            Tournament tournament = new Tournament();
            tournament.name = "Conversation cleanup";
            tournament.status = Tournament.TournamentStatus.IN_PROGRESS;
            tournamentRepository.persist(tournament);
            for (int i = 0; i < 2; i++) {
                Game game = new Game();
                game.tournament = tournament;
                game.status = Game.GameStatus.IN_PROGRESS;
                game.whitePlayerName = "White";
                game.blackPlayerName = "Black";
                games.persist(game);
            }
            return tournament.id;
        });
    }

    private List<UUID> gameIds(UUID tournamentId) {
        return QuarkusTransaction.requiringNew().call(() -> games.findByTournamentId(tournamentId).stream()
                .map(game -> game.id).toList());
    }

    private void retain(List<UUID> ids) throws Exception {
        var cache = retainedConversations();
        for (UUID id : ids) {
            var state = QuarkusTransaction.requiringNew().call(() -> conversations.initializeConversation(id));
            state.whiteHistory().addAll(List.of(new OpenRouterService.ChatMsg("user", "turn", true),
                    new OpenRouterService.ChatMsg("assistant", "answer")));
            cache.put(id, state);
            assertNotNull(cache.get(id));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, GameConversationService.ConversationState> retainedConversations() throws Exception {
        var field = GameEngineService.class.getDeclaredField("humanConversations");
        field.setAccessible(true);
        return (Map<UUID, GameConversationService.ConversationState>) field.get(ClientProxy.unwrap(engine));
    }
}
