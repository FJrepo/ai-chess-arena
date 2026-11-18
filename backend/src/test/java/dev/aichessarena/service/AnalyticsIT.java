package dev.aichessarena.service;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;

import dev.aichessarena.entity.BanterLevel;
import dev.aichessarena.entity.ChatMessage;
import dev.aichessarena.entity.ChatMode;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Game.GameStatus;
import dev.aichessarena.entity.Move;
import dev.aichessarena.entity.MoveAttempt;
import dev.aichessarena.entity.MoveAttempt.Outcome;
import dev.aichessarena.entity.ReasoningEffort;
import dev.aichessarena.entity.Tournament;
import dev.aichessarena.entity.TournamentParticipant;
import dev.aichessarena.repository.ChatMessageRepository;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveAttemptRepository;
import dev.aichessarena.repository.MoveRepository;
import dev.aichessarena.repository.TournamentParticipantRepository;
import dev.aichessarena.repository.TournamentRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Analytics windows and per-model cost against a real PostgreSQL via Dev Services. */
@QuarkusTest
class AnalyticsIT {

    private static final String START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    @Inject
    TournamentRepository tournamentRepository;

    @Inject
    TournamentParticipantRepository participantRepository;

    @Inject
    GameRepository gameRepository;

    @Inject
    MoveRepository moveRepository;

    @Inject
    MoveAttemptRepository attemptRepository;

    @Inject
    ChatMessageRepository chatMessageRepository;

    private UUID tournamentId;

    /**
     * One tournament with:
     * <ul>
     *   <li>a game created 40 days ago and finished yesterday (in the 30-day window), with rejected and accepted
     *       attempts;</li>
     *   <li>a game finished 35 days ago (outside the window);</li>
     *   <li>a bye and a game that has not started.</li>
     * </ul>
     */
    @BeforeEach
    void seed() {
        tournamentId = seed(true);
    }

    private UUID seed(boolean legalMoveHints) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Tournament tournament = new Tournament();
            tournament.name = "Analytics " + UUID.randomUUID();
            tournament.status = Tournament.TournamentStatus.IN_PROGRESS;
            tournament.legalMoveHints = legalMoveHints;
            tournamentRepository.persist(tournament);
            TournamentParticipant alpha = participant(tournament, "Alpha", 0);
            TournamentParticipant beta = participant(tournament, "Beta", 1);
            LocalDateTime now = LocalDateTime.now();

            Game recent = game(tournament, alpha, beta, 0, GameStatus.COMPLETED, now.minusDays(40), now.minusDays(1));
            recent.result = Game.GameResult.WHITE_WINS;
            recent.resultReason = Game.ResultReason.CHECKMATE;
            gameRepository.persist(recent);
            // Chat was MANDATORY: alpha talked (10 characters without the surrounding spaces), beta stayed silent.
            move(recent, 1, "WHITE", "model-alpha", "0.01", 1).chatMode = ChatMode.MANDATORY;
            move(recent, 1, "BLACK", "model-beta", "0.02", 0).chatMode = ChatMode.MANDATORY;
            chat(recent, 1, "WHITE", "model-alpha", "  Good luck!  ");
            // A duplicate message for the same ply (possible in databases from before V8): only the first counts.
            chat(recent, 1, "WHITE", "model-alpha", "A much longer duplicate message").createdAt =
                    LocalDateTime.now().plusMinutes(1);
            MoveAttempt rejected = attempt(recent, 1, "WHITE", 1, "model-alpha", Outcome.ILLEGAL_MOVE, "0.003");
            rejected.reasoningTokens = 300;
            rejected.reasoningEffort = ReasoningEffort.MEDIUM;
            MoveAttempt accepted = attempt(recent, 1, "WHITE", 2, "model-alpha", Outcome.ACCEPTED, "0.01");
            accepted.reasoningEffort = ReasoningEffort.MEDIUM;
            accepted.reasoningTokens = 0;
            attempt(recent, 1, "BLACK", 1, "model-beta", Outcome.ACCEPTED, "0.02");
            attempt(recent, 2, "WHITE", 1, "model-alpha", Outcome.TRUNCATED, "0.001")
                    .reasoningEffort = ReasoningEffort.MEDIUM;

