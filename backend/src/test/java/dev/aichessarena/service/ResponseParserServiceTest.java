package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ResponseParserServiceTest {

    private ResponseParserService parser;

    @BeforeEach
    void setUp() {
        parser = new ResponseParserService();
        parser.objectMapper = new ObjectMapper();
    }

    @Test
    void aNullMessageIsNoMessageNotTheTextNull() {
        ResponseParserService.ParseResult result = parser.parse("{\"move\":\"Nf3\",\"message\":null}");

        assertEquals("Nf3", result.move());
        assertNull(result.message());
    }

    @Test
    void parseAcceptsRawJsonResponse() {
        ResponseParserService.ParseResult result = parser.parse("{\"move\":\"Nf3\",\"message\":\"Your king looks shaky.\"}");

        assertFalse(result.isFailure());
        assertEquals("Nf3", result.move());
        assertEquals("Your king looks shaky.", result.message());
        assertEquals("JSON", result.parseMethod());
    }

    @Test
    void parseExtractsMoveFromMarkdownJsonBlock() {
        String content = "```json\n{\"move\":\"e4\"}\n```";
        ResponseParserService.ParseResult result = parser.parse(content);

        assertFalse(result.isFailure());
        assertEquals("e4", result.move());
        assertEquals("JSON", result.parseMethod());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Qh5+", "e4#", "O-O", "O-O+", "O-O-O", "O-O-O+", "O-O-O#"})
    void parseFallsBackToPlainTextMoveExtraction(String proposed) {
        String content = "My move is " + proposed + ".";
        ResponseParserService.ParseResult result = parser.parse(content);

        assertFalse(result.isFailure());
        assertEquals(proposed, result.move());
        assertEquals("PLAIN_TEXT_EXTRACTION", result.parseMethod());
    }

    @Test
    void plainTextQueensideCastleWithDoublePlusPlaysTheMatingMove() {
        ChessService chess = new ChessService();
        var board = chess.boardFromFen("2rkr3/2p1p3/8/8/8/8/8/R3K2R w KQ - 0 1");
        assertTrue(chess.getLegalMovesAsSan(board).contains("O-O"));

        var result = parser.parse("My move is O-O-O++.");
        assertEquals("O-O-O++", result.move());
        var played = chess.validateAndApply(board, result.move());
        assertEquals("O-O-O#", played.san());
        assertTrue(board.isMated());
    }

    @ParameterizedTest
    @ValueSource(strings = {"O-O-O+++", "O-O-O-O"})
    void plainTextExtractionSkipsMalformedCastlingTokens(String malformed) {
        assertTrue(parser.parse("My move is " + malformed + ".").isFailure());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0-0-0", "0-0-0+", "0-0-0#", "0-0-0++"})
    void jsonPreservesZeroSpelledCastling(String proposed) {
        assertEquals(proposed, parser.parse("{\"move\":\"" + proposed + "\"}").move());
    }

    @Test
    void aResignationNeedsNoMove() {
        ResponseParserService.ParseResult result = parser.parse("{\"resign\": true, \"message\": \"Well played.\"}");

        assertFalse(result.isFailure());
        assertTrue(result.resign());
        assertNull(result.move());
        assertEquals("Well played.", result.message());
        assertEquals("JSON", result.parseMethod());

        ResponseParserService.ParseResult bare = parser.parse("{\"resign\":true}");
        assertTrue(bare.resign());
        assertNull(bare.message());
    }

    @Test
    void aResignationKeepsAMoveSentAlongForTheCallerToDecide() {
        ResponseParserService.ParseResult result = parser.parse("{\"move\":\"Qxf7\",\"resign\":true,\"message\":\"gg\"}");

        assertTrue(result.resign());
        assertEquals("Qxf7", result.move());
        assertEquals("gg", result.message());
    }

    @Test
    void aResignationIsFoundInMarkdownAndInSurroundingText() {
        ResponseParserService.ParseResult block = parser.parse("```json\n{\"resign\": true}\n```");
        assertTrue(block.resign());

        ResponseParserService.ParseResult text =
                parser.parse("The position is lost. {\"resign\": \"true\", \"message\": \"You win.\"} Thanks!");
        assertTrue(text.resign());
        assertNull(text.move());
        assertEquals("You win.", text.message());

        ResponseParserService.ParseResult withMove =
                parser.parse("Thinking... {\"resign\": true, \"move\": \"e5\"}");
        assertTrue(withMove.resign());
        assertEquals("e5", withMove.move());
    }

    @Test
    void onlyTrueResigns() {
        ResponseParserService.ParseResult notResigning = parser.parse("{\"move\":\"e4\",\"resign\":false}");
        assertFalse(notResigning.resign());
        assertEquals("e4", notResigning.move());

        for (String malformed : new String[] {"{\"resign\":false}", "{\"resign\":\"yes\"}", "{\"resign\":1}",
                "{\"resign\":null}", "{\"resign\":true", "{\"Resign\":true}"}) {
            ResponseParserService.ParseResult result = parser.parse(malformed);
            assertFalse(result.resign(), malformed);
            assertTrue(result.isFailure(), malformed);
        }
    }

    /**
     * Regression: wrapped in text, only a lowercase, unpadded "true" was recognised, so
     * {@code Answer: {"resign":"TRUE","message":"e4"}} played e4 from the message instead of resigning.
     */
    @Test
    void theResignFlagIsRecognisedTheSameWayRawInACodeBlockAndWrappedInText() {
        for (String value : new String[] {"true", "\"true\"", "\"TRUE\"", "\"True\"", "\" true \"", "\"  TRUE\""}) {
            String json = "{\"resign\":" + value + ",\"message\":\"e4\"}";
            for (String answer : new String[] {json, "```json\n" + json + "\n```", "Answer: " + json,
                    "I resign. " + json + " Good game."}) {
                ResponseParserService.ParseResult result = parser.parse(answer);
                assertTrue(result.resign(), answer);
                assertNull(result.move(), answer);
                assertEquals("e4", result.message(), answer);
            }
        }
    }

    @Test
    void aWrappedNonResigningObjectFallsBackLikeARawOne() {
        for (String json : new String[] {"{\"resign\":\"no\",\"message\":\"e4\"}", "{\"resign\":false,\"message\":\"e4\"}"}) {
            ResponseParserService.ParseResult raw = parser.parse(json);
            ResponseParserService.ParseResult wrapped = parser.parse("Answer: " + json);
            assertFalse(raw.resign(), json);
            assertFalse(wrapped.resign(), json);
            assertEquals(raw.move(), wrapped.move(), json);
        }
        // A later object in the text that does resign is still found.
        assertTrue(parser.parse("Draft {\"resign\":false} final {\"resign\":\"TRUE\"}").resign());
    }

    @Test
    void aResignationWithANullOrBlankMoveHasNoMove() {
        assertNull(parser.parse("{\"resign\":true,\"move\":null}").move());
        assertNull(parser.parse("{\"resign\":true,\"move\":\"  \"}").move());
        assertTrue(parser.parse("{\"resign\":true,\"move\":\"  \"}").resign());
    }
}
