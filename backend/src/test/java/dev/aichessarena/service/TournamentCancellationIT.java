package dev.aichessarena.service;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

import dev.aichessarena.dto.DtoMapper;
import dev.aichessarena.dto.CreateTournamentRequest;
import dev.aichessarena.dto.GameDto;
import dev.aichessarena.dto.GameDto.RunCapacity;
import dev.aichessarena.dto.TournamentDto;
import dev.aichessarena.entity.ChatMessage;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Game.GameStatus;
import dev.aichessarena.entity.Move;
import dev.aichessarena.entity.ReasoningEffort;
import dev.aichessarena.entity.Tournament;
import dev.aichessarena.entity.Tournament.TournamentStatus;
import dev.aichessarena.entity.TournamentParticipant;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveAttemptRepository;
import dev.aichessarena.repository.MoveRepository;
import dev.aichessarena.repository.TournamentRepository;
import dev.aichessarena.resource.TournamentResource;
import dev.aichessarena.websocket.GameWebSocket;
import dev.aichessarena.websocket.LiveGameClient;
import io.quarkus.arc.ClientProxy;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.common.http.TestHTTPResource;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;

/** Real transactions and running virtual-thread loops against Dev Services PostgreSQL; no provider requests. */
@QuarkusTest
class TournamentCancellationIT {
    @Inject TournamentRepository tournaments;
    @Inject GameRepository games;
    @Inject MoveRepository moves;
    @Inject MoveAttemptRepository attempts;
    @Inject TournamentService tournamentService;
    @Inject TournamentResource tournamentResource;
    @Inject GameLifecycleService lifecycle;
    @Inject GameEngineService engine;
    @Inject GameTurnService turns;
    @Inject AnalyticsWindowLoader analytics;
    @Inject AnalyticsService analyticsService;
    @Inject GameWebSocket socket;
    @TestHTTPResource("/ws/games") URI socketUri;

    @ParameterizedTest
    @EnumSource(TournamentStatus.class)
    void cancelApiStatusMatrixAndRepeatedRequests(TournamentStatus status) {
        UUID id = seed(status, GameStatus.WAITING);
        boolean allowed = status == TournamentStatus.CREATED || status == TournamentStatus.IN_PROGRESS;
        given().when().post("/api/tournaments/{id}/cancel", id).then().statusCode(allowed ? 200 : 409);
        assertEquals(allowed ? TournamentStatus.CANCELLED : status,
                QuarkusTransaction.requiringNew().call(() -> tournaments.findById(id).status));
        for (int retry = 0; retry < 2; retry++) {
            given().when().post("/api/tournaments/{id}/cancel", id).then().statusCode(409);
        }
    }

    @Test
    void unknownTournamentReturns404() {
        given().when().post("/api/tournaments/{id}/cancel", UUID.randomUUID()).then().statusCode(404);
    }

    @Test
    void createdTournamentWithoutABracketCanBeCancelled() {
        UUID id = seed(TournamentStatus.CREATED);
        TournamentDto cancelled = given().contentType("application/json").body("{}")
                .when().post("/api/tournaments/{id}/cancel", id).then().statusCode(200)
                .extract().as(TournamentDto.class);
        assertEquals("CANCELLED", cancelled.status());
        assertTrue(cancelled.games().isEmpty());
        given().contentType("application/json").when().post("/api/tournaments/{id}/generate-bracket", id)
                .then().statusCode(409);
    }

