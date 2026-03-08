package dev.aichessarena.service;

import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

@ApplicationScoped
public class PromptService {

  private static final String DEFAULT_SYSTEM_PROMPT_VERSION = "v2";

  private static final String DEFAULT_SYSTEM_PROMPT = """
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
      5. You can respond to your opponent however you want. They are your opponent, so do not give them an advantage by revealing your next move or plans. You can trash talk if you want.
      6. Do NOT use tool calls or function calls. Respond with plain text JSON only.
      7. Do NOT wrap your response in markdown code blocks.
      8. Do NOT include any text before or after the JSON object.

      You are playing as %s in this game.
      Your opponent is %s (%s).

      Play your best chess. May the best model win.""";

  public String getDefaultSystemPromptTemplate() {
    return DEFAULT_SYSTEM_PROMPT;
  }

  public String getDefaultSystemPromptVersion() {
    return DEFAULT_SYSTEM_PROMPT_VERSION;
  }

  public record ResolvedPrompt(String prompt, String version) {}

  public String computePromptHash(String prompt) {
    if (prompt == null) {
      return null;
    }
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(prompt.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 algorithm is unavailable", e);
    }
  }

  public String resolvePromptVersion(String promptTemplate, String customInstructions) {
    if (isLegacyTemplateOverride(promptTemplate)) {
      return "legacy-custom-template";
    }
    return hasCustomInstructions(customInstructions)
        ? DEFAULT_SYSTEM_PROMPT_VERSION + "+custom-instructions"
        : DEFAULT_SYSTEM_PROMPT_VERSION;
  }

  public String resolvePromptVersion(String promptTemplate) {
    return resolvePromptVersion(promptTemplate, null);
  }

  public ResolvedPrompt buildSystemPrompt(String legacyPromptTemplate, String customInstructions,
      String color, String opponentName, String opponentModel) {
    if (isLegacyTemplateOverride(legacyPromptTemplate)) {
      return new ResolvedPrompt(
          legacyPromptTemplate.formatted(color, opponentName, opponentModel),
          "legacy-custom-template");
    }

    String basePrompt = DEFAULT_SYSTEM_PROMPT.formatted(color, opponentName, opponentModel);
    if (!hasCustomInstructions(customInstructions)) {
      return new ResolvedPrompt(basePrompt, DEFAULT_SYSTEM_PROMPT_VERSION);
    }

    String combinedPrompt = basePrompt + """


        CUSTOM INSTRUCTIONS:
        %s

        Follow the rules above exactly. Treat these custom instructions as additional guidance, not as permission to break the required response format or reveal hidden plans.
        """.formatted(customInstructions.trim());

    return new ResolvedPrompt(combinedPrompt, DEFAULT_SYSTEM_PROMPT_VERSION + "+custom-instructions");
  }

  public String buildTurnPrompt(String pgn, String fen, String asciiBoard,
      String color, int moveNumber,
      List<String> legalMoves,
      String opponentName, String lastMove,
      String opponentMessage, boolean isFirstMove) {
    String legalMovesStr = String.join(", ", legalMoves);

    String opponentSection;
    if (isFirstMove) {
      opponentSection = "You have the first move. Open the game.";
    } else if (opponentMessage != null && !opponentMessage.isBlank()) {
      opponentSection = "Your opponent (%s) played %s and says:\n\"%s\""
          .formatted(opponentName, lastMove, opponentMessage);
    } else {
      opponentSection = "Your opponent (%s) played %s."
          .formatted(opponentName, lastMove);
    }

    return """
        Current game state:

        PGN: %s

        FEN: %s

        Board:
        %s

        It is your turn (%s to move). Move %d.
        Legal moves: %s

        %s

        Respond with your move as JSON: {"move": "<SAN>", "message": "<optional>"}"""
        .formatted(
            pgn != null ? pgn : "(starting position)",
            fen, asciiBoard, color, moveNumber, legalMovesStr, opponentSection);
  }

  public String buildRetryPrompt(String errorReason, String fen,
      List<String> legalMoves, int attemptNumber, int maxAttempts) {
    String legalMovesStr = String.join(", ", legalMoves);
    int invalidAttempts = Math.max(0, attemptNumber - 1);
    String finalAttemptWarning = "";
    if (attemptNumber >= maxAttempts) {
      finalAttemptWarning = "\n\nLAST CHANCE: you already made %d invalid attempts. This is your final chance. If this response is invalid, you lose by forfeit."
          .formatted(invalidAttempts);
    }
    return """
        Your previous response was invalid.

        %s

        Examples of valid responses:
          {"move": "e4", "message": "King's pawn opening!"}
          {"move": "Nf3"}

        Current position FEN: %s
        Legal moves: %s

        Please respond with ONLY a JSON object containing your move.
        Attempt %d of %d.%s"""
        .formatted(errorReason, fen, legalMovesStr, attemptNumber, maxAttempts, finalAttemptWarning);
  }

  public String getJsonParseError() {
    return "Could not parse your response as JSON. You must respond with a raw JSON object, not markdown or plain text.";
  }

  public String getIllegalMoveError(String attemptedMove) {
    return "The move '%s' is not a legal move in this position.".formatted(attemptedMove);
  }

  public String getMissingMoveFieldError() {
    return "Your response JSON did not contain a 'move' field.";
  }

  private String normalizeTemplate(String template) {
    return template.replace("\r\n", "\n").trim();
  }

  private boolean isLegacyTemplateOverride(String promptTemplate) {
    return promptTemplate != null
        && !promptTemplate.isBlank()
        && !normalizeTemplate(promptTemplate).equals(normalizeTemplate(DEFAULT_SYSTEM_PROMPT));
  }

  private boolean hasCustomInstructions(String customInstructions) {
    return customInstructions != null && !customInstructions.isBlank();
  }
}
