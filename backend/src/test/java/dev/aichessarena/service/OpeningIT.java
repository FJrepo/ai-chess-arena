package dev.aichessarena.service;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.aichessarena.dto.DtoMapper;
import dev.aichessarena.dto.GameDto;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Move;
import dev.aichessarena.entity.OpeningMode;
import dev.aichessarena.entity.Tournament;
import dev.aichessarena.entity.TournamentParticipant;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveAttemptRepository;
import dev.aichessarena.repository.TournamentRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

@QuarkusTest
class OpeningIT {
    @Inject OpeningBookService book;
    @Inject TournamentService tournaments;
    @Inject TournamentRepository tournamentRepository;
    @Inject GameRepository games;
    @Inject GameLifecycleService lifecycle;
    @Inject MoveAttemptRepository attempts;
    @ConfigProperty(name = "quarkus.datasource.jdbc.url") String jdbcUrl;
    @ConfigProperty(name = "quarkus.datasource.username") String username;
    @ConfigProperty(name = "quarkus.datasource.password") String password;

    @Test
    void catalogAndApiValidateCreateUpdateAndPreserveOmittedSettings() {
        given().get("/api/openings").then().statusCode(200)
                .body("size()", equalTo(42)).body("find { it.id == 'ruy-lopez' }.eco", equalTo("C60"));
        String id = given().contentType("application/json").body("{\"name\":\"Openings\"}")
                .post("/api/tournaments").then().statusCode(201)
                .body("openingMode", equalTo("STANDARD")).extract().path("id");
        for (String invalid : List.of(
                "{\"openingMode\":\"unknown\"}", "{\"openingMode\":\"\"}",
                "{\"openingMode\":\"FIXED\"}", "{\"openingMode\":\"FIXED\",\"openingId\":\"unknown\"}",
                "{\"openingMode\":\"RANDOM_BOOK\",\"openingId\":\"ruy-lopez\"}",
                "{\"openingId\":\"ruy-lopez\"}", "{\"openingMode\":12}", "{\"openingMode\":{}}")) {
            given().contentType("application/json").body(invalid.replaceFirst("\\{", "{\"name\":\"Invalid\","))
                    .post("/api/tournaments").then().statusCode(400);
            given().contentType("application/json").body(invalid).put("/api/tournaments/{id}", id)
                    .then().statusCode(400);
        }
        given().get("/api/tournaments/{id}", id).then().body("openingMode", equalTo("STANDARD"));
        given().contentType("application/json").body("{\"openingMode\":\"fixed\",\"openingId\":\"ruy-lopez\"}")
                .put("/api/tournaments/{id}", id).then().statusCode(200)
                .body("openingMode", equalTo("FIXED")).body("openingId", equalTo("ruy-lopez"));
        for (String body : List.of("{}", "{\"openingMode\":null,\"openingId\":null}")) {
            given().contentType("application/json").body(body).put("/api/tournaments/{id}", id)
                    .then().statusCode(200).body("openingId", equalTo("ruy-lopez"));
        }
        given().contentType("application/json").body("{\"openingId\":\"italian\"}")
                .put("/api/tournaments/{id}", id).then().statusCode(200).body("openingId", equalTo("italian"));
        given().contentType("application/json").body("{\"openingMode\":\"RANDOM_BOOK\"}")
                .put("/api/tournaments/{id}", id).then().statusCode(200).body("openingId", nullValue());
        given().contentType("application/json").body("{\"name\":\"Fixed\",\"openingMode\":\"FIXED\",\"openingId\":\"italian\"}")
                .post("/api/tournaments").then().statusCode(201).body("openingId", equalTo("italian"));
    }

