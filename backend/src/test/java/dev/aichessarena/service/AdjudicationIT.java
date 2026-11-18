package dev.aichessarena.service;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;

import com.github.bhlangonijr.chesslib.Board;
import dev.aichessarena.dto.GameDto.RunCapacity;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Move;
import dev.aichessarena.entity.Tournament;
import dev.aichessarena.entity.TournamentParticipant;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveRepository;
import dev.aichessarena.repository.TournamentRepository;
import dev.aichessarena.websocket.GameWebSocket;
import io.quarkus.arc.ClientProxy;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Instant;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@QuarkusTest
class AdjudicationIT {

    private static final List<String> OPENING = List.of("e4", "e5", "Nf3", "Nc6", "Bb5", "a6", "Ba4", "Nf6", "O-O", "Be7");

    @Inject GameLifecycleService lifecycle;
    @Inject GameEngineService engine;
    @Inject GameTurnService turns;
    @Inject GameMoveService gameMoves;
    @Inject ChessService chess;
    @Inject TournamentService tournaments;
    @Inject TournamentRepository tournamentRepository;
    @Inject GameRepository gameRepository;
    @Inject MoveRepository moveRepository;
    @ConfigProperty(name = "quarkus.datasource.jdbc.url") String jdbcUrl;
    @ConfigProperty(name = "quarkus.datasource.username") String username;
    @ConfigProperty(name = "quarkus.datasource.password") String password;

    @Test
    void apiDefaultsAndUpdatesPersistAndNullOrOmittedKeepTheCurrentValue() {
        String id = createApi("{}");
        given().get("/api/tournaments/{id}", id).then().statusCode(200)
                .body("adjudicationEnabled", equalTo(false));
        for (String body : List.of("{\"adjudicationEnabled\":true}", "{\"maxRetries\":2}", "{\"adjudicationEnabled\":null}")) {
            given().contentType("application/json").body(body).put("/api/tournaments/{id}", id)
                    .then().statusCode(200).body("adjudicationEnabled", equalTo(true));
        }
        assertTrue(QuarkusTransaction.requiringNew().call(() -> tournamentRepository.findById(UUID.fromString(id)).adjudicationEnabled));
        given().contentType("application/json").body("{\"adjudicationEnabled\":false}").put("/api/tournaments/{id}", id)
                .then().statusCode(200).body("adjudicationEnabled", equalTo(false));
        String enabled = createApi("{\"adjudicationEnabled\":true}");
        given().get("/api/tournaments/{id}", enabled).then().body("adjudicationEnabled", equalTo(true));
    }

    @Test
    void invalidApiValuesAreRejectedWithoutChangingTheOption() {
        String id = createApi("{\"adjudicationEnabled\":true}");
        for (String value : List.of("\"sometimes\"", "\"true\"", "1", "0", "[]", "{}")) {
            given().contentType("application/json").body("{\"name\":\"Invalid\",\"adjudicationEnabled\":" + value + "}")
                    .post("/api/tournaments").then().statusCode(400);
            given().contentType("application/json").body("{\"adjudicationEnabled\":" + value + "}")
                    .put("/api/tournaments/{id}", id).then().statusCode(400);
        }
        given().get("/api/tournaments/{id}", id).then().body("adjudicationEnabled", equalTo(true));
    }

