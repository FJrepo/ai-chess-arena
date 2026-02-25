package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
}