    @Test
    void seriesReuseStoredLineAcrossColorSwapAndSettingsChangesButTiebreakChoosesAgain() {
        UUID tournamentId = createTournament(2, OpeningMode.RANDOM_BOOK, null, 3);
        List<GameDto> first = bracket(tournamentId);
        GameDto one = first.getFirst();
        assertNotNull(one.openingId());
        String other = "italian".equals(one.openingId()) ? "ruy-lopez" : "italian";
        configure(tournamentId, OpeningMode.FIXED, other);
        assertEquals(one.openingId(), game(one.id()).openingId(), "existing WAITING game is immutable");
        GameDto two = finishDrawAndAdvance(one.id());
        assertEquals(one.openingId(), two.openingId());
        assertEquals(one.openingName(), two.openingName());
        assertEquals(one.pgn(), two.pgn());
        assertEquals(one.currentFen(), two.currentFen());
        assertEquals(one.whiteParticipantId(), two.blackParticipantId());
        assertEquals(one.blackParticipantId(), two.whiteParticipantId());
        GameDto three = finishDrawAndAdvance(two.id());
        assertEquals(other, three.openingId());
        String tiebreakId = "italian".equals(other) ? "caro-kann" : "italian";
        configure(tournamentId, OpeningMode.FIXED, tiebreakId);
        GameDto four = finishDrawAndAdvance(three.id());
        assertEquals(4, four.seriesGameNumber());
        assertEquals(tiebreakId, four.openingId(), "tiebreak does not reuse game 3 despite reversed colors");
    }

    @Test
    void standardPartnerRemainsStandardAfterBookIsEnabledAndByesHaveNoBookRows() {
        UUID tournamentId = createTournament(2, OpeningMode.STANDARD, null, 3);
        GameDto one = bracket(tournamentId).getFirst();
        configure(tournamentId, OpeningMode.FIXED, "italian");
        GameDto two = finishDrawAndAdvance(one.id());
        assertNull(two.openingId());
        assertTrue(two.moves().isEmpty());
        assertEquals("italian", finishDrawAndAdvance(two.id()).openingId());

        UUID withBye = createTournament(3, OpeningMode.FIXED, "ruy-lopez", 1);
        List<GameDto> bracket = bracket(withBye);
        assertTrue(bracket.stream().anyMatch(g -> "BYE".equals(g.blackPlayerName())));
        for (GameDto game : bracket) {
            if ("BYE".equals(game.blackPlayerName())) {
                assertNull(game.openingId());
                assertTrue(game.moves().isEmpty());
            } else {
                assertEquals("ruy-lopez", game.openingId(), "future-round placeholders snapshot on creation too");
            }
        }
    }

    @Test
    void persistedBookRowsAreVisibleAndStartingOrResumingDoesNotDuplicateOrChargeThem() {
        UUID id = bracket(createTournament(2, OpeningMode.FIXED, "ruy-lopez", 1)).getFirst().id();
        given().get("/api/games/{id}", id).then().statusCode(200)
                .body("openingName", equalTo("Ruy Lopez")).body("openingEco", equalTo("C60"))
                .body("moves.size()", equalTo(5)).body("moves.isBook", everyItem(equalTo(true)))
                .body("qualitySummary.white.analyzedMoves", equalTo(0));
        given().get("/api/games/{id}/pgn", id).then().statusCode(200)
                .body(containsString("1. e4 {book}"))
                .body(containsString("1... e5 {book}"))
                .body(containsString("3. Bb5 {book}"));
        QuarkusTransaction.requiringNew().run(() -> lifecycle.markGameStarted(id));
        GameDto started = game(id);
        configure(started.tournamentId(), OpeningMode.FIXED, "italian");
        QuarkusTransaction.requiringNew().run(() -> lifecycle.markGamePaused(id));
        QuarkusTransaction.requiringNew().run(() -> lifecycle.markGameStarted(id));
        GameDto resumed = game(id);
        assertEquals(started.currentFen(), resumed.currentFen());
        assertEquals(started.pgn(), resumed.pgn());
        assertEquals(5, resumed.moves().size());
        assertEquals(0, resumed.totalCostUsd().signum());
        assertEquals(0, QuarkusTransaction.requiringNew().call(() -> attempts.count("game.id", id)));
    }