    @Test
    void v18DefaultsExistingAndNewTournamentsToOffWithoutChangingResults() throws Exception {
        String database = "adjudication_migration_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = DriverManager.getConnection(jdbcUrl, username, password); Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        String url = jdbcUrl.replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
        try {
            flyway(url, "17").migrate();
            try (Connection connection = DriverManager.getConnection(url, username, password); Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO tournaments (name) VALUES ('Existing')");
                statement.execute("INSERT INTO games (status, result, result_reason) VALUES ('COMPLETED', 'DRAW', 'INSUFFICIENT_MATERIAL')");
            }
            flyway(url, "18").migrate();
            try (Connection connection = DriverManager.getConnection(url, username, password); Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO tournaments (name) VALUES ('Fresh')");
                try (ResultSet rows = statement.executeQuery("SELECT adjudication_enabled FROM tournaments")) {
                    int count = 0;
                    while (rows.next()) {
                        assertEquals(Boolean.FALSE, rows.getObject(1));
                        count++;
                    }
                    assertEquals(2, count);
                }
                assertThrows(java.sql.SQLException.class,
                        () -> statement.execute("UPDATE tournaments SET adjudication_enabled = NULL"));
                try (ResultSet rows = statement.executeQuery("SELECT result, result_reason FROM games")) {
                    assertTrue(rows.next());
                    assertEquals("DRAW", rows.getString(1));
                    assertEquals("INSUFFICIENT_MATERIAL", rows.getString(2));
                }
            }
        } finally {
            try (Connection admin = DriverManager.getConnection(jdbcUrl, username, password); Statement statement = admin.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3})
    void completionUsesStatusRevisionAfterCommitBroadcastAndNormalSeriesAdvancement(int bestOf) {
        UUID gameId = fixture(bestOf, true, 10);
        UUID tournamentId = tournamentId(gameId);
        List<String> announcements = recordCompletions();
        lifecycle.markGameStarted(gameId);
        assertTrue(lifecycle.adjudicateIfEligible(gameId, fen(gameId)));
        assertFalse(lifecycle.adjudicateIfEligible(gameId, fen(gameId)), "cannot complete twice");
        assertEquals(List.of("WHITE_WINS/ADJUDICATION@2"), announcements);
        given().get("/api/games/{id}", gameId).then().statusCode(200)
                .body("status", equalTo("COMPLETED")).body("resultReason", equalTo("ADJUDICATION"))
                .body("adjudication.color", equalTo("WHITE")).body("adjudication.plies", equalTo(10))
                .body("adjudication.evaluationCp", equalTo(1230));
        if (bestOf == 3) {
            UUID second = QuarkusTransaction.requiringNew().call(() -> gameRepository.findByTournamentId(tournamentId).stream()
                    .filter(game -> game.seriesGameNumber == 2).findFirst().orElseThrow().id);
            given().get("/api/tournaments/{id}", tournamentId).then().body("status", equalTo("IN_PROGRESS"));
            prepare(second, 10, -1230); // Colors alternate; the first winner is now Black.
            lifecycle.markGameStarted(second);
            assertTrue(lifecycle.adjudicateIfEligible(second, fen(second)));
            given().get("/api/games/{id}", second).then().body("advancedPlayerName", equalTo("White Bot"))
                    .body("adjudication.color", equalTo("BLACK")).body("adjudication.evaluationCp", equalTo(1230));
            assertEquals(2, QuarkusTransaction.requiringNew().call(() -> gameRepository.findByTournamentId(tournamentId).size()));
        }
        given().get("/api/tournaments/{id}", tournamentId).then()
                .body("status", equalTo("COMPLETED")).body("winnerName", equalTo("White Bot"));
        given().queryParam("tournamentId", tournamentId).get("/api/analytics/comparison").then().statusCode(200)
                .body("models.find { it.modelId == 'test/white' }.conversion.converted", equalTo(bestOf == 1 ? 1 : 2))
                .body("models.find { it.modelId == 'test/white' }.conversion.adjudicatedWins", equalTo(bestOf == 1 ? 1 : 2));
    }

    @Test
    void disabledMissingEvaluationsHumansAndChangedStateCannotAdjudicate() {
        UUID id = fixture(1, false, 10);
        lifecycle.markGameStarted(id);
        assertFalse(lifecycle.adjudicateIfEligible(id, fen(id)));
        QuarkusTransaction.requiringNew().run(() -> {
            Game game = gameRepository.findById(id);
            game.tournament.adjudicationEnabled = true;
            moveRepository.findByGameId(id).get(3).evaluationCp = null;
        });
        assertFalse(lifecycle.adjudicateIfEligible(id, fen(id)));
        QuarkusTransaction.requiringNew().run(() -> {
            Game game = gameRepository.findById(id);
            moveRepository.findByGameId(id).get(3).evaluationCp = 1230;
            game.whiteParticipant.controlType = TournamentParticipant.ControlType.HUMAN;
        });
        assertFalse(lifecycle.adjudicateIfEligible(id, fen(id)));
        QuarkusTransaction.requiringNew().run(() -> {
            Game game = gameRepository.findById(id);
            game.whiteParticipant.controlType = TournamentParticipant.ControlType.AI;
            game.blackParticipant.controlType = TournamentParticipant.ControlType.HUMAN;
        });
        assertFalse(lifecycle.adjudicateIfEligible(id, fen(id)));
        QuarkusTransaction.requiringNew().run(() -> gameRepository.findById(id).blackParticipant.controlType = TournamentParticipant.ControlType.AI);
        assertFalse(lifecycle.adjudicateIfEligible(id, MoveQualityClassifier.START_FEN));
        lifecycle.markGamePaused(id);
        assertFalse(lifecycle.adjudicateIfEligible(id, fen(id)));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3})
    void sixPliesWithFourLegacyDuplicatesCannotAdjudicateOrAdvanceASeries(int bestOf) {
        UUID id = fixture(bestOf, true, 6);
        lifecycle.markGameStarted(id);
        String expectedFen = fen(id);
        List<String> announcements = recordCompletions();
        IllegalStateException rollback = assertThrows(IllegalStateException.class,
                () -> QuarkusTransaction.requiringNew().run(() -> {
                    // PostgreSQL rolls this DDL back, restoring the index after the test.
                    // Reproduce a legacy database where V8 skipped the index because duplicates existed.
                    moveRepository.getEntityManager().createNativeQuery("DROP INDEX uq_moves_game_ply").executeUpdate();
                    List<Move> originals = moveRepository.findByGameId(id);
                    for (Move original : originals.subList(2, 6)) {
                        Move duplicate = new Move();
                        duplicate.game = original.game;
                        duplicate.moveNumber = original.moveNumber;
                        duplicate.color = original.color;
                        duplicate.san = original.san;
                        duplicate.fen = original.fen;
                        duplicate.modelId = original.modelId;
                        duplicate.evaluationCp = original.evaluationCp;
                        duplicate.createdAt = original.createdAt.plusSeconds(1);
                        moveRepository.persist(duplicate);
                    }
                    moveRepository.flush();
                    assertEquals(10, moveRepository.findByGameId(id).size());
                    assertFalse(lifecycle.adjudicateIfEligible(id, expectedFen));
                    Game game = gameRepository.findById(id);
                    assertEquals(Game.GameStatus.IN_PROGRESS, game.status);
                    assertEquals(1, game.statusRevision);
                    assertNull(game.result);
                    assertNull(game.resultReason);
                    assertNull(game.advancedParticipant);
                    assertEquals(Tournament.TournamentStatus.IN_PROGRESS, game.tournament.status);
                    assertEquals(1, gameRepository.count("tournament.id", game.tournament.id));
                    throw new IllegalStateException("Rollback legacy duplicate fixture");
                }));
        assertEquals("Rollback legacy duplicate fixture", rollback.getMessage());
        assertTrue(announcements.isEmpty());
    }