    @Test
    void cancellationEndsOnlyUnfinishedGamesPreservesEvidenceAndCostsAndAnnouncesRevisions() {
        UUID id = seed(TournamentStatus.IN_PROGRESS, GameStatus.WAITING, GameStatus.PAUSED,
                GameStatus.IN_PROGRESS, GameStatus.COMPLETED, GameStatus.FORFEIT);
        List<GameDto> before = snapshots(id);
        var costBefore = analyticsService.getTournamentCostSummary(id);
        List<String> updates = new CopyOnWriteArrayList<>();
        QuarkusMock.installMockForType(recordingSocket(updates), GameWebSocket.class);

        tournamentService.cancelTournament(id);

        List<GameDto> after = snapshots(id);
        for (int i = 0; i < before.size(); i++) {
            GameDto old = before.get(i);
            GameDto current = after.get(i);
            if (List.of("COMPLETED", "FORFEIT").contains(old.status())) {
                assertEquals(old, current, "finished game remains byte-for-byte equivalent");
            } else {
                assertEquals("CANCELLED", current.status());
                assertNull(current.result());
                assertNull(current.resultReason());
                assertNull(current.advancedParticipantId());
                assertNotNull(current.completedAt());
                assertEquals(old.statusRevision() + 1, current.statusRevision());
                assertEquals(old.moves(), current.moves());
                assertEquals(old.chatMessages(), current.chatMessages());
                assertEquals(old.pgn(), current.pgn());
                assertEquals(old.currentFen(), current.currentFen());
                assertEquals(old.totalCostUsd(), current.totalCostUsd());
                assertTrue(updates.contains(current.id() + ":CANCELLED@8"));
            }
            for (String endpoint : List.of("start", "override-move", "human-move")) {
                given().contentType("application/json").body("{\"move\":\"e4\"}")
                        .when().post("/api/games/{id}/" + endpoint, current.id()).then().statusCode(409);
            }
        }
        assertEquals(3, updates.size());
        assertEquals(costBefore, analyticsService.getTournamentCostSummary(id));
        assertEquals(2, QuarkusTransaction.requiringNew().call(() -> analytics.loadTerminalScope(30, id).games().size()));
        assertEquals(2, QuarkusTransaction.requiringNew().call(() -> analytics.loadHealthScope(30, id).games().size()));
        assertEquals(2, QuarkusTransaction.requiringNew().call(() -> analyticsService.getComparison(30, id, 0).gamesCount()));
    }

    @Test
    void rollbackRestoresTournamentAndAllGamesAndDoesNotBroadcastOrStopOwners() {
        UUID id = seed(TournamentStatus.IN_PROGRESS, GameStatus.PAUSED, GameStatus.WAITING);
        List<GameDto> before = snapshots(id);
        UUID gameId = before.getFirst().id();
        GameEngineService.GameLoop owner = engine.tryClaimLoop(gameId);
        List<String> updates = new CopyOnWriteArrayList<>();
        QuarkusMock.installMockForType(recordingSocket(updates), GameWebSocket.class);
        try {
            assertThrows(IllegalStateException.class, () -> QuarkusTransaction.requiringNew().run(() -> {
                tournamentService.cancelTournament(id);
                assertTrue(updates.isEmpty(), "status updates wait for commit");
                assertTrue(owner.shouldContinue(), "a rollback must not stop the owner");
                throw new IllegalStateException("force rollback");
            }));
            assertEquals(before, snapshots(id));
            assertEquals(TournamentStatus.IN_PROGRESS,
                    QuarkusTransaction.requiringNew().call(() -> tournaments.findById(id).status));
            assertTrue(updates.isEmpty());
            assertTrue(owner.shouldContinue());
        } finally {
            engine.releaseLoop(gameId, owner);
        }
    }

