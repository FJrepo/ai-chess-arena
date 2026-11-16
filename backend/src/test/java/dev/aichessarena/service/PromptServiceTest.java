package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.bhlangonijr.chesslib.Board;
import dev.aichessarena.entity.ChatMode;
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
                null, null, "WHITE", "Opponent Bot", "model/opponent", ChatMode.OFF);

        assertFalse(prompt.prompt().contains(PromptService.TRASH_TALK_RULE));
        assertFalse(prompt.prompt().contains("trash talk if you want"));
        assertTrue(prompt.prompt().contains(PromptService.SILENT_MESSAGE_RULE));
        assertTrue(prompt.prompt().contains("You are playing as WHITE in this game."));
        assertEquals("v2-silent", prompt.version());
        assertEquals(ChatMode.OFF, prompt.chatMode());
    }

    @Test
    void silentPromptWithCustomInstructionsKeepsBothSuffixes() {
        PromptService.ResolvedPrompt prompt = promptService.buildSystemPrompt(
                null, "Play the London.", "BLACK", "Opponent Bot", "model/opponent", ChatMode.OFF);

        assertTrue(prompt.prompt().contains(PromptService.SILENT_MESSAGE_RULE));
        assertTrue(prompt.prompt().contains("Play the London."));
        assertEquals("v2+custom-instructions-silent", prompt.version());
    }

    @Test
    void optionalChatMatchesTheFiveArgumentPrompt() {
        PromptService.ResolvedPrompt legacy = promptService.buildSystemPrompt(
                null, null, "WHITE", "Opponent Bot", "model/opponent");
        PromptService.ResolvedPrompt optional = promptService.buildSystemPrompt(
                null, null, "WHITE", "Opponent Bot", "model/opponent", ChatMode.OPTIONAL);

        assertEquals(legacy, optional);
        assertEquals("v2", optional.version());
        assertEquals(ChatMode.OPTIONAL, optional.chatMode());
    }

    /**
     * The OFF and OPTIONAL prompts are byte-for-byte those of version 1.3.1 (hashes recorded before chat modes
     * existed), so the prompt hashes stored on existing moves stay comparable.
     */
    @Test
    void offAndOptionalPromptsAndVersionsAreUnchangedSinceBeforeChatModes() {
        assertPrompt(ChatMode.OPTIONAL, null, "v2",
                "c53f3a34f1d60ba43e09034359c003dae53e7668c785735dc6b56aca32dbb3dc");
        assertPrompt(ChatMode.OPTIONAL, "Play the London.", "v2+custom-instructions",
                "f9a4e0e0e1d29febd0facc8839064e6427cebc89393905669526bf66090e4e3a");
        assertPrompt(ChatMode.OFF, null, "v2-silent",
                "fccd19e6563fac4f88fc4f4b7a730ba09e1f1ae16e14e0f1bfc7b4d8810c0b05");
        assertPrompt(ChatMode.OFF, "Play the London.", "v2+custom-instructions-silent",
                "171c092872fb212f996158d6a11b45e60b606c11aa9e7c8b175fedb61a27b993");
    }

    private void assertPrompt(ChatMode mode, String customInstructions, String version, String hash) {
        PromptService.ResolvedPrompt prompt = promptService.buildSystemPrompt(
                null, customInstructions, "WHITE", "Opponent Bot", "model/opponent", mode);
        assertEquals(version, prompt.version());
        assertEquals(hash, promptService.computePromptHash(prompt.prompt()), mode + " prompt text changed");
    }

    @Test
    void mandatoryPromptAsksForAShortMessageWithEveryMoveAndChangesNothingElse() {
        PromptService.ResolvedPrompt optional = promptService.buildSystemPrompt(
                null, null, "WHITE", "Opponent Bot", "model/opponent", ChatMode.OPTIONAL);
        PromptService.ResolvedPrompt mandatory = promptService.buildSystemPrompt(
                null, null, "WHITE", "Opponent Bot", "model/opponent", ChatMode.MANDATORY);

        assertEquals("v2-chat-required", mandatory.version());
        assertEquals(ChatMode.MANDATORY, mandatory.chatMode());
        assertEquals("5. Chat is required in this tournament: with every move, send your opponent one short message "
                        + "(at most 200 characters) in the \"message\" field. Banter and trash talk about the game "
                        + "are welcome; stay civil. They are your opponent, so do not give them an advantage by "
                        + "revealing your next move or plans.",
                PromptService.REQUIRED_MESSAGE_RULE);

        List<String> optionalLines = optional.prompt().lines().toList();
        List<String> mandatoryLines = mandatory.prompt().lines().toList();
        assertEquals(optionalLines.size(), mandatoryLines.size());
        List<String> changed = new ArrayList<>();
        for (int i = 0; i < optionalLines.size(); i++) {
            if (!optionalLines.get(i).equals(mandatoryLines.get(i))) {
                changed.add(mandatoryLines.get(i).trim());
            }
        }
        assertEquals(List.of(
                PromptService.REQUIRED_MESSAGE_FORMAT,
                PromptService.REQUIRED_MESSAGE_FIELD_RULE,
                PromptService.REQUIRED_MESSAGE_RULE), changed);
        assertFalse(mandatory.prompt().contains("OPTIONAL"));
        assertFalse(mandatory.prompt().contains("<optional"));
    }

    @Test
    void mandatoryPromptWithCustomInstructionsKeepsBothSuffixes() {
        PromptService.ResolvedPrompt prompt = promptService.buildSystemPrompt(
                null, "Play the London.", "BLACK", "Opponent Bot", "model/opponent", ChatMode.MANDATORY);

        assertTrue(prompt.prompt().contains(PromptService.REQUIRED_MESSAGE_RULE));
        assertTrue(prompt.prompt().contains("Play the London."));
        assertEquals("v2+custom-instructions-chat-required", prompt.version());
    }

    @Test
    void everyModeReplacesTheRulesItTargets() {
        String template = promptService.getDefaultSystemPromptTemplate();
        assertTrue(template.contains(PromptService.OPTIONAL_MESSAGE_FORMAT));
        assertTrue(template.contains(PromptService.OPTIONAL_MESSAGE_FIELD_RULE));
        assertTrue(template.contains(PromptService.TRASH_TALK_RULE));
        assertEquals(template, promptService.getSystemPromptTemplate(ChatMode.OPTIONAL));
        assertEquals("v2", promptService.getSystemPromptVersion(ChatMode.OPTIONAL));
        assertEquals("v2-silent", promptService.getSystemPromptVersion(ChatMode.OFF));
        assertEquals("v2-chat-required", promptService.getSystemPromptVersion(ChatMode.MANDATORY));
    }

    @Test
    void chatModesLeaveLegacyCustomTemplatesUntouched() {
        for (ChatMode mode : ChatMode.values()) {
            PromptService.ResolvedPrompt prompt = promptService.buildSystemPrompt(
                    "Play as %s against %s (%s). You can trash talk if you want.", null,
                    "WHITE", "Opponent Bot", "model/opponent", mode);

            assertEquals("Play as WHITE against Opponent Bot (model/opponent). You can trash talk if you want.",
                    prompt.prompt());
            assertEquals("legacy-custom-template", prompt.version());
            // A stored template cannot ask for a message, so MANDATORY applies as OPTIONAL.
            assertEquals(mode == ChatMode.MANDATORY ? ChatMode.OPTIONAL : mode, prompt.chatMode());
        }
    }

    @Test
    void aStoredCopyOfTheDefaultRulesIsNotALegacyOverrideAndFollowsTheChatMode() {
        for (ChatMode mode : ChatMode.values()) {
            PromptService.ResolvedPrompt prompt = promptService.buildSystemPrompt(
                    promptService.getDefaultSystemPromptTemplate().replace("\n", "\r\n") + "\n", null,
                    "WHITE", "Opponent Bot", "model/opponent", mode);

            assertEquals(promptService.getSystemPromptVersion(mode), prompt.version(), mode.name());
        }
    }

    /**
     * The silent rules as 1.3.1 rendered them (the default template with rule 5 replaced). A tournament or
     * participant that stored this text has always been a legacy custom template, used byte for byte: that must not
     * change in any chat mode (the hash is the one 1.3.1 recorded for the silent prompt).
     */
    private static final String STORED_SILENT_TEMPLATE_1_3_1 = """
            You are playing chess in a tournament match. This is a real competition.

            RULES:
            1. You MUST respond with valid JSON only. No markdown, no code blocks, no extra text.
            2. Response format:
               {
                 "move": "<your move in Standard Algebraic Notation>",
                 "message": "<optional message to your opponent>"
               }
            3. The "move" field is REQUIRED and must be a legal move in Standard Algebraic Notation (SAN).
               Examples: "e4", "Nf3", "O-O" (kingside castle), "O-O-O" (queenside castle), "Bxe5", "e8=Q"
            4. The "message" field is OPTIONAL.
            5. Trash talk is disabled in this tournament. Leave the "message" field empty ("") and do not address your opponent.
            6. Do NOT use tool calls or function calls. Respond with plain text JSON only.
            7. Do NOT wrap your response in markdown code blocks.
            8. Do NOT include any text before or after the JSON object.

            You are playing as %s in this game.
            Your opponent is %s (%s).

            Play your best chess. May the best model win.""";

    @Test
    void aStoredCopyOfTheSilentRulesStaysALegacyTemplateWithItsOriginalBytes() {
        String expected = STORED_SILENT_TEMPLATE_1_3_1.formatted("WHITE", "Opponent Bot", "model/opponent");
        for (ChatMode mode : ChatMode.values()) {
            PromptService.ResolvedPrompt prompt = promptService.buildSystemPrompt(
                    STORED_SILENT_TEMPLATE_1_3_1, "Ignored for legacy templates.",
                    "WHITE", "Opponent Bot", "model/opponent", mode);

            assertEquals("legacy-custom-template", prompt.version(), mode.name());
            assertEquals(expected, prompt.prompt(), mode.name());
            assertEquals("fccd19e6563fac4f88fc4f4b7a730ba09e1f1ae16e14e0f1bfc7b4d8810c0b05",
                    promptService.computePromptHash(prompt.prompt()), mode.name());
        }
        assertEquals("legacy-custom-template", promptService.resolvePromptVersion(STORED_SILENT_TEMPLATE_1_3_1));
        assertEquals("legacy-custom-template", promptService.resolvePromptVersion(
                promptService.getSystemPromptTemplate(ChatMode.MANDATORY)));
    }

    @Test
    void turnPromptAsksForAMessageOnlyWhenChatIsMandatory() {
        Board board = new Board();
        String optional = promptService.buildTurnPrompt(null, board.getFen(), "board", "WHITE", 1,
                List.of("e4"), "Opponent", null, null, true);
        String off = promptService.buildTurnPrompt(null, board.getFen(), "board", "WHITE", 1,
                List.of("e4"), "Opponent", null, null, true, ChatMode.OFF);
        String mandatory = promptService.buildTurnPrompt(null, board.getFen(), "board", "WHITE", 1,
                List.of("e4"), "Opponent", null, null, true, ChatMode.MANDATORY);

        assertTrue(optional.endsWith("Respond with your move as JSON: {\"move\": \"<SAN>\", \"message\": \"<optional>\"}"));
        assertEquals(optional, off);
        assertTrue(mandatory.endsWith("Respond with your move and a short message as JSON: "
                + "{\"move\": \"<SAN>\", \"message\": \"<your message>\"}"));
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