    @Test
    void rollbackDoesNotBroadcastOrAdvance() {
        UUID id = fixture(1, true, 10);
        lifecycle.markGameStarted(id);
        List<String> announcements = recordCompletions();
        assertThrows(IllegalStateException.class, () -> QuarkusTransaction.requiringNew().run(() -> {
            assertTrue(lifecycle.adjudicateIfEligible(id, fen(id)));
            assertTrue(announcements.isEmpty());
            throw new IllegalStateException("Rollback");
        }));
        assertTrue(announcements.isEmpty());
        assertEquals(Game.GameStatus.IN_PROGRESS, status(id));
        given().get("/api/tournaments/{id}", tournamentId(id)).then().body("status", equalTo("IN_PROGRESS"));
    }

    @Test
    void gameLoopAdjudicatesBeforeCallingAModel() throws InterruptedException {
        UUID id = fixture(1, true, 10);
        AtomicInteger calls = new AtomicInteger();
        QuarkusMock.installMockForType(new GameTurnService() {
            @Override public TurnResult playTurn(TurnRequest request) {
                calls.incrementAndGet();
                return TurnResult.stopped();
            }
        }, GameTurnService.class);
        engine.startGame(id);
        awaitCompleted(id);
        assertEquals(0, calls.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void changingDuringATurnAppliesOnTheNextTurnWithoutPauseOrResume(boolean enabled) throws InterruptedException {
        UUID id = fixture(1, !enabled, 9);
        UUID tournamentId = tournamentId(id);
        AtomicInteger calls = new AtomicInteger();
        QuarkusMock.installMockForType(new OpenRouterService() {
            @Override public LlmResponse chat(String modelId, List<ChatMsg> messages, AnswerDeadline deadline, int maxTokens,
                                               dev.aichessarena.entity.ReasoningEffort effort) {
                if (calls.incrementAndGet() > 1) {
                    return LlmResponse.providerFailure(ProviderOutcome.FATAL, 402, "Script ended", 1, null);
                }
                given().contentType("application/json").body("{\"adjudicationEnabled\":" + enabled + "}")
                        .put("/api/tournaments/{id}", tournamentId).then().statusCode(200);
                return new LlmResponse("{\"move\":\"Be7\"}", 10, 5, 0.01, 1, "", false, null);
            }
        }, OpenRouterService.class);
        // Leave the callback pending. The turn wrapper stores the score before the next turn's adjudication check.
        QuarkusMock.installMockForType(new StockfishService() {
            @Override public CompletableFuture<EvalResult> evaluate(String fen, int depth) {
                return new CompletableFuture<>();
            }
        }, StockfishService.class);
        GameTurnService realTurns = ClientProxy.unwrap(turns);
        QuarkusMock.installMockForType(new GameTurnService() {
            @Override public TurnResult playTurn(TurnRequest request) {
                TurnResult result = realTurns.playTurn(request);
                // Ensure its committed evaluation exists before the next turn, independently of callback scheduling.
                UUID move = QuarkusTransaction.requiringNew().call(() -> AdjudicationIT.this.moveRepository.findByGameId(id).getLast().id);
                gameMoves.updateMoveEvaluation(move, new StockfishService.EvalResult(1230, null));
                return result;
            }
        }, GameTurnService.class);
        engine.startGame(id);
        if (enabled) {
            awaitCompleted(id);
            given().get("/api/games/{id}", id).then().statusCode(200)
                    .body("attempts[0].configurationRevision", org.hamcrest.Matchers.equalTo(1))
                    .body("configurationHistory[0].settings.adjudicationEnabled", org.hamcrest.Matchers.equalTo(false))
                    .body("adjudication.configurationRevision", org.hamcrest.Matchers.equalTo(2))
                    .body("configurationHistory[1].settings.adjudicationEnabled", org.hamcrest.Matchers.equalTo(true));
        } else {
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < deadline && (status(id) != Game.GameStatus.PAUSED || engine.isRunning(id))) {
                Thread.sleep(20);
            }
            assertEquals(Game.GameStatus.PAUSED, status(id));
        }
        assertEquals(enabled ? 1 : 2, calls.get(), "the changed option applies before the second model call");
        assertEquals(10, QuarkusTransaction.requiringNew().call(() -> moveRepository.findByGameId(id).size()));
    }

    @Test
    void missedMateDetailsReachTheApiAndLiveUpdatesWithAdjudicationDisabled() {
        UUID id = fixture(1, false, 10);
        List<UUID> moves = QuarkusTransaction.requiringNew().call(() -> moveRepository.findByGameId(id).stream()
                .map(move -> move.id).toList());
        List<dev.aichessarena.dto.MoveQualityDto> announced = new ArrayList<>();
        QuarkusMock.installMockForType(new GameWebSocket() {
            @Override public void broadcastEvaluation(UUID gameId, int number, String color, Integer cp, Integer mate,
                    List<dev.aichessarena.dto.PlyQualityDto> qualities, dev.aichessarena.dto.GameQualitySummaryDto summary) {
                qualities.stream().filter(ply -> ply.moveNumber() == 5 && "BLACK".equals(ply.color()))
                        .map(dev.aichessarena.dto.PlyQualityDto::quality).filter(java.util.Objects::nonNull)
                        .forEach(announced::add);
            }
        }, GameWebSocket.class);
        // Before Black's last move it had mate in 2; after it there is no forced mate for Black.
        gameMoves.updateMoveEvaluation(moves.get(8), new StockfishService.EvalResult(null, -2));
        assertTrue(announced.stream().anyMatch(quality -> quality.missedMate() && quality.missedMateIn() == 2));
        given().get("/api/games/{id}", id).then().statusCode(200)
                .body("moves[9].quality.missedMate", equalTo(true))
                .body("moves[9].quality.missedMateIn", equalTo(2));
    }

    private String createApi(String fields) {
        String body = fields.replaceFirst("\\{", "{\"name\":\"Adjudication API\"," ).replace(",}", "}");
        return given().contentType("application/json").body(body).post("/api/tournaments")
                .then().statusCode(201).extract().path("id");
    }

    private Flyway flyway(String url, String target) {
        return Flyway.configure().dataSource(url, username, password).locations("db/migration").target(target).load();
    }

    private UUID fixture(int bestOf, boolean enabled, int plies) {
        UUID id = QuarkusTransaction.requiringNew().call(() -> {
            Tournament tournament = new Tournament();
            tournament.name = "Adjudication fixture";
            tournament.matchupBestOf = bestOf;
            tournament.adjudicationEnabled = enabled;
            tournamentRepository.persist(tournament);
            for (int seed = 0; seed < 2; seed++) {
                TournamentParticipant participant = new TournamentParticipant();
                participant.tournament = tournament;
                participant.playerName = seed == 0 ? "White Bot" : "Black Bot";
                participant.modelId = seed == 0 ? "test/white" : "test/black";
                participant.seed = seed;
                participant.persist();
            }
            return tournaments.generateBracket(tournament.id).getFirst().id;
        });
        prepare(id, plies, 1230);
        return id;
    }

    private void prepare(UUID gameId, int plies, int cp) {
        QuarkusTransaction.requiringNew().run(() -> {
            Game game = gameRepository.findById(gameId);
            Board board = chess.createBoard();
            String pgn = "";
            for (int i = 0; i < plies; i++) {
                String color = i % 2 == 0 ? "WHITE" : "BLACK";
                var applied = chess.validateAndApply(board, OPENING.get(i));
                assertTrue(applied.valid());
                Move move = new Move();
                move.game = game;
                move.moveNumber = i / 2 + 1;
                move.color = color;
                move.san = applied.san();
                move.fen = applied.fen();
                move.modelId = "WHITE".equals(color) ? game.whiteModelId : game.blackModelId;
                move.evaluationCp = cp;
                moveRepository.persist(move);
                pgn += (pgn.isEmpty() ? "" : " ") + ("WHITE".equals(color) ? move.moveNumber + ". " : "") + applied.san();
            }
            game.currentFen = board.getFen();
            game.pgn = pgn;
        });
    }

    private List<String> recordCompletions() {
        List<String> announcements = Collections.synchronizedList(new ArrayList<>());
        QuarkusMock.installMockForType(new GameWebSocket() {
            @Override public void broadcastStatusChange(UUID id, String status, String result, String reason,
                                                        int totalMoves, BigDecimal cost, String detail, long revision, Instant resumeRequestedAt,
                                              RunCapacity capacity) {
                if (!"COMPLETED".equals(status)) return;
                // A different transaction must already see exactly the state announced after commit.
                String stored = QuarkusTransaction.requiringNew().call(() -> {
                    Game game = gameRepository.findById(id);
                    return game.result + "/" + game.resultReason + "@" + game.statusRevision;
                });
                announcements.add(stored);
                assertEquals(result + "/" + reason + "@" + revision, stored);
            }
        }, GameWebSocket.class);
        return announcements;
    }

    private UUID tournamentId(UUID id) {
        return QuarkusTransaction.requiringNew().call(() -> gameRepository.findById(id).tournament.id);
    }

    private String fen(UUID id) {
        return QuarkusTransaction.requiringNew().call(() -> gameRepository.findById(id).currentFen);
    }

    private Game.GameStatus status(UUID id) {
        return QuarkusTransaction.requiringNew().call(() -> gameRepository.findById(id).status);
    }

    private void awaitCompleted(UUID id) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline && (status(id) != Game.GameStatus.COMPLETED || engine.isRunning(id))) {
            Thread.sleep(20);
        }
        assertEquals(Game.GameStatus.COMPLETED, status(id));
    }
}