            Game old = game(tournament, beta, alpha, 1, GameStatus.COMPLETED, now.minusDays(50), now.minusDays(35));
            old.result = Game.GameResult.WHITE_WINS;
            gameRepository.persist(old);
            move(old, 1, "BLACK", "model-alpha", "1.00", 0).chatMode = ChatMode.OPTIONAL;
            chat(old, 1, "BLACK", "model-alpha", "Outside the window");
            attempt(old, 1, "BLACK", 1, "model-alpha", Outcome.ILLEGAL_MOVE, "1.00");

            Game bye = game(tournament, alpha, null, 2, GameStatus.COMPLETED, now.minusDays(2), null);
            bye.result = Game.GameResult.WHITE_WINS;
            bye.blackPlayerName = "BYE";
            gameRepository.persist(bye);
            attempt(bye, 1, "WHITE", 1, "model-alpha", Outcome.ACCEPTED, "0");

            Game waiting = game(tournament, alpha, beta, 3, GameStatus.WAITING, now.minusDays(1), null);
            gameRepository.persist(waiting);
            attempt(waiting, 1, "WHITE", 1, "model-alpha", Outcome.DISCARDED, "0");
            return tournament.id;
        });
    }

    @Test
    void filtersOutUnknownSidesAndKeepsReportedZeroSeparateFromRetries() {
        given().queryParam("tournamentId", tournamentId).queryParam("reasoningEffort", "medium")
                .when().get("/api/analytics/comparison")
                .then().statusCode(200)
                .body("gamesCount", equalTo(1))
                .body("models", hasSize(1))
                .body("models[0].modelId", equalTo("model-alpha"))
                .body("models[0].gamesPlayed", equalTo(1))
                .body("models[0].reasoning.effort", equalTo("medium"))
                .body("models[0].reasoning.averageTokens", equalTo(0.0f))
                .body("models[0].reasoning.reportedMoves", equalTo(1))
                .body("models[0].reasoning.modelMoves", equalTo(1));

        given().queryParam("tournamentId", tournamentId).queryParam("reasoningEffort", "nonsense")
                .when().get("/api/analytics/comparison")
                .then().statusCode(400);
    }

    @Test
    void ambiguousDifferentEffortsKeepEachSideInTheUnknownBucket() {
        QuarkusTransaction.requiringNew().run(() -> {
            Game recent = gameRepository.find("tournament.id = ?1 and bracketPosition = 0", tournamentId).firstResult();
            bodylessTimeout(recent, ReasoningEffort.HIGH);
            Game second = game(tournamentRepository.findById(tournamentId), recent.whiteParticipant,
                    recent.blackParticipant, 4, GameStatus.COMPLETED, LocalDateTime.now(), LocalDateTime.now());
            second.result = Game.GameResult.WHITE_WINS;
            gameRepository.persist(second);
            move(second, 1, "WHITE", "model-alpha", "0", 0);
            attempt(second, 1, "WHITE", 1, "model-alpha", Outcome.ACCEPTED, "0")
                    .reasoningEffort = ReasoningEffort.HIGH;
            bodylessTimeout(second, ReasoningEffort.MEDIUM);
        });

        given().queryParam("tournamentId", tournamentId).queryParam("reasoningEffort", "medium")
                .when().get("/api/analytics/comparison")
                .then().statusCode(200)
                .body("models", hasSize(0));

        given().queryParam("tournamentId", tournamentId).queryParam("reasoningEffort", "unknown")
                .when().get("/api/analytics/comparison")
                .then().statusCode(200)
                .body("models.find { it.modelId == 'model-alpha' }.gamesPlayed", equalTo(2))
                .body("models.find { it.modelId == 'model-alpha' }.reasoning.effort", equalTo("unknown"));
    }

    private void bodylessTimeout(Game game, ReasoningEffort effort) {
        MoveAttempt timedOut = attempt(game, 2, "WHITE", 3, "model-alpha", Outcome.TIMEOUT, "0");
        timedOut.reasoningEffort = effort;
        timedOut.promptTokens = null;
        timedOut.cachedPromptTokens = null;
        timedOut.cacheWriteTokens = null;
    }

    @Test
    void mixedDefaultAndFailedOnlySamplesUseObservedRequests() {
        QuarkusTransaction.requiringNew().run(() -> {
            Game recent = gameRepository.find("tournament.id = ?1 and bracketPosition = 0", tournamentId).firstResult();
            MoveAttempt retry = attempt(recent, 2, "WHITE", 2, "model-alpha", Outcome.ILLEGAL_MOVE, "0");
            retry.reasoningEffort = ReasoningEffort.HIGH;
            MoveAttempt beta = attemptRepository.find("game.id = ?1 and modelId = 'model-beta'", recent.id)
                    .firstResult();
            beta.effectiveMaxOutputTokens = 4096;
            beta.reasoningTokens = null;

            Game forfeit = game(tournamentRepository.findById(tournamentId), recent.whiteParticipant,
                    recent.blackParticipant, 4, GameStatus.FORFEIT, LocalDateTime.now(), LocalDateTime.now());
            forfeit.result = Game.GameResult.WHITE_FORFEIT;
            gameRepository.persist(forfeit);
            attempt(forfeit, 1, "WHITE", 1, "model-alpha", Outcome.ILLEGAL_MOVE, "0")
                    .reasoningEffort = ReasoningEffort.HIGH;
        });

        given().queryParam("tournamentId", tournamentId).queryParam("reasoningEffort", "mixed")
                .when().get("/api/analytics/comparison")
                .then().statusCode(200)
                .body("models", hasSize(1))
                .body("models[0].modelId", equalTo("model-alpha"))
                .body("models[0].gamesPlayed", equalTo(1))
                .body("models[0].reasoning.effort", equalTo("mixed"))
                .body("models[0].reasoning.reportedMoves", equalTo(1));

        given().queryParam("tournamentId", tournamentId).queryParam("reasoningEffort", "high")
                .when().get("/api/analytics/comparison")
                .then().statusCode(200)
                .body("models", hasSize(1))
                .body("models[0].forfeits", equalTo(1))
                .body("models[0].reasoning.effort", equalTo("high"))
                .body("models[0].reasoning.modelMoves", equalTo(0));

        given().queryParam("tournamentId", tournamentId).queryParam("reasoningEffort", "default")
                .when().get("/api/analytics/comparison")
                .then().statusCode(200)
                .body("models", hasSize(1))
                .body("models[0].modelId", equalTo("model-beta"))
                .body("models[0].reasoning.effort", equalTo("default"))
                .body("models[0].reasoning.reportedMoves", equalTo(0));
    }

    @Test
    void effortAndHintFiltersAreIndependentCacheDimensions() {
        UUID unhintedTournament = seed(false);

        given().queryParam("tournamentId", unhintedTournament).queryParam("legalMoveHints", false)
                .queryParam("reasoningEffort", "medium").get("/api/analytics/comparison")
                .then().statusCode(200).body("models", hasSize(1)).body("models[0].modelId", equalTo("model-alpha"));

        given().queryParam("tournamentId", unhintedTournament).queryParam("legalMoveHints", true)
                .queryParam("reasoningEffort", "medium").get("/api/analytics/comparison")
                .then().statusCode(200).body("gamesCount", equalTo(0));

        given().queryParam("tournamentId", unhintedTournament).queryParam("legalMoveHints", false)
                .queryParam("reasoningEffort", "unknown").get("/api/analytics/comparison")
                .then().statusCode(200).body("models", hasSize(1)).body("models[0].modelId", equalTo("model-beta"));

        given().queryParam("tournamentId", unhintedTournament).queryParam("legalMoveHints", false)
                .queryParam("reasoningEffort", "medium", "nonsense").get("/api/analytics/comparison")
                .then().statusCode(400);
    }

    @Test
    void analyticsHintsFilterCombinesWithTheTournamentAndRejectsInvalidValues() {
        given().queryParam("tournamentId", tournamentId).queryParam("legalMoveHints", true)
                .get("/api/analytics/comparison").then().statusCode(200).body("gamesCount", equalTo(1));
        given().queryParam("tournamentId", tournamentId).queryParam("legalMoveHints", false)
                .get("/api/analytics/comparison").then().statusCode(200).body("gamesCount", equalTo(0));
        given().queryParam("legalMoveHints", "").get("/api/analytics/health").then().statusCode(400);
        given().queryParam("legalMoveHints", "both").get("/api/analytics/reliability").then().statusCode(400);
    }

    @Test
    void comparisonCountsFinishedGamesInTheWindowAndRejectedAttemptCost() {
        given().queryParam("days", 30).queryParam("tournamentId", tournamentId)
                .when().get("/api/analytics/comparison")
                .then().statusCode(200)
                .body("gamesCount", equalTo(1))
                .body("models", hasSize(2))
                .body("models.find { it.modelId == 'model-alpha' }.gamesPlayed", equalTo(1))
                .body("models.find { it.modelId == 'model-alpha' }.movesSampled", equalTo(1))
                // 0.01 accepted move + 0.003 illegal + 0.001 cut off; the accepted attempt is not counted again.
                .body("models.find { it.modelId == 'model-alpha' }.totalCostUsd", equalTo(0.014f))
                .body("models.find { it.modelId == 'model-alpha' }.firstTryLegalRate", equalTo(0.0f))
                .body("models.find { it.modelId == 'model-beta' }.totalCostUsd", equalTo(0.02f))
                .body("models.find { it.modelId == 'model-beta' }.firstTryLegalRate", equalTo(1.0f));
    }

    @Test
    void comparisonReportsTalkativenessPerChatModeFromStoredMessages() {
        given().queryParam("days", 30).queryParam("tournamentId", tournamentId)
                .when().get("/api/analytics/comparison")
                .then().statusCode(200)
                .body("models.find { it.modelId == 'model-alpha' }.mandatoryChat.moves", equalTo(1))
                .body("models.find { it.modelId == 'model-alpha' }.mandatoryChat.messages", equalTo(1))
                .body("models.find { it.modelId == 'model-alpha' }.mandatoryChat.talkRate", equalTo(1.0f))
                .body("models.find { it.modelId == 'model-alpha' }.mandatoryChat.averageMessageLength",
                        equalTo(10.0f))
                // The OPTIONAL move of alpha is in a game outside the window.
                .body("models.find { it.modelId == 'model-alpha' }.optionalChat.moves", equalTo(0))
                .body("models.find { it.modelId == 'model-alpha' }.optionalChat", not(hasKey("talkRate")))
                .body("models.find { it.modelId == 'model-beta' }.mandatoryChat.moves", equalTo(1))
                .body("models.find { it.modelId == 'model-beta' }.mandatoryChat.messages", equalTo(0))
                .body("models.find { it.modelId == 'model-beta' }.mandatoryChat.talkRate", equalTo(0.0f));
    }

    @Test
    void reliabilityAndHealthIgnoreByesAndUnstartedGames() {
        given().queryParam("days", 30).queryParam("tournamentId", tournamentId)
                .when().get("/api/analytics/reliability")
                .then().statusCode(200)
                .body("models.find { it.modelId == 'model-alpha' }.gamesPlayed", equalTo(1))
                .body("models.find { it.modelId == 'model-alpha' }.gamesCompleted", equalTo(1))
                .body("models.find { it.modelId == 'model-alpha' }.averageCostPerMoveUsd", equalTo(0.014f));

        given().queryParam("days", 30).queryParam("tournamentId", tournamentId)
                .when().get("/api/analytics/health")
                .then().statusCode(200)
                .body("gamesCount", equalTo(2))
                .body("completedGamesCount", equalTo(1))
                .body("movesCount", equalTo(2))
                .body("costTotalUsd", equalTo(0.034f));
    }

    @Test
    void tournamentCostSummaryAddsRejectedAttemptsPerModel() {
        given().when().get("/api/tournaments/{id}/cost-summary", tournamentId)
                .then().statusCode(200)
                .body("topModels[0].modelId", equalTo("model-alpha"))
                // Moves 0.01 + 1.00, rejected attempts 0.003 + 0.001 + 1.00.
                .body("topModels[0].totalCostUsd", equalTo(2.014f))
                .body("topModels[1].totalCostUsd", equalTo(0.02f));
    }

    private TournamentParticipant participant(Tournament tournament, String name, int seed) {
        TournamentParticipant participant = new TournamentParticipant();
        participant.tournament = tournament;
        participant.playerName = name;
        participant.controlType = TournamentParticipant.ControlType.AI;
        participant.modelId = "model-" + name.toLowerCase();
        participant.seed = seed;
        participantRepository.persist(participant);
        return participant;
    }

    @Test
    void promptCachingUsesAllAttemptsInFinishedGamesAndExcludesOtherWindowsByesAndWaitingGames() {
        given().queryParam("days", 30).queryParam("tournamentId", tournamentId)
                .when().get("/api/analytics/comparison").then().statusCode(200)
                .body("models.find { it.modelId == 'model-alpha' }.promptCaching.promptTokens", equalTo(300))
                .body("models.find { it.modelId == 'model-alpha' }.promptCaching.cachedPromptTokens", equalTo(240))
                .body("models.find { it.modelId == 'model-alpha' }.promptCaching.cacheHitRate", equalTo(0.8f))
                .body("models.find { it.modelId == 'model-alpha' }.promptCaching.cacheWriteTokens", equalTo(60))
                .body("models.find { it.modelId == 'model-alpha' }.promptCaching.estimatedSavingsUsd",
                        org.hamcrest.Matchers.nullValue())
                .body("models.find { it.modelId == 'model-beta' }.promptCaching.promptTokens", equalTo(100))
                .body("models.find { it.modelId == 'model-beta' }.promptCaching.cachedPromptTokens",
                        org.hamcrest.Matchers.nullValue())
                .body("models.find { it.modelId == 'model-beta' }.promptCaching.cacheHitRate",
                        org.hamcrest.Matchers.nullValue())
                .body("models.find { it.modelId == 'model-beta' }.promptCaching.cacheWriteTokens",
                        org.hamcrest.Matchers.nullValue());
    }

    @Test
    void comparisonReportsPersuasionFromStoredEvaluationsMessagesAndPromptVersions() {
        String mindGames = new PromptService().getSystemPromptVersion(ChatMode.OPTIONAL, true,
                BanterLevel.MIND_GAMES);
        QuarkusTransaction.requiringNew().run(() -> {
            Tournament tournament = tournamentRepository.findById(tournamentId);
            TournamentParticipant alpha = participantRepository.find("tournament.id = ?1 and seed = 0", tournamentId)
                    .firstResult();
            TournamentParticipant beta = participantRepository.find("tournament.id = ?1 and seed = 1", tournamentId)
                    .firstResult();
            LocalDateTime now = LocalDateTime.now();
            Game resigned = game(tournament, alpha, beta, 4, GameStatus.COMPLETED, now.minusDays(2), now.minusDays(1));
            resigned.result = Game.GameResult.WHITE_WINS;
            resigned.resultReason = Game.ResultReason.RESIGNATION;
            gameRepository.persist(resigned);
            Move last = null;
            for (int number = 1; number <= 10; number++) {
                last = move(resigned, number, "WHITE", "model-alpha", "0.01", 0);
                last.promptVersion = mindGames;
                if (number < 10) {
                    move(resigned, number, "BLACK", "model-beta", "0.01", 0);
                }
            }
            // Black resigns facing +1.99 for White (−1.99 from its own view): not lost, after alpha's message.
            last.evaluationCp = 199;
            chat(resigned, 9, "WHITE", "model-alpha", "Resign now, your king is doomed.");
        });

        given().queryParam("days", 30).queryParam("tournamentId", tournamentId)
                .when().get("/api/analytics/comparison")
                .then().statusCode(200)
                .body("persuasionRules.lostThresholdCp", equalTo(-200))
                .body("persuasionRules.messageWindowMoves", equalTo(3))
                .body("models.find { it.modelId == 'model-alpha' }.persuasion.asPersuaderMindGames.resignations",
                        equalTo(1))
                .body("models.find { it.modelId == 'model-alpha' }.persuasion.asPersuaderMindGames.talkedOut",
                        equalTo(1))
                .body("models.find { it.modelId == 'model-alpha' }.persuasion.asPersuaderMindGames.lowSample",
                        equalTo(true))
                .body("models.find { it.modelId == 'model-alpha' }.persuasion.asPersuaderOtherLevels.resignations",
                        equalTo(0))
                .body("models.find { it.modelId == 'model-beta' }.persuasion.asPersuaded.evaluated", equalTo(1))
                .body("models.find { it.modelId == 'model-beta' }.persuasion.asPersuaded.talkedOut", equalTo(1));
    }

    private Game game(
            Tournament tournament,
            TournamentParticipant white,
            TournamentParticipant black,
            int position,
            GameStatus status,
            LocalDateTime createdAt,
            LocalDateTime completedAt
    ) {
        Game game = new Game();
        game.tournament = tournament;
        game.bracketRound = "Semifinal";
        game.bracketPosition = position;
        game.status = status;
        game.createdAt = createdAt;
        game.completedAt = completedAt;
        if (white != null) {
            game.whiteParticipant = white;
            game.whitePlayerName = white.playerName;
            game.whiteModelId = white.modelId;
        }
        if (black != null) {
            game.blackParticipant = black;
            game.blackPlayerName = black.playerName;
            game.blackModelId = black.modelId;
        }
        return game;
    }

    private ChatMessage chat(Game game, int moveNumber, String color, String modelId, String text) {
        ChatMessage message = new ChatMessage();
        message.game = game;
        message.moveNumber = moveNumber;
        message.senderColor = color;
        message.senderModel = modelId;
        message.message = text;
        chatMessageRepository.persist(message);
        return message;
    }

    private Move move(Game game, int moveNumber, String color, String modelId, String cost, int retryCount) {
        Move move = new Move();
        move.game = game;
        move.moveNumber = moveNumber;
        move.color = color;
        move.san = "WHITE".equals(color) ? "e4" : "e5";
        move.fen = START_FEN;
        move.modelId = modelId;
        move.costUsd = new BigDecimal(cost);
        move.retryCount = retryCount;
        move.createdAt = game.createdAt;
        moveRepository.persist(move);
        return move;
    }

    private MoveAttempt attempt(Game game, int moveNumber, String color, int number, String modelId, Outcome outcome,
            String cost) {
        MoveAttempt attempt = new MoveAttempt();
        attempt.game = game;
        attempt.moveNumber = moveNumber;
        attempt.color = color;
        attempt.attemptNumber = number;
        attempt.modelId = modelId;
        attempt.outcome = outcome;
        attempt.costUsd = new BigDecimal(cost);
        attempt.promptTokens = 100;
        if ("model-alpha".equals(modelId)) {
            attempt.cachedPromptTokens = 80;
            attempt.cacheWriteTokens = 20;
        }
        attempt.createdAt = game.createdAt;
        attemptRepository.persist(attempt);
        return attempt;
    }
}
