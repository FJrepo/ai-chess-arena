package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PromptServiceTest {

    private final PromptService promptService = new PromptService();

    @Test
    void buildRetryPromptUsesConfiguredMaxAttempts() {
        String prompt = promptService.buildRetryPrompt(
                "Could not parse JSON",
                "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
                List.of("e4", "Nf3"),
                2,
                5
        );

        assertTrue(prompt.contains("Attempt 2 of 5."));
        assertFalse(prompt.contains("LAST CHANCE"));
    }

    @Test
    void buildRetryPromptIncludesLastChanceWarningOnFinalAttempt() {
        String prompt = promptService.buildRetryPrompt(
                "The move 'Ke9' is not a legal move in this position.",
                "8/8/8/8/8/8/8/8 w - - 0 1",
                List.of("Kh2"),
                3,
                3
        );

        assertTrue(prompt.contains("Attempt 3 of 3."));
        assertTrue(prompt.contains("LAST CHANCE"));
        assertTrue(prompt.contains("already made 2 invalid attempts"));
    }

    @Test
    void buildRetryPromptCarriesFenAndLegalMoves() {
        String fen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/3P4/PPP2PPP/RNBQKBNR w KQkq - 0 3";
        String prompt = promptService.buildRetryPrompt(
                "Could not parse your response as JSON.",
                fen,
                List.of("Nf3", "Bc4", "Qh5"),
                1,
                3
        );

        assertTrue(prompt.contains("Current position FEN: " + fen));
        assertTrue(prompt.contains("Legal moves: Nf3, Bc4, Qh5"));
    }

    @Test
    void resolvePromptVersionReturnsDefaultForDefaultTemplate() {
        String version = promptService.resolvePromptVersion(promptService.getDefaultSystemPromptTemplate());
        assertEquals(promptService.getDefaultSystemPromptVersion(), version);
    }

    @Test
    void resolvePromptVersionReturnsLegacyCustomForCustomTemplate() {
        String version = promptService.resolvePromptVersion("Play chess. Return JSON.");
        assertEquals("legacy-custom-template", version);
    }

    @Test
    void resolvePromptVersionReturnsDefaultForBlankTemplate() {
        String version = promptService.resolvePromptVersion("   ");
        assertEquals(promptService.getDefaultSystemPromptVersion(), version);
    }

    @Test
    void resolvePromptVersionReturnsCustomInstructionsVariantWhenInstructionsPresent() {
        String version = promptService.resolvePromptVersion(null, "Play sharp openings.");
        assertEquals(promptService.getDefaultSystemPromptVersion() + "+custom-instructions", version);
    }

    @Test
    void buildSystemPromptUsesImmutableRulesAndAppendsSharedInstructions() {
        PromptService.ResolvedPrompt prompt = promptService.buildSystemPrompt(
                null,
                "Prefer open positions and avoid repeating moves.",
                "WHITE",
                "Opponent Bot",
                "model/opponent"
        );

        assertTrue(prompt.prompt().contains("RULES:"));
        assertTrue(prompt.prompt().contains("CUSTOM INSTRUCTIONS:"));
        assertTrue(prompt.prompt().contains("Prefer open positions and avoid repeating moves."));
        assertTrue(prompt.prompt().contains("You are playing as WHITE in this game."));
        assertEquals(promptService.getDefaultSystemPromptVersion() + "+custom-instructions", prompt.version());
    }

    @Test
    void buildSystemPromptIgnoresBlankInstructions() {
        PromptService.ResolvedPrompt prompt = promptService.buildSystemPrompt(
                null,
                "   ",
                "BLACK",
                "Opponent Bot",
                "model/opponent"
        );

        assertFalse(prompt.prompt().contains("CUSTOM INSTRUCTIONS:"));
        assertEquals(promptService.getDefaultSystemPromptVersion(), prompt.version());
    }

    @Test
    void buildSystemPromptKeepsLegacyCustomTemplateOverride() {
        PromptService.ResolvedPrompt prompt = promptService.buildSystemPrompt(
                "Play as %s against %s (%s). Return raw JSON only.",
                "Ignore this instruction.",
                "WHITE",
                "Opponent Bot",
                "model/opponent"
        );

        assertTrue(prompt.prompt().startsWith("Play as WHITE against Opponent Bot"));
        assertFalse(prompt.prompt().contains("CUSTOM INSTRUCTIONS:"));
        assertEquals("legacy-custom-template", prompt.version());
    }

    @Test
    void computePromptHashIsDeterministicAndSensitiveToChanges() {
        String hashA1 = promptService.computePromptHash("Prompt A");
        String hashA2 = promptService.computePromptHash("Prompt A");
        String hashB = promptService.computePromptHash("Prompt B");

        assertTrue(hashA1.equals(hashA2));
        assertNotEquals(hashA1, hashB);
        assertTrue(hashA1.length() == 64);
    }

    @Test
    void computePromptHashReturnsNullForNullPrompt() {
        assertNull(promptService.computePromptHash(null));
    }
}
