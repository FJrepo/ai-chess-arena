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
    /** Any flat JSON object with a "resign" field; its value is judged by {@link #isResignation}. */
    private static final Pattern RESIGN_OBJECT = Pattern.compile("\\{[^{}]*\"resign\"\\s*:[^{}]*}");
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

        // 3b. Try every JSON object with a "resign" field (it may have no "move" field). Whether it resigns is
        // decided by the same rule as for a raw answer (isResignation), before any move is taken from plain text.
        Matcher resignMatcher = RESIGN_OBJECT.matcher(rawResponse);
        while (resignMatcher.find()) {
            result = tryParseJson(resignMatcher.group().trim());
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
            if (node == null || !node.isObject()) {
                return null;
            }
            // "message": null means no message (asText() would turn it into the text "null").
            String message = node.hasNonNull("message") ? node.get("message").asText() : null;
            if (isResignation(node.get("resign"))) {
                // "move" may be omitted when resigning; one sent along is kept so the caller can play it when
                // resigning is not allowed (an allowed resignation ignores it).
                String move = node.hasNonNull("move") ? node.get("move").asText().trim() : null;
                return new ParseResult(move == null || move.isBlank() ? null : move, message, "JSON", true);
            }
            if (node.has("move")) {
                String move = node.get("move").asText();
                if (move != null && !move.isBlank()) {
                    return new ParseResult(move.trim(), message, "JSON");
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Only {@code "resign": true} (or the string "true") resigns; false, null, numbers or other text do not. */
    private static boolean isResignation(JsonNode resign) {
        if (resign == null) {
            return false;
        }
        if (resign.isBoolean()) {
            return resign.booleanValue();
        }
        return resign.isTextual() && "true".equalsIgnoreCase(resign.textValue().trim());
    }

    /**
     * A parsed answer. {@code resign} is true for an answer with {@code "resign": true}; {@code move} is then the move
     * sent along with it, if any. A failure has neither a move nor a resignation, and {@code message} is the reason.
     */
    public record ParseResult(String move, String message, String parseMethod, boolean resign) {
        public ParseResult(String move, String message, String parseMethod) {
            this(move, message, parseMethod, false);
        }

        public boolean isFailure() {
            return move == null && !resign;
        }

        public static ParseResult failure(String reason) {
            return new ParseResult(null, reason, "FAILURE");
        }
    }
}