    @Test
    void cancelledTournamentRejectsEvenLegacyUnfinishedRowsAndCannotAdvanceOrCreateSeriesGames() {
        UUID id = seed(TournamentStatus.CANCELLED, GameStatus.WAITING, GameStatus.PAUSED,
                GameStatus.IN_PROGRESS, GameStatus.COMPLETED);
        List<GameDto> before = snapshots(id);
        for (GameDto game : before) {
            for (String endpoint : List.of("start", "override-move", "human-move")) {
                given().contentType("application/json").body("{\"move\":\"e4\"}")
                        .when().post("/api/games/{id}/" + endpoint, game.id()).then().statusCode(409);
            }
            lifecycle.completeGame(game.id(), new ChessService.GameEndInfo("WHITE_WINS", "CHECKMATE"));
            lifecycle.forfeitTimeout(game.id(), "WHITE", 30);
            lifecycle.forfeitInvalidMoves(game.id(), "WHITE", 3);
            assertFalse(lifecycle.adjudicateIfEligible(game.id(), game.currentFen()));
            tournamentService.advanceWinner(game.id());
        }
        assertEquals(before, snapshots(id));
        assertEquals(TournamentStatus.CANCELLED,
                QuarkusTransaction.requiringNew().call(() -> tournaments.findById(id).status));
    }