    @Test
    void v19AddsSafeDefaultsWithoutChangingPersistedPositionsResultsOrUsage() throws Exception {
        String database = "opening_migration_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = DriverManager.getConnection(jdbcUrl, username, password); Statement sql = admin.createStatement()) {
            sql.execute("CREATE DATABASE " + database);
        }
        String url = jdbcUrl.replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
        try {
            migrate(url, "18");
            try (Connection connection = DriverManager.getConnection(url, username, password); Statement sql = connection.createStatement()) {
                sql.execute("INSERT INTO tournaments (name) VALUES ('Existing')");
                sql.execute("INSERT INTO games (status, result, pgn, current_fen, total_cost_usd) "
                        + "VALUES ('COMPLETED', 'DRAW', '1. e4', 'stored-position', 0.123)");
                sql.execute("INSERT INTO moves (game_id, move_number, color, san, fen, model_id, cost_usd) "
                        + "SELECT id, 1, 'WHITE', 'e4', 'stored-position', 'model-a', 0.123 FROM games");
            }
            migrate(url, "19");
            try (Connection connection = DriverManager.getConnection(url, username, password); Statement sql = connection.createStatement()) {
                sql.execute("INSERT INTO tournaments (name) VALUES ('Fresh')");
                try (ResultSet rows = sql.executeQuery("SELECT opening_mode, opening_id FROM tournaments")) {
                    int count = 0;
                    while (rows.next()) {
                        assertEquals("STANDARD", rows.getString(1));
                        assertNull(rows.getString(2));
                        count++;
                    }
                    assertEquals(2, count);
                }
                try (ResultSet rows = sql.executeQuery("SELECT result, current_fen, pgn, opening_id FROM games")) {
                    assertTrue(rows.next());
                    assertEquals("DRAW", rows.getString(1));
                    assertEquals("stored-position", rows.getString(2));
                    assertEquals("1. e4", rows.getString(3));
                    assertNull(rows.getString(4));
                }
                try (ResultSet rows = sql.executeQuery("SELECT is_book, model_id, cost_usd FROM moves")) {
                    assertTrue(rows.next());
                    assertEquals(Boolean.FALSE, rows.getObject(1));
                    assertEquals("model-a", rows.getString(2));
                    assertEquals(0, new java.math.BigDecimal("0.123").compareTo(rows.getBigDecimal(3)));
                }
                for (String update : List.of("opening_mode = NULL", "opening_mode = 'UNKNOWN'", "opening_mode = 'FIXED'")) {
                    assertThrows(java.sql.SQLException.class, () -> sql.execute("UPDATE tournaments SET " + update));
                }
            }
        } finally {
            try (Connection admin = DriverManager.getConnection(jdbcUrl, username, password); Statement sql = admin.createStatement()) {
                sql.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
            }
        }
    }

    private void migrate(String url, String target) {
        Flyway.configure().dataSource(url, username, password).locations("classpath:db/migration").target(target).load().migrate();
    }

    private UUID createTournament(int players, OpeningMode mode, String openingId, int bestOf) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Tournament tournament = new Tournament();
            tournament.name = "Opening tests";
            tournament.openingMode = mode;
            tournament.openingId = openingId;
            tournament.matchupBestOf = bestOf;
            tournament.drawPolicy = Tournament.DrawPolicy.REPLAY_GAME;
            for (int i = 0; i < players; i++) {
                TournamentParticipant participant = new TournamentParticipant();
                participant.tournament = tournament;
                participant.playerName = "Player " + i;
                participant.modelId = "model-" + i;
                participant.seed = i;
                tournament.participants.add(participant);
            }
            tournamentRepository.persist(tournament);
            return tournament.id;
        });
    }

    private List<GameDto> bracket(UUID tournamentId) {
        return QuarkusTransaction.requiringNew().call(() -> tournaments.generateBracket(tournamentId).stream()
                .map(game -> DtoMapper.toDto(game, true)).toList());
    }

    private GameDto game(UUID id) {
        return QuarkusTransaction.requiringNew().call(() -> DtoMapper.toDto(games.findById(id), true));
    }

    private void configure(UUID id, OpeningMode mode, String openingId) {
        QuarkusTransaction.requiringNew().run(() -> {
            Tournament tournament = tournamentRepository.findByIdForUpdate(id);
            book.configure(tournament, mode.name(), openingId);
        });
    }

    private GameDto finishDrawAndAdvance(UUID id) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Game game = games.findByIdForUpdate(id);
            game.status = Game.GameStatus.COMPLETED;
            game.result = Game.GameResult.DRAW;
            tournaments.advanceWinner(id);
            return DtoMapper.toDto(games.findBySeriesId(game.seriesId).getLast(), true);
        });
    }
}
