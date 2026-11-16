package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.MoveAttempt;
import dev.aichessarena.entity.MoveAttempt.Outcome;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveAttemptRepository;
import dev.aichessarena.service.OpenRouterService.LlmResponse;
import dev.aichessarena.service.OpenRouterService.ProviderOutcome;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MoveAttemptServiceTest {

    @Test
    void rejectedAttemptIsStoredAndAddsItsCostToTheGame() {
        Fixture fixture = new Fixture(new BigDecimal("0.10"));

        fixture.service.record(attempt(fixture.game.id, Outcome.ILLEGAL_MOVE, "Qxh7",
                new LlmResponse("{\"move\":\"Qxh7\"}", 120, 30, 0.0025, 900, "{\"raw\":true}", false, null,
                        ProviderOutcome.OK, 200, "stop", 12)));

        assertEquals(1, fixture.attempts.persisted.size());
        MoveAttempt row = fixture.attempts.persisted.getFirst();
        assertSame(fixture.game, row.game);
        assertEquals(Outcome.ILLEGAL_MOVE, row.outcome);
        assertEquals("Qxh7", row.proposedMove);
        assertEquals(120, row.promptTokens);
        assertEquals(30, row.completionTokens);
        assertEquals(12, row.reasoningTokens);
        assertEquals(900L, row.responseTimeMs);
        assertEquals("stop", row.finishReason);
        assertEquals("{\"raw\":true}", row.rawResponse);
        assertEquals(0, new BigDecimal("0.1025").compareTo(fixture.game.totalCostUsd), fixture.game.totalCostUsd.toString());
        assertTrue(fixture.games.lockedForUpdate);
    }

    @Test
    void truncatedAttemptStoresTheEffectiveRequestCapAndKnownModelMaximum() {
        Fixture fixture = new Fixture(BigDecimal.ZERO);
        String detail = "Answer was cut off at the model's maximum of 8,192 output tokens";
        fixture.service.record(new MoveAttemptService.AttemptRecord(fixture.game.id, 1, "WHITE", 1, "model-a",
                Outcome.TRUNCATED, null, detail,
                new LlmResponse(null, 100, 8192, null, 500, "{}", false, null,
                        ProviderOutcome.OK, 200, "length", 8192).withOutputTokenLimits(8192, 8192)));

        MoveAttempt row = fixture.attempts.persisted.getFirst();
        assertEquals(8192, row.effectiveMaxOutputTokens);
        assertEquals(8192, row.modelMaxCompletionTokens);
        assertEquals(detail, row.errorMessage);
    }

    @Test
    void acceptedAttemptIsStoredWithoutCountingItsCostTwice() {
        Fixture fixture = new Fixture(new BigDecimal("0.10"));

        fixture.service.record(attempt(fixture.game.id, Outcome.ACCEPTED, "e4",
                new LlmResponse("{\"move\":\"e4\"}", 100, 10, 0.004, 500, "{}", false, null)));

        assertEquals(1, fixture.attempts.persisted.size());
        assertEquals(0, new BigDecimal("0.10").compareTo(fixture.game.totalCostUsd));
    }

    @Test
    void providerFailureWithoutUsageStoresNullMetrics() {
        Fixture fixture = new Fixture(BigDecimal.ZERO);

        fixture.service.record(attempt(fixture.game.id, Outcome.PROVIDER_RETRYABLE, null,
                LlmResponse.providerFailure(ProviderOutcome.RETRYABLE, 503, "OpenRouter server error (HTTP 503)", 0,
                        null)));

        MoveAttempt row = fixture.attempts.persisted.getFirst();
        assertNull(row.effectiveMaxOutputTokens);
        assertNull(row.modelMaxCompletionTokens);
        assertNull(row.promptTokens);
        assertNull(row.completionTokens);
        assertNull(row.costUsd);
        assertNull(row.responseTimeMs);
        assertEquals(0, BigDecimal.ZERO.compareTo(fixture.game.totalCostUsd));
    }

    @Test
    void longValuesAreBoundedToTheirColumns() {
        Fixture fixture = new Fixture(BigDecimal.ZERO);

        fixture.service.record(new MoveAttemptService.AttemptRecord(fixture.game.id, 1, "WHITE", 1, "model-a",
                Outcome.PARSE_FAILURE, "m".repeat(100), "e".repeat(800),
                new LlmResponse("x", 1, 1, null, 1, "r".repeat(30_000), false, null,
                        ProviderOutcome.OK, 200, "f".repeat(40), null)));

        MoveAttempt row = fixture.attempts.persisted.getFirst();
        assertEquals(MoveAttemptService.MAX_PROPOSED_MOVE_CHARS, row.proposedMove.length());
        assertEquals(MoveAttemptService.MAX_ERROR_CHARS, row.errorMessage.length());
        assertEquals(MoveAttemptService.MAX_RAW_RESPONSE_CHARS, row.rawResponse.length());
        assertEquals(MoveAttemptService.MAX_FINISH_REASON_CHARS, row.finishReason.length());
    }

    @Test
    void onlyTheModelsOwnFailuresCountAgainstIt() {
        assertEquals(List.of(Outcome.ILLEGAL_MOVE, Outcome.PARSE_FAILURE, Outcome.EMPTY_RESPONSE, Outcome.TIMEOUT,
                        Outcome.RESIGN_NOT_ALLOWED),
                java.util.Arrays.stream(Outcome.values()).filter(Outcome::isModelFailure).toList());
    }

    @Test
    void anAcceptedResignationIsStoredWithItsTokensButCostedWithTheGameResult() {
        Fixture fixture = new Fixture(new BigDecimal("0.10"));

        fixture.service.record(attempt(fixture.game.id, Outcome.RESIGNED, null,
                new LlmResponse("{\"resign\":true}", 300, 12, 0.006, 700, "{}", false, null)));

        MoveAttempt row = fixture.attempts.persisted.getFirst();
        assertEquals(Outcome.RESIGNED, row.outcome);
        assertEquals(300, row.promptTokens);
        assertEquals(12, row.completionTokens);
        assertEquals(0, new BigDecimal("0.006").compareTo(row.costUsd));
        // GameLifecycleService.resign added it to the game together with the result.
        assertEquals(0, new BigDecimal("0.10").compareTo(fixture.game.totalCostUsd));
    }

    @Test
    void aResignationThatWasNotAllowedAddsItsCostLikeOtherInvalidAnswers() {
        Fixture fixture = new Fixture(new BigDecimal("0.10"));

        fixture.service.record(attempt(fixture.game.id, Outcome.RESIGN_NOT_ALLOWED, null,
                new LlmResponse("{\"resign\":true}", 300, 12, 0.006, 700, "{}", false, null)));

        assertEquals(0, new BigDecimal("0.106").compareTo(fixture.game.totalCostUsd));
        assertTrue(fixture.games.lockedForUpdate);
    }

    @Test
    void discardedLegalAnswerAddsItsCost() {
        Fixture fixture = new Fixture(new BigDecimal("0.10"));

        fixture.service.record(attempt(fixture.game.id, Outcome.DISCARDED, "e4",
                new LlmResponse("{\"move\":\"e4\"}", 100, 10, 0.004, 500, "{}", false, null)));

        assertEquals(Outcome.DISCARDED, fixture.attempts.persisted.getFirst().outcome);
        assertEquals(0, new BigDecimal("0.104").compareTo(fixture.game.totalCostUsd));
    }

    @Test
    void unknownGameIsIgnored() {
        Fixture fixture = new Fixture(BigDecimal.ZERO);

        fixture.service.record(attempt(UUID.randomUUID(), Outcome.TIMEOUT, null, null));

        assertTrue(fixture.attempts.persisted.isEmpty());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(Outcome.class)
    void everyOutcomeKeepsCacheUsageAndReportedCost(Outcome outcome) throws Exception {
        Fixture fixture = new Fixture(BigDecimal.ZERO);
        var response = new LlmResponse("e4", 100, 10, 0.001, 100, "{}", false, null)
                .withUsage(new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                        {"prompt_tokens":100,"completion_tokens":10,"cost":0.001,
                        "prompt_tokens_details":{"cached_tokens":80,"cache_write_tokens":0}}
                        """));
        fixture.service.record(attempt(fixture.game.id, outcome, "e4", response));
        MoveAttempt stored = fixture.attempts.persisted.getFirst();
        assertEquals(80, stored.cachedPromptTokens);
        assertEquals(0, stored.cacheWriteTokens);
        assertEquals(0, new BigDecimal("0.001").compareTo(stored.costUsd));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"null,null", "0,0", "100,100"})
    void promptUsageDistinguishesMissingFromReportedZeroEvenWhenCacheCountersExist(String prompt, String expected)
            throws Exception {
        Fixture fixture = new Fixture(BigDecimal.ZERO);
        var response = new LlmResponse("e4", 0, 0, null, 100, "{}", false, null)
                .withUsage(new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                        {"prompt_tokens":%s,"prompt_tokens_details":{"cached_tokens":0,"cache_write_tokens":0}}
                        """.formatted(prompt))).withOutputTokenLimits(16384, null);
        fixture.service.record(attempt(fixture.game.id, Outcome.ACCEPTED, "e4", response));
        var stored = fixture.attempts.persisted.getFirst();
        assertEquals("null".equals(expected) ? null : Integer.valueOf(expected), stored.promptTokens);
        assertEquals(0, stored.cachedPromptTokens);
        assertEquals(0, stored.cacheWriteTokens);
    }

    private static MoveAttemptService.AttemptRecord attempt(UUID gameId, Outcome outcome, String move,
                                                            LlmResponse response) {
        return new MoveAttemptService.AttemptRecord(gameId, 3, "BLACK", 2, "model-b", outcome, move, null, response);
    }

    private static final class Fixture {
        final Game game = new Game();
        final FakeGameRepository games = new FakeGameRepository(game);
        final RecordingMoveAttemptRepository attempts = new RecordingMoveAttemptRepository();
        final MoveAttemptService service = new MoveAttemptService();

        Fixture(BigDecimal totalCost) {
            game.id = UUID.randomUUID();
            game.totalCostUsd = totalCost;
            service.gameRepository = games;
            service.moveAttemptRepository = attempts;
        }
    }

    private static final class FakeGameRepository extends GameRepository {
        private final Game game;
        private boolean lockedForUpdate;

        private FakeGameRepository(Game game) {
            this.game = game;
        }

        @Override
        public Game findById(UUID id) {
            return game.id.equals(id) ? game : null;
        }

        @Override
        public Game findByIdForUpdate(UUID id) {
            lockedForUpdate = true;
            return findById(id);
        }
    }

    private static final class RecordingMoveAttemptRepository extends MoveAttemptRepository {
        private final List<MoveAttempt> persisted = new ArrayList<>();

        @Override
        public void persist(MoveAttempt entity) {
            persisted.add(entity);
        }
    }
}