    @Test
    void concurrentCancellationCommitsExactlyOnce() throws Exception {
        UUID id = seed(TournamentStatus.IN_PROGRESS, GameStatus.PAUSED);
        CountDownLatch go = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var request = (java.util.concurrent.Callable<Integer>) () -> {
                assertTrue(go.await(10, TimeUnit.SECONDS));
                return given().when().post("/api/tournaments/{id}/cancel", id).statusCode();
            };
            var first = pool.submit(request);
            var second = pool.submit(request);
            go.countDown();
            assertEquals(List.of(200, 409), java.util.stream.Stream.of(
                    first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)).sorted().toList());
        }
        assertEquals(8, snapshots(id).getFirst().statusRevision());
    }

    @ParameterizedTest
    @EnumSource(TournamentStatus.class)
    void settingsAndEffortApiOnlyAcceptUnfinishedTournaments(TournamentStatus status) {
        UUID id = seed(status);
        UUID participantId = participantId(id);
        boolean editable = status == TournamentStatus.CREATED || status == TournamentStatus.IN_PROGRESS;
        given().contentType("application/json").body("{\"name\":\"Renamed\"}")
                .put("/api/tournaments/{id}", id).then().statusCode(editable ? 200 : 409);
        given().contentType("application/json").body("{\"reasoningEffort\":\"low\"}")
                .put("/api/tournaments/{id}/participants/{pid}", id, participantId)
                .then().statusCode(editable ? 200 : 409);
        assertEquals(status, QuarkusTransaction.requiringNew().call(() -> tournaments.findById(id).status));
        assertEquals(editable ? "Renamed" : "Cancellation test",
                QuarkusTransaction.requiringNew().call(() -> tournaments.findById(id).name));
        assertEquals(editable ? ReasoningEffort.LOW : null,
                QuarkusTransaction.requiringNew().call(() -> tournamentService.findParticipant(id, participantId).reasoningEffort));
    }

    @ParameterizedTest
    @CsvSource({"CREATED,false", "CREATED,true", "IN_PROGRESS,false", "IN_PROGRESS,true"})
    void writerWithCachedStateCannotUndoCancellation(TournamentStatus status, boolean participantUpdate) throws Exception {
        UUID id = seed(status);
        UUID participantId = participantId(id);
        LocalDateTime updatedAt = QuarkusTransaction.requiringNew().call(() -> tournaments.findById(id).updatedAt);
        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var writer = pool.submit(() -> {
                WebApplicationException rejected = assertThrows(WebApplicationException.class,
                        () -> QuarkusTransaction.requiringNew().run(() -> {
                            assertEquals(status, tournaments.findById(id).status);
                            tournamentService.findParticipant(id, participantId);
                            loaded.countDown();
                            await(cancelled);
                            updateSettings(id, participantId, participantUpdate);
                        }));
                assertEquals(409, rejected.getResponse().getStatus());
            });
            try {
                assertTrue(loaded.await(10, TimeUnit.SECONDS), "writer cached the old state");
                tournamentService.cancelTournament(id);
            } finally {
                cancelled.countDown();
            }
            writer.get(20, TimeUnit.SECONDS);
        }
        assertCancellationIsFinal(id);
        QuarkusTransaction.requiringNew().run(() -> {
            Tournament stored = tournaments.findById(id);
            assertEquals("Cancellation test", stored.name);
            assertTrue(stored.updatedAt.isAfter(updatedAt));
            assertNull(tournamentService.findParticipant(id, participantId).reasoningEffort);
        });
    }

    @ParameterizedTest
    @CsvSource({"false", "true"})
    void cancellationFollowsASettingsWriterHoldingTheRowLock(boolean participantUpdate) throws Exception {
        UUID id = seed(TournamentStatus.CREATED);
        UUID participantId = participantId(id);
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch cancelStarted = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var writer = pool.submit(() -> QuarkusTransaction.requiringNew().run(() -> {
                updateSettings(id, participantId, participantUpdate);
                assertEquals(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE,
                        tournaments.getEntityManager().getLockMode(tournaments.findById(id)));
                written.countDown();
                await(releaseWriter);
            }));
            try {
                assertTrue(written.await(10, TimeUnit.SECONDS), "settings writer holds the tournament lock");
                var cancel = pool.submit(() -> {
                    cancelStarted.countDown();
                    tournamentService.cancelTournament(id);
                });
                assertTrue(cancelStarted.await(10, TimeUnit.SECONDS));
                releaseWriter.countDown();
                writer.get(20, TimeUnit.SECONDS);
                cancel.get(20, TimeUnit.SECONDS);
            } finally {
                releaseWriter.countDown();
            }
        }
        assertCancellationIsFinal(id);
    }

    private UUID participantId(UUID id) {
        return QuarkusTransaction.requiringNew().call(() -> tournaments.findById(id).participants.getFirst().id);
    }

    private void updateSettings(UUID id, UUID participantId, boolean participantUpdate) {
        if (participantUpdate) {
            tournamentService.updateParticipantReasoningEffort(id, participantId, ReasoningEffort.LOW);
        } else {
            tournamentResource.update(id, new CreateTournamentRequest("Renamed", null, null, null, null,
                    null, null, null, null, null, null, null));
        }
    }

    private void assertCancellationIsFinal(UUID id) {
        assertEquals(TournamentStatus.CANCELLED,
                QuarkusTransaction.requiringNew().call(() -> tournaments.findById(id).status));
        given().contentType("application/json").post("/api/tournaments/{id}/generate-bracket", id).then().statusCode(409);
        assertTrue(snapshots(id).isEmpty(), "no bracket can be created after cancellation");
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "transaction ordering latch released");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    void completionWithAnAlreadyCachedGameAndTournamentCannotUndoCommittedCancellation() throws Exception {
        UUID id = seed(TournamentStatus.IN_PROGRESS, GameStatus.IN_PROGRESS);
        UUID gameId = snapshots(id).getFirst().id();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            QuarkusTransaction.requiringNew().run(() -> {
                Game cached = games.findById(gameId);
                assertEquals(TournamentStatus.IN_PROGRESS, cached.tournament.status);
                try {
                    pool.submit(() -> tournamentService.cancelTournament(id)).get(20, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                // This transaction's persistence context still has the pre-cancel entities.
                lifecycle.completeGame(gameId, new ChessService.GameEndInfo("WHITE_WINS", "CHECKMATE"));
                tournamentService.advanceWinner(gameId);
            });
        }
        GameDto cancelled = snapshots(id).getFirst();
        assertEquals("CANCELLED", cancelled.status());
        assertEquals(8, cancelled.statusRevision());
        assertNull(cancelled.result());
        assertEquals(1, snapshots(id).size(), "no replay or series game created");
    }

    @Test
    void modelAnswerArrivingAfterCancellationCannotWriteAMoveChatResultOrAdvance() throws Exception {
        UUID id = seed(TournamentStatus.IN_PROGRESS, GameStatus.WAITING);
        GameDto before = snapshots(id).getFirst();
        CountDownLatch thinking = new CountDownLatch(1);
        CountDownLatch answer = new CountDownLatch(1);
        CountDownLatch turnFinished = new CountDownLatch(1);
        GameTurnService realTurns = ClientProxy.unwrap(turns);
        QuarkusMock.installMockForType(new OpenRouterService() {
            @Override
            public LlmResponse chat(String modelId, List<ChatMsg> messages, AnswerDeadline deadline,
                                    int maxOutputTokens, ReasoningEffort effort) {
                thinking.countDown();
                try {
                    if (!answer.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("answer not released");
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                String body = "{\"move\":\"e4\",\"message\":\"Too late\"}";
                return new LlmResponse(body, 10, 5, 0.25, 1, body, false, null);
            }
        }, OpenRouterService.class);
        QuarkusMock.installMockForType(new GameTurnService() {
            @Override
            public TurnResult playTurn(TurnRequest request) {
                try {
                    return realTurns.playTurn(request);
                } finally {
                    turnFinished.countDown();
                }
            }
        }, GameTurnService.class);
        try (var client = new LiveGameClient(socketUri, before.id(), socket)) {
            engine.startGame(before.id());
            try {
                assertTrue(thinking.await(15, TimeUnit.SECONDS), "loop reached model request");
                given().when().post("/api/tournaments/{id}/cancel", id).then().statusCode(200);
                com.fasterxml.jackson.databind.JsonNode cancelledStatus;
                do {
                    cancelledStatus = client.awaitType("gameStatus");
                } while (!"CANCELLED".equals(cancelledStatus.path("status").asText()));
                assertEquals(9, cancelledStatus.path("statusRevision").asLong());
                assertTrue(QuarkusTransaction.requiringNew().call(() -> attempts.findByGameId(before.id()).isEmpty()),
                        "cancellation's initial reload cannot see the still-pending answer");
                assertFalse(engine.isRunning(before.id()));
                assertNull(engine.tryClaimLoop(before.id()), "the stopped owner still holds its claim");
            } finally {
                answer.countDown();
            }
            assertTrue(turnFinished.await(15, TimeUnit.SECONDS), "late answer processed");
            var invalidation = client.awaitType("attemptsUpdated");
            assertEquals(before.id().toString(), invalidation.path("gameId").asText());
            assertFalse(invalidation.has("statusRevision"), "cost notification is not a status change");
            // A live viewer reloading on this event sees both the attempt and its spend already committed.
            given().get("/api/games/{id}/attempts", before.id()).then().statusCode(200)
                    .body("[0].outcome", org.hamcrest.Matchers.equalTo("DISCARDED"));
            GameDto liveSnapshot = given().get("/api/games/{id}", before.id()).then().statusCode(200)
                    .extract().as(GameDto.class);
            assertEquals(9, liveSnapshot.statusRevision());
            assertEquals("CANCELLED", liveSnapshot.status());
            assertEquals(0, new BigDecimal("1.50").compareTo(liveSnapshot.totalCostUsd()));
            awaitReleased(before.id());
            GameDto cancelled = snapshots(id).getFirst();
            assertEquals("CANCELLED", cancelled.status());
            assertEquals(9, cancelled.statusRevision()); // initial 7, start, cancel; no late pause
            assertNull(cancelled.result());
            assertNull(cancelled.advancedParticipantId());
            assertEquals(before.moves(), cancelled.moves());
            assertEquals(before.chatMessages(), cancelled.chatMessages());
            assertEquals(before.currentFen(), cancelled.currentFen());
            assertEquals(1, snapshots(id).size(), "no best-of or tiebreak game created");
            assertEquals("DISCARDED", QuarkusTransaction.requiringNew().call(
                    () -> attempts.findByGameId(before.id()).getFirst().outcome.name()));
            assertEquals(0, new BigDecimal("1.50").compareTo(cancelled.totalCostUsd()),
                    "paid late answers remain costed, never counted as a played move");
        }
    }

    private void awaitReleased(UUID gameId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            GameEngineService.GameLoop claim = engine.tryClaimLoop(gameId);
            if (claim != null) {
                engine.releaseLoop(gameId, claim);
                return;
            }
            Thread.sleep(10);
        }
        fail("cancelled loop did not release ownership");
    }

    private GameWebSocket recordingSocket(List<String> updates) {
        return new GameWebSocket() {
            @Override
            public void broadcastStatusChange(UUID id, String status, String result, String reason, int totalMoves,
                                              BigDecimal cost, String detail, long revision, Instant resumeRequestedAt,
                                              RunCapacity capacity) {
                assertNull(result);
                assertNull(reason);
                assertEquals(TournamentStatus.CANCELLED, QuarkusTransaction.requiringNew().call(
                        () -> games.findById(id).tournament.status));
                updates.add(id + ":" + status + "@" + revision);
            }
        };
    }

    private List<GameDto> snapshots(UUID tournamentId) {
        return QuarkusTransaction.requiringNew().call(() -> games.findByTournamentId(tournamentId).stream()
                .sorted(java.util.Comparator.comparing(g -> g.bracketPosition))
                .map(g -> DtoMapper.toDto(g, true)).toList());
    }

    private UUID seed(TournamentStatus status, GameStatus... gameStatuses) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Tournament tournament = new Tournament();
            tournament.name = "Cancellation test";
            tournament.status = status;
            tournament.drawPolicy = Tournament.DrawPolicy.REPLAY_GAME;
            tournaments.persist(tournament);
            TournamentParticipant white = participant(tournament, "White", 0);
            TournamentParticipant black = participant(tournament, "Black", 1);
            for (int i = 0; i < gameStatuses.length; i++) {
                Game game = new Game();
                game.tournament = tournament;
                game.whiteParticipant = white;
                game.whiteModelId = white.modelId;
                game.whitePlayerName = white.playerName;
                game.blackParticipant = black;
                game.blackModelId = black.modelId;
                game.blackPlayerName = black.playerName;
                game.status = gameStatuses[i];
                game.statusRevision = 7;
                game.bracketRound = "Final";
                game.bracketPosition = i;
                game.seriesId = UUID.randomUUID();
                game.seriesBestOf = 3;
                game.totalCostUsd = new BigDecimal("1.25");
                if (game.status == GameStatus.COMPLETED || game.status == GameStatus.FORFEIT) {
                    game.result = game.status == GameStatus.COMPLETED
                            ? Game.GameResult.DRAW : Game.GameResult.BLACK_FORFEIT;
                    game.resultReason = game.status == GameStatus.COMPLETED
                            ? Game.ResultReason.STALEMATE : Game.ResultReason.TIMEOUT;
                    game.completedAt = LocalDateTime.now();
                }
                games.persist(game);
                // A waiting game has the start position, so the mid-turn answer e4 is legal.
                if (game.status != GameStatus.WAITING) {
                    Move move = new Move();
                    move.game = game;
                    move.moveNumber = 1;
                    move.color = "WHITE";
                    move.san = "e4";
                    move.modelId = white.modelId;
                    move.fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1";
                    moves.persist(move);
                    game.currentFen = move.fen;
                    game.pgn = "1. e4";
                    ChatMessage chat = new ChatMessage();
                    chat.game = game;
                    chat.moveNumber = 1;
                    chat.senderColor = "WHITE";
                    chat.senderModel = white.modelId;
                    chat.message = "Keep this evidence";
                    chat.persist();
                }
            }
            return tournament.id;
        });
    }

    private TournamentParticipant participant(Tournament tournament, String name, int seed) {
        TournamentParticipant participant = new TournamentParticipant();
        participant.tournament = tournament;
        participant.playerName = name;
        participant.modelId = "test/" + name.toLowerCase(java.util.Locale.ROOT);
        participant.seed = seed;
        participant.persist();
        return participant;
    }
}
