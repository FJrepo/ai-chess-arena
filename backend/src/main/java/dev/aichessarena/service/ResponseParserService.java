package dev.aichessarena.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class ResponseParserService {

    private static final Pattern JSON_BLOCK = Pattern.compile("```(?:json)?\\s*(\\{.*?})\\s*```", Pattern.DOTALL);
    private static final Pattern JSON_OBJECT = Pattern.compile("\\{[^{}]*\"move\"\\s*:\\s*\"[^\"]+\"[^{}]*}");
    private static final Pattern MOVE_PATTERN = Pattern.compile(
            "\\b([KQRBN]?[a-h]?[1-8]?x?[a-h][1-8](?:=[QRBN])?[+#]?|O-O(?:-O)?)\\b"
    );

    @Inject
    ObjectMapper objectMapper;

    public ParseResult parse(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return ParseResult.failure("Empty response");
        }

        // 1. Try direct JSON parse
        ParseResult result = tryParseJson(rawResponse.trim());
        if (result != null) return result;

        // 2. Try extracting from markdown code block
        Matcher blockMatcher = JSON_BLOCK.matcher(rawResponse);
        if (blockMatcher.find()) {
            result = tryParseJson(blockMatcher.group(1).trim());
            if (result != null) return result;
        }

        // 3. Try finding JSON object with "move" field
        Matcher objectMatcher = JSON_OBJECT.matcher(rawResponse);
        if (objectMatcher.find()) {
            result = tryParseJson(objectMatcher.group().trim());
            if (result != null) return result;
        }

        // 4. Try extracting a move from plain text
        Matcher moveMatcher = MOVE_PATTERN.matcher(rawResponse);
        if (moveMatcher.find()) {
            return new ParseResult(moveMatcher.group(1), null, "PLAIN_TEXT_EXTRACTION");
        }

        return ParseResult.failure("Could not extract a move from the response");
    }

    private ParseResult tryParseJson(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node.has("move")) {
                String move = node.get("move").asText();
                String message = node.has("message") ? node.get("message").asText() : null;
                if (move != null && !move.isBlank()) {
                    return new ParseResult(move.trim(), message, "JSON");
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public record ParseResult(String move, String message, String parseMethod) {
        public boolean isFailure() {
            return move == null;
        }

        public static ParseResult failure(String reason) {
            return new ParseResult(null, reason, "FAILURE");
        }
    }
}
