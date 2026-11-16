package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.bhlangonijr.chesslib.Board;
import java.util.ArrayList;
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
        assertTrue(prompt.prompt().contains("You are playing chess in a tournament match."));
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
    void defaultPromptStillContainsTheTrashTalkRuleThatTheSilentVariantReplaces() {
        assertTrue(promptService.getDefaultSystemPromptTemplate().contains(PromptService.TRASH_TALK_RULE));
    }

    @Test
    void silentPromptReplacesTheTrashTalkRuleAndMarksTheVersion() {
        PromptService.ResolvedPrompt prompt = promptService.buildSystemPrompt(
                null, null, "WHITE", "Opponent Bot", "model/opponent", false);

        assertFalse(prompt.prompt().contains(PromptService.TRASH_TALK_RULE));
        assertFalse(prompt.prompt().contains("trash talk if you want"));
        assertTrue(prompt.prompt().contains(PromptService.SILENT_MESSAGE_RULE));
        assertTrue(prompt.prompt().contains("You are playing as WHITE in this game."));
        assertEquals("v2-silent", prompt.version());
    }

    @Test
    void silentPromptWithCustomInstructionsKeepsBothSuffixes() {
        PromptService.ResolvedPrompt prompt = promptService.buildSystemPrompt(
                null, "Play the London.", "BLACK", "Opponent Bot", "model/opponent", false);

        assertTrue(prompt.prompt().contains(PromptService.SILENT_MESSAGE_RULE));
        assertTrue(prompt.prompt().contains("Play the London."));
        assertEquals("v2+custom-instructions-silent", prompt.version());
    }

    @Test
    void enabledTrashTalkMatchesTheFiveArgumentPrompt() {
        PromptService.ResolvedPrompt legacy = promptService.buildSystemPrompt(
                null, null, "WHITE", "Opponent Bot", "model/opponent");
        PromptService.ResolvedPrompt enabled = promptService.buildSystemPrompt(
                null, null, "WHITE", "Opponent Bot", "model/opponent", true);

        assertEquals(legacy, enabled);
        assertEquals("v2", enabled.version());
    }

    @Test
    void silentModeLeavesLegacyCustomTemplatesUntouched() {
        PromptService.ResolvedPrompt prompt = promptService.buildSystemPrompt(
                "Play as %s against %s (%s). You can trash talk if you want.", null,
                "WHITE", "Opponent Bot", "model/opponent", false);

        assertEquals("Play as WHITE against Opponent Bot (model/opponent). You can trash talk if you want.",
                prompt.prompt());
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

    /**
     * Measures what one side sends per move over a 70-ply game: system prompt, the last six prior messages and
     * the current turn (see {@link ConversationWindow}). The setup page estimates spend with
     * {@code PROMPT_TOKENS_PER_MOVE = 1400} in tournament-setup.ts; at roughly 3.2 characters per token for
     * FEN, SAN lists and the ASCII board this averages about 4,500 characters (about 1,600 at the first move,
     * about 5,000 late in the game). Update both if the prompts change a lot.
     */
    @Test
    void boundedTurnWindowAveragesAboutFourteenHundredPromptTokens() {
        ChessService chess = new ChessService();
        Board board = chess.createBoard();
        List<List<OpenRouterService.ChatMsg>> histories = List.of(
                new ArrayList<>(List.of(system("WHITE", "Beta"))),
                new ArrayList<>(List.of(system("BLACK", "Alpha")))
        );
        String pgn = null;
        String lastMove = null;
        long totalChars = 0;
        int plies = 0;

        for (int ply = 0; ply < 70 && !chess.isGameOver(board); ply++) {
            boolean white = ply % 2 == 0;
            List<String> legalMoves = chess.getLegalMovesAsSan(board);
            int moveNumber = chess.getMoveNumber(board);
            List<OpenRouterService.ChatMsg> history = histories.get(white ? 0 : 1);
            int turnStart = history.size();
            history.add(new OpenRouterService.ChatMsg("user", promptService.buildTurnPrompt(
                    pgn, board.getFen(), chess.toAsciiBoard(board), white ? "WHITE" : "BLACK", moveNumber,
                    legalMoves, white ? "Beta" : "Alpha", lastMove, "Nice try.", ply == 0)));
            totalChars += ConversationWindow.trim(history, turnStart, ConversationWindow.DEFAULT_PRIOR_MESSAGES)
                    .stream()
                    .mapToInt(message -> message.content().length())
                    .sum();
            plies++;

            String san = legalMoves.get((ply * 7 + 3) % legalMoves.size());
            history.add(new OpenRouterService.ChatMsg("assistant",
                    "{\"move\": \"" + san + "\", \"message\": \"Watch your king.\"}"));
            chess.validateAndApply(board, san);
            pgn = (pgn == null ? "" : pgn + " ") + (white ? moveNumber + ". " + san : san);
            lastMove = san;
        }

        double averageTokens = (double) totalChars / plies / 3.2;
        assertTrue(plies >= 40, "the sample game should last long enough: " + plies);
        assertTrue(averageTokens > 1150 && averageTokens < 1650, "average prompt tokens: " + averageTokens);
    }

    private OpenRouterService.ChatMsg system(String color, String opponent) {
        return new OpenRouterService.ChatMsg("system",
                promptService.buildSystemPrompt(null, null, color, opponent, "vendor/" + opponent.toLowerCase())
                        .prompt());
    }
}
