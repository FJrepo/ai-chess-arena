package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

    @Test
    void parseFallsBackToPlainTextMoveExtraction() {
        String content = "I will play Qh5+ now.";
        ResponseParserService.ParseResult result = parser.parse(content);

        assertFalse(result.isFailure());
        assertEquals("Qh5", result.move());
        assertEquals("PLAIN_TEXT_EXTRACTION", result.parseMethod());
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

    @Test
    void aResignationWithANullOrBlankMoveHasNoMove() {
        assertNull(parser.parse("{\"resign\":true,\"move\":null}").move());
        assertNull(parser.parse("{\"resign\":true,\"move\":\"  \"}").move());
        assertTrue(parser.parse("{\"resign\":true,\"move\":\"  \"}").resign());
    }
}
