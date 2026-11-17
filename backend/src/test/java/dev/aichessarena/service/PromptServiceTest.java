package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.github.bhlangonijr.chesslib.Board;
import dev.aichessarena.entity.ChatMode;
import dev.aichessarena.entity.BanterLevel;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PromptServiceTest {

    private final PromptService promptService = new PromptService();

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void absentLastSanOmitsTheOpponentMoveSentence(String lastSan) {
        String prompt = promptService.buildTurnPrompt(null, new Board().getFen(), "board", "BLACK", 1,
                List.of("e5"), "Opponent", lastSan, "Older message", false);

        assertFalse(prompt.contains("Your opponent"), prompt);
        assertFalse(prompt.contains("Older message"), prompt);
        assertFalse(prompt.contains("null"), prompt);
    }

    @Test
    void legalMoveHintsPreserveLegacyTurnAndFinalRetryBytes() {
        assertEquals("""
                Current game state:

                PGN: (starting position)

                FEN: position

                Board:
                board

                It is your turn (WHITE to move). Move 1.
                Legal moves: e4, Nf3

                You have the first move. Open the game.

                Respond with your move as JSON: {"move": "<SAN>", "message": "<optional>"}""",
                promptService.buildTurnPrompt(null, "position", "board", "WHITE", 1, List.of("e4", "Nf3"),
                        "Opponent", null, null, true));
        assertEquals("""
                Your previous response was invalid.

                Reason

                Examples of valid responses:
                  {"move": "e4", "message": "King's pawn opening!"}
                  {"move": "Nf3"}

                Current position FEN: position
                Legal moves: e4, Nf3

                Please respond with ONLY a JSON object containing your move.
                Attempt 3 of 3.

                LAST CHANCE: you already made 2 invalid attempts. This is your final chance. If this response is invalid, you lose by forfeit.""",
                promptService.buildRetryPrompt("Reason", "position", List.of("e4", "Nf3"), 3, 3));
    }

    @Test
    void absentHintsOmitTheListAndKeepPositionAndResponseInstructions() {
        String turn = promptService.buildTurnPrompt(null, "position", "board", "WHITE", 10, null,
                "Opponent", "c5", null, false, ChatMode.MANDATORY, true);
        assertFalse(turn.contains("Legal moves:"));
        assertTrue(turn.contains("FEN: position"));
        assertTrue(turn.contains("You may resign"));
        assertTrue(turn.contains(PromptService.REQUIRED_MESSAGE_ANSWER_LINE));

        String retry = promptService.buildRetryPrompt("Reason", "position", null, 3, 3);
        assertFalse(retry.contains("Legal moves:"));
        assertTrue(retry.contains("Current position FEN: position"));
        assertTrue(retry.contains("LAST CHANCE"));
        assertTrue(retry.contains("{\"move\": \"<SAN>\"}"));
    }

    @Test
    void systemPromptVersionDistinguishesNoHintsWithCustomInstructionsOrLegacyTemplates() {
        var hinted = promptService.buildSystemPrompt(null, "Play sharp openings.", "WHITE", "Opponent", "model",
                ChatMode.MANDATORY, true, BanterLevel.PLAYFUL);
        var unhinted = promptService.buildSystemPrompt(null, "Play sharp openings.", "WHITE", "Opponent", "model",
                ChatMode.MANDATORY, true, BanterLevel.PLAYFUL, false);
        assertEquals(hinted.version() + "-no-hints", unhinted.version());
        assertNotEquals(promptService.computePromptHash(hinted.prompt()), promptService.computePromptHash(unhinted.prompt()));
        assertTrue(unhinted.prompt().contains(PromptService.NO_HINTS_RULE));
        assertTrue(unhinted.prompt().contains("Play sharp openings."));
        assertTrue(unhinted.resignationOffered());

        String legacyTemplate = "You are %s. Opponent: %s (%s).";
        var legacy = promptService.buildSystemPrompt(legacyTemplate, null, "WHITE", "Opponent", "model",
                ChatMode.OPTIONAL, false, null, false);
        assertEquals("legacy-custom-template-no-hints", legacy.version());
        assertEquals("You are WHITE. Opponent: Opponent (model).", legacy.prompt());
    }

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

    /** The MANDATORY prompts as 1.4 introduced them (#38); resignation must not change them either. */
    @Test
    void mandatoryPromptsAndVersionsAreUnchanged() {
        assertPrompt(ChatMode.MANDATORY, null, "v2-chat-required",
                "818eb691f98294ccd6ac69b211f214381f7b67a329e63941b075c18f31e1f631");
        assertPrompt(ChatMode.MANDATORY, "Play the London.", "v2+custom-instructions-chat-required",
                "5dc363397e26a42d0ad30559ffb89cd41258e093529c0c08883a5d6f69089822");
    }

    private void assertPrompt(ChatMode mode, String customInstructions, String version, String hash) {
        PromptService.ResolvedPrompt prompt = promptService.buildSystemPrompt(
                null, customInstructions, "WHITE", "Opponent Bot", "model/opponent", mode);
        assertEquals(version, prompt.version());
        assertEquals(hash, promptService.computePromptHash(prompt.prompt()), mode + " prompt text changed");
        // Without resignation the explicit overload renders exactly the same prompt.
        PromptService.ResolvedPrompt withoutResignation = promptService.buildSystemPrompt(
                null, customInstructions, "WHITE", "Opponent Bot", "model/opponent", mode, false);
        assertEquals(prompt.prompt(), withoutResignation.prompt());
        assertEquals(version, withoutResignation.version());
        assertFalse(withoutResignation.resignationOffered());
        assertFalse(prompt.prompt().contains("resign"), mode + " prompt mentions resignation");
    }

    /** The resignation variants, pinned like the others so a text change shows up as a new hash. */
    @Test
    void resignationPromptsHaveTheirOwnVersionsAndHashes() {
        assertResignationPrompt(ChatMode.OPTIONAL, null, "v2-resign",
                "e8659256d65d0931ce93c47394e3a1bb566c22a821045784a0944c226887ec44");
        assertResignationPrompt(ChatMode.OPTIONAL, "Play the London.", "v2+custom-instructions-resign",
                "0b8855423c8e4832ff9849ed1eaf8824851e501ae9aa02d65156012311e01fd3");
        assertResignationPrompt(ChatMode.OFF, null, "v2-silent-resign",
                "8a838783553b8628c1b00e2158fffa62f1a59f942b32f0b18dc4fe361a5721ce");
        assertResignationPrompt(ChatMode.OFF, "Play the London.", "v2+custom-instructions-silent-resign",
                "8cf2c5c6207d2e76b1ba7635c6c356061dd7d68b56d99b75a36aaf1cf8e9781f");
        assertResignationPrompt(ChatMode.MANDATORY, null, "v2-chat-required-resign",
                "f8e4a11e3ba240852a8f33a43e177887bb0be06001df22782966cab3ac898536");
        assertResignationPrompt(ChatMode.MANDATORY, "Play the London.",
                "v2+custom-instructions-chat-required-resign",
                "14ae0ce4dc14d094c5cf6c4fc9566c0499eb1bdc4744e9c2dd0a4f3029c5cda2");
    }

    private void assertResignationPrompt(ChatMode mode, String customInstructions, String version, String hash) {
        PromptService.ResolvedPrompt prompt = promptService.buildSystemPrompt(
                null, customInstructions, "WHITE", "Opponent Bot", "model/opponent", mode, true);
        assertEquals(version, prompt.version());
        assertTrue(prompt.resignationOffered());
        assertEquals(mode, prompt.chatMode());
        assertEquals(hash, promptService.computePromptHash(prompt.prompt()), mode + " resignation prompt changed");
    }

    @Test
    void resignationAddsRuleNineAfterRuleEightAndChangesNothingElse() {
        for (ChatMode mode : ChatMode.values()) {
            String base = promptService.buildSystemPrompt(
                    null, null, "BLACK", "Opponent Bot", "model/opponent", mode, false).prompt();
            String resign = promptService.buildSystemPrompt(
                    null, null, "BLACK", "Opponent Bot", "model/opponent", mode, true).prompt();

            List<String> added = new ArrayList<>(resign.lines().toList());
            int ruleNine = added.indexOf(added.stream().filter(line -> line.startsWith("9. ")).findFirst().orElseThrow());
            assertEquals(PromptService.LAST_RULE, added.get(ruleNine - 1), mode.name());
            String rule = added.remove(ruleNine);
            assertEquals(base.lines().toList(), added, mode + ": only rule 9 is added");

            assertEquals(mode == ChatMode.OFF ? SILENT_RESIGNATION_RULE : RESIGNATION_RULE_WITH_LAST_WORDS, rule,
                    mode.name());
        }
    }

    /** Rule 9 word for word: both players may resign (the opponent too), stated as a fact, no advice either way. */
    private static final String RESIGNATION_RULE_WITH_LAST_WORDS = "9. Resigning is allowed in this tournament: both "
            + "players may resign from their own move 10 on (book moves do not count), so your opponent can resign too. Instead of a move you may "
            + "then answer {\"resign\": true, \"message\": \"<optional last words to your opponent>\"} and the game "
            + "ends at once: your opponent wins. This is the only exception to rule 3. If you resign, any \"move\" in "
            + "the same answer is ignored.";

    /** With chat OFF the resigning answer has no message. */
    private static final String SILENT_RESIGNATION_RULE = "9. Resigning is allowed in this tournament: both players "
            + "may resign from their own move 10 on (book moves do not count), so your opponent can resign too. Instead of a move you may then "
            + "answer {\"resign\": true} and the game ends at once: your opponent wins. This is the only exception to "
            + "rule 3. If you resign, any \"move\" in the same answer is ignored.";

    @Test
    void theTemplatesForTheSetupPageFollowTheResignationSetting() {
        for (ChatMode mode : ChatMode.values()) {
            assertEquals(promptService.getSystemPromptTemplate(mode), promptService.getSystemPromptTemplate(mode, false));
            assertEquals(promptService.getSystemPromptVersion(mode), promptService.getSystemPromptVersion(mode, false));
            assertTrue(promptService.getSystemPromptTemplate(mode, true).contains("9. Resigning is allowed"));
            assertEquals(promptService.getSystemPromptVersion(mode) + "-resign",
                    promptService.getSystemPromptVersion(mode, true));
        }
    }

    @Test
    void legacyCustomTemplatesNeverOfferResignation() {
        for (ChatMode mode : ChatMode.values()) {
            PromptService.ResolvedPrompt prompt = promptService.buildSystemPrompt(
                    "Play as %s against %s (%s).", null, "WHITE", "Opponent Bot", "model/opponent", mode, true);

            assertEquals("Play as WHITE against Opponent Bot (model/opponent).", prompt.prompt());
            assertEquals("legacy-custom-template", prompt.version());
            assertFalse(prompt.resignationOffered(), mode.name());
        }
    }

    @Test
    void turnPromptMentionsResignationOnlyWhenTheSideMayResign() {
        Board board = new Board();
        String plain = promptService.buildTurnPrompt("1. e4 e5", board.getFen(), "board", "WHITE", 10,
                List.of("e4"), "Opponent", "e5", null, false, ChatMode.MANDATORY);
        String notYet = promptService.buildTurnPrompt("1. e4 e5", board.getFen(), "board", "WHITE", 10,
                List.of("e4"), "Opponent", "e5", null, false, ChatMode.MANDATORY, false);
        String mayResign = promptService.buildTurnPrompt("1. e4 e5", board.getFen(), "board", "WHITE", 10,
                List.of("e4"), "Opponent", "e5", null, false, ChatMode.MANDATORY, true);

        assertEquals(plain, notYet);
        assertFalse(plain.contains("resign"));
        assertEquals(plain.replace(PromptService.REQUIRED_MESSAGE_ANSWER_LINE,
                "You may resign instead of moving (rule 9).\n" + PromptService.REQUIRED_MESSAGE_ANSWER_LINE), mayResign);
    }

    @Test
    void theCorrectiveMessageSaysWhyResigningIsNotAllowed() {
        assertEquals("Resigning is not allowed in this game; send a legal move.",
                promptService.getResignationNotAllowedError(false));
        assertEquals("Resigning is not allowed before your own move 10 (book moves do not count); send a legal move.",
                promptService.getResignationNotAllowedError(true));
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
     * Measures the legacy (prompt caching disabled) window over a 70-ply game: system prompt, six prior messages and
     * the current turn. The setup page's rough estimate uses
     * {@code PROMPT_TOKENS_PER_MOVE = 1400} in tournament-setup.ts; at roughly 3.2 characters per token for
     * FEN, SAN lists and the ASCII board this averages about 4,500 characters (about 1,600 at the first move,
     * about 5,000 late in the game). Update both if the prompts change a lot.
     */
    @Test
    void legacyTurnWindowAveragesAboutFourteenHundredPromptTokens() {
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
            totalChars += ConversationWindow.trim(history, turnStart, ConversationWindow.LEGACY_PRIOR_MESSAGES)
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

    @Test
    void chunkedGamePromptsKeepBookPgnAndEachSidesPrefixWithinTheMessageBudget() {
        ChessService chess = new ChessService();
        Board board = chess.createBoard();
        for (String move : List.of("e4", "e5", "Nf3", "Nc6", "Bc4", "Bc5", "c3", "Nf6")) {
            assertTrue(chess.validateAndApply(board, move).valid());
        }
        String bookPgn = "1. e4 e5 2. Nf3 Nc6 3. Bc4 Bc5 4. c3 Nf6";
        String pgn = bookPgn;
        String lastMove = "Nf6";
        List<List<OpenRouterService.ChatMsg>> histories = List.of(
                new ArrayList<>(List.of(system("WHITE", "Beta"))),
                new ArrayList<>(List.of(system("BLACK", "Alpha"))));
        List<List<OpenRouterService.ChatMsg>> previous = new ArrayList<>(List.of(List.of(), List.of()));
        int boundaries = 0;
        int plies = 0;
        for (int ply = 0; ply < 70 && !chess.isGameOver(board); ply++) {
            boolean white = "WHITE".equals(chess.getSideToMove(board));
            int side = white ? 0 : 1;
            List<OpenRouterService.ChatMsg> history = histories.get(side);
            OpenRouterService.ChatMsg system = history.getFirst();
            boolean reanchored = history.size() - 1 > ConversationWindow.DEFAULT_MAX_PRIOR_MESSAGES;
            ConversationWindow.reanchor(history, ConversationWindow.DEFAULT_MAX_PRIOR_MESSAGES,
                    ConversationWindow.DEFAULT_RETAINED_MESSAGES);
            assertSame(system, history.getFirst());
            assertTrue(history.size() - 1 <= 12, "system and current-turn suffix are outside the prior budget");
            if (reanchored) {
                boundaries++;
                assertEquals(5, history.size(), "keep exactly two completed exchanges in this retry-free game");
            }
            List<String> legal = chess.getLegalMovesAsSan(board);
            int moveNumber = chess.getMoveNumber(board);
            String turn = promptService.buildTurnPrompt(pgn, board.getFen(), chess.toAsciiBoard(board),
                    white ? "WHITE" : "BLACK", moveNumber, legal, white ? "Beta" : "Alpha", lastMove, "Nice try.", false);
            assertTrue(turn.contains(pgn));
            assertTrue(turn.contains(bookPgn), "book PGN is still available after every reanchor");
            assertTrue(turn.contains(board.getFen()));
            assertTrue(turn.contains(chess.toAsciiBoard(board)));
            assertTrue(turn.contains(lastMove));
            assertTrue(turn.contains("Nice try."));
            for (String san : legal) {
                assertTrue(turn.contains(san));
            }
            history.add(new OpenRouterService.ChatMsg("user", turn, true));
            if (!reanchored && !previous.get(side).isEmpty()) {
                assertEquals(previous.get(side), history.subList(0, previous.get(side).size()));
            }
            previous.set(side, List.copyOf(history));
            String san = legal.get((ply * 7 + 3) % legal.size());
            history.add(new OpenRouterService.ChatMsg("assistant", "{\"move\":\"" + san + "\"}"));
            assertTrue(chess.validateAndApply(board, san).valid());
            pgn += " " + (white ? moveNumber + ". " : "") + san;
            lastMove = san;
            plies++;
        }
        assertTrue(plies >= 40, "exercise a long game: " + plies);
        assertTrue(boundaries >= 4, "exercise reanchoring for both sides: " + boundaries);
    }

    private OpenRouterService.ChatMsg system(String color, String opponent) {
        return new OpenRouterService.ChatMsg("system",
                promptService.buildSystemPrompt(null, null, color, opponent, "vendor/" + opponent.toLowerCase())
                        .prompt());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("banterPins")
    void banterVariantsHavePinnedTextsVersionsAndHashes(BanterLevel level, ChatMode mode, boolean resign,
                                                       boolean custom, String version, String hash) {
        var resolved = promptService.buildSystemPrompt(null, custom ? "Play the London." : null,
                "WHITE", "Opponent Bot", "model/opponent", mode, resign, level);
        assertEquals(version, resolved.version());
        assertEquals(hash, promptService.computePromptHash(resolved.prompt()));
        assertEquals(mode, resolved.chatMode());
        assertEquals(resign, resolved.resignationOffered());
        assertTrue(resolved.prompt().contains(PromptService.toneSentence(level)));
        if (!custom) {
            assertEquals(resolved.prompt(), promptService.getSystemPromptTemplate(mode, resign, level)
                    .formatted("WHITE", "Opponent Bot", "model/opponent"));
            assertEquals(version, promptService.getSystemPromptVersion(mode, resign, level));
        }
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> banterPins() {
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(BanterLevel.POLITE, ChatMode.OPTIONAL, false, false,
                        "v2-banter-polite", "995ef8bc61567c840528cef34da594c45fc594cb68a3d65428c7139f827991d8"),
                org.junit.jupiter.params.provider.Arguments.of(BanterLevel.POLITE, ChatMode.OPTIONAL, false, true,
                        "v2+custom-instructions-banter-polite", "faa27d85a8387dec37d23005c8b058aea672c25137c083a5efe829751d7704e6"),
                org.junit.jupiter.params.provider.Arguments.of(BanterLevel.POLITE, ChatMode.OPTIONAL, true, false,
                        "v2-resign-banter-polite", "4ae1e0e058d8ffe20753ce08a83cf13e4d4b9021b6f47c3776c015853520ca0a"),
                org.junit.jupiter.params.provider.Arguments.of(BanterLevel.POLITE, ChatMode.OPTIONAL, true, true,
                        "v2+custom-instructions-resign-banter-polite", "408db590a72512e4e714a44132f4f00dc42f1661b8258f63de5468c687e20b97"),
                org.junit.jupiter.params.provider.Arguments.of(BanterLevel.POLITE, ChatMode.MANDATORY, false, false,
                        "v2-chat-required-banter-polite", "dd00735b113b6ac7983195f2287b528516c075a3df19f56f6c47d9934d6cbb20"),
                org.junit.jupiter.params.provider.Arguments.of(BanterLevel.POLITE, ChatMode.MANDATORY, false, true,
                        "v2+custom-instructions-chat-required-banter-polite", "bf57adccb94965bbbaff112f69d6a446bd311b7fee8943c45c2613907d42113a"),
                org.junit.jupiter.params.provider.Arguments.of(BanterLevel.POLITE, ChatMode.MANDATORY, true, false,
                        "v2-chat-required-resign-banter-polite", "d839a3362d35afbc781ba63520381c4a863c04c5e3f7aabc3323979d4ae15076"),
                org.junit.jupiter.params.provider.Arguments.of(BanterLevel.POLITE, ChatMode.MANDATORY, true, true,
                        "v2+custom-instructions-chat-required-resign-banter-polite", "5051206cf24630d2f5ace817f90de7cfa7342db64ab04ddd872f78e44264b7ee"),
                org.junit.jupiter.params.provider.Arguments.of(BanterLevel.PLAYFUL, ChatMode.OPTIONAL, false, false,
                        "v2-banter-playful", "a00b90ae67b5e618788dff9eeeea758e0770bea4f69baa2309e0981fe5a93fb8"),
                org.junit.jupiter.params.provider.Arguments.of(BanterLevel.PLAYFUL, ChatMode.OPTIONAL, false, true,
                        "v2+custom-instructions-banter-playful", "c4d241a3c7b93742815d13fbccdc5cf703bc950e1277c0aa529aa6605fd6b3cd"),
                org.junit.jupiter.params.provider.Arguments.of(BanterLevel.PLAYFUL, ChatMode.OPTIONAL, true, false,
                        "v2-resign-banter-playful", "07c6c1f30acefa6f45a7e58f45926535f0b9f64eff0930e5cc7b6d70d59f723c"),
                org.junit.jupiter.params.provider.Arguments.of(BanterLevel.PLAYFUL, ChatMode.OPTIONAL, true, true,
                        "v2+custom-instructions-resign-banter-playful", "2a1c6347ec13b3f3cad7354887a23b31e80f4558ccf7cf72c21de0248bfb55be"),
                org.junit.jupiter.params.provider.Arguments.of(BanterLevel.PLAYFUL, ChatMode.MANDATORY, false, false,
                        "v2-chat-required-banter-playful", "94992bbbedb3b5180c474f0c5da08db2e714151c8494855eed8d0efe5865dc6f"),
                org.junit.jupiter.params.provider.Arguments.of(BanterLevel.PLAYFUL, ChatMode.MANDATORY, false, true,
                        "v2+custom-instructions-chat-required-banter-playful", "91419a0ed96754649c3e0a58cb33daf7428792bf857cf12e5a05e2c130b6b52f"),
                org.junit.jupiter.params.provider.Arguments.of(BanterLevel.PLAYFUL, ChatMode.MANDATORY, true, false,
                        "v2-chat-required-resign-banter-playful", "589196cbef5f8bd05aee042cc4c2cddd75de5643c88f27b3eb310a739df7f97c"),
                org.junit.jupiter.params.provider.Arguments.of(BanterLevel.PLAYFUL, ChatMode.MANDATORY, true, true,
                        "v2+custom-instructions-chat-required-resign-banter-playful", "0ed164c68b1e0609ef8e38fa39ad93c247fe64041c5d73bc0195f17950a30fbc"),
                org.junit.jupiter.params.provider.Arguments.of(BanterLevel.SPICY, ChatMode.OPTIONAL, false, false,
                        "v2-banter-spicy", "613e8aa068d5478d7cc7a6314fc037fc742657de156b3fe5cd59e5857a6962f7"),
                org.junit.jupiter.params.provider.Arguments.of(BanterLevel.SPICY, ChatMode.OPTIONAL, false, true,
                        "v2+custom-instructions-banter-spicy", "fd4e030e2dc63111a6822b102fd232e882c9046815a73154d34361d07ddef726"),
                org.junit.jupiter.params.provider.Arguments.of(BanterLevel.SPICY, ChatMode.OPTIONAL, true, false,
                        "v2-resign-banter-spicy", "3f8b886fe6628b866ff5d039e055253fc871ad25bd4c16e47d5c320cc592fd3a"),
                org.junit.jupiter.params.provider.Arguments.of(BanterLevel.SPICY, ChatMode.OPTIONAL, true, true,
                        "v2+custom-instructions-resign-banter-spicy", "7b5c188536c5e3ac9cbd2ce67682f00886c2ae8bd8f16f1064ebe32e61759e57"),
                org.junit.jupiter.params.provider.Arguments.of(BanterLevel.SPICY, ChatMode.MANDATORY, false, false,
                        "v2-chat-required-banter-spicy", "2dc647f16a499540bbc4d4224da34f786139bfe6067c1f7887fbeb81c0849048"),
                org.junit.jupiter.params.provider.Arguments.of(BanterLevel.SPICY, ChatMode.MANDATORY, false, true,
                        "v2+custom-instructions-chat-required-banter-spicy", "23528bc052ab5f7204e9db2bec556301473e9e0da807fbfb9a7df17314be2735"),
                org.junit.jupiter.params.provider.Arguments.of(BanterLevel.SPICY, ChatMode.MANDATORY, true, false,
                        "v2-chat-required-resign-banter-spicy", "cd9487e50af01974c6f46ceaf0baa900ae250613e3f19de519ac9413ccdae44e"),
                org.junit.jupiter.params.provider.Arguments.of(BanterLevel.SPICY, ChatMode.MANDATORY, true, true,
                        "v2+custom-instructions-chat-required-resign-banter-spicy", "19f120b2a0593de8fe1451a7b9a8700e0a318ce426229084f70e21e448b6abb3"));
    }

    @Test
    void nullBanterOffAndLegacyTemplatesPreserveAllExistingPrompts() {
        String legacy = "Play %s against %s (%s).";
        for (ChatMode mode : ChatMode.values()) {
            for (boolean resign : new boolean[] {false, true}) {
                for (String custom : new String[] {null, "Play the London."}) {
                    var original = promptService.buildSystemPrompt(null, custom, "WHITE", "Opponent Bot",
                            "model/opponent", mode, resign);
                    assertEquals(original, promptService.buildSystemPrompt(null, custom, "WHITE", "Opponent Bot",
                            "model/opponent", mode, resign, null));
                    for (BanterLevel level : BanterLevel.values()) {
                        if (mode == ChatMode.OFF) {
                            assertEquals(original, promptService.buildSystemPrompt(null, custom, "WHITE", "Opponent Bot",
                                    "model/opponent", mode, resign, level));
                        }
                        assertEquals(promptService.buildSystemPrompt(legacy, custom, "WHITE", "Opponent Bot",
                                        "model/opponent", mode, resign),
                                promptService.buildSystemPrompt(legacy, custom, "WHITE", "Opponent Bot",
                                        "model/opponent", mode, resign, level));
                    }
                }
            }
        }
    }
}
