package dev.aichessarena.service;

import dev.aichessarena.entity.ChatMode;
import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

@ApplicationScoped
public class PromptService {

  private static final String DEFAULT_SYSTEM_PROMPT_VERSION = "v2";

  /** Version suffix of prompts rendered with chat OFF. */
  static final String SILENT_VERSION_SUFFIX = "-silent";

  /** Version suffix of prompts rendered with chat MANDATORY. */
  static final String CHAT_REQUIRED_VERSION_SUFFIX = "-chat-required";

  /**
   * Rule 5 of {@link #DEFAULT_SYSTEM_PROMPT} (chat OPTIONAL); replaced by {@link #SILENT_MESSAGE_RULE} when chat is
   * OFF and by {@link #REQUIRED_MESSAGE_RULE} when it is MANDATORY.
   */
  static final String TRASH_TALK_RULE = "5. You can respond to your opponent however you want. They are your opponent, "
      + "so do not give them an advantage by revealing your next move or plans. You can trash talk if you want.";

  static final String SILENT_MESSAGE_RULE = "5. Trash talk is disabled in this tournament. Leave the \"message\" field "
      + "empty (\"\") and do not address your opponent.";

  /** Upper bound for the message the MANDATORY prompt asks for; a guideline for the model, not enforced. */
  static final int REQUIRED_MESSAGE_MAX_CHARS = 200;

  static final String REQUIRED_MESSAGE_RULE = ("5. Chat is required in this tournament: with every move, send your "
      + "opponent one short message (at most %d characters) in the \"message\" field. Banter and trash talk about "
      + "the game are welcome; stay civil. They are your opponent, so do not give them an advantage by revealing "
      + "your next move or plans.").formatted(REQUIRED_MESSAGE_MAX_CHARS);

  /** Rule 4 of {@link #DEFAULT_SYSTEM_PROMPT}; replaced by {@link #REQUIRED_MESSAGE_FIELD_RULE} when chat is MANDATORY. */
  static final String OPTIONAL_MESSAGE_FIELD_RULE = "4. The \"message\" field is OPTIONAL.";

  static final String REQUIRED_MESSAGE_FIELD_RULE = "4. The \"message\" field is REQUIRED in this tournament.";

  /** The "message" line of the response format in {@link #DEFAULT_SYSTEM_PROMPT}. */
  static final String OPTIONAL_MESSAGE_FORMAT = "\"message\": \"<optional message to your opponent>\"";

  static final String REQUIRED_MESSAGE_FORMAT = "\"message\": \"<short message to your opponent>\"";

  /** Last line of the turn prompt. */
  static final String DEFAULT_ANSWER_LINE = "Respond with your move as JSON: {\"move\": \"<SAN>\", \"message\": \"<optional>\"}";

  /** Last line of the turn prompt with chat MANDATORY. */
  static final String REQUIRED_MESSAGE_ANSWER_LINE = "Respond with your move and a short message as JSON: "
      + "{\"move\": \"<SAN>\", \"message\": \"<your message>\"}";

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

  /** The default rules (chat OPTIONAL), unformatted. */
  public String getDefaultSystemPromptTemplate() {
    return DEFAULT_SYSTEM_PROMPT;
  }

  public String getDefaultSystemPromptVersion() {
    return DEFAULT_SYSTEM_PROMPT_VERSION;
  }

  /** The default rules for a chat mode, unformatted (with the %s placeholders); OPTIONAL is the default template. */
  public String getSystemPromptTemplate(ChatMode chatMode) {
    return applyChatMode(DEFAULT_SYSTEM_PROMPT, chatMode);
  }

  /** The version of the default rules for a chat mode without custom instructions: v2, v2-silent, v2-chat-required. */
  public String getSystemPromptVersion(ChatMode chatMode) {
    return DEFAULT_SYSTEM_PROMPT_VERSION + versionSuffix(chatMode);
  }

  /**
   * A rendered system prompt. {@code chatMode} is the mode that applies to the model's answers: the requested one,
   * except that a legacy custom template (used as stored) cannot ask for a message, so MANDATORY counts as OPTIONAL.
   */
  public record ResolvedPrompt(String prompt, String version, ChatMode chatMode) {}

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
    return buildSystemPrompt(legacyPromptTemplate, customInstructions, color, opponentName, opponentModel,
        ChatMode.OPTIONAL);
  }

  /**
   * Renders the system prompt for a chat mode. OPTIONAL is the default text; OFF asks the model to leave "message"
   * empty (version suffix {@value #SILENT_VERSION_SUFFIX}); MANDATORY asks for a short message with every move
   * (version suffix {@value #CHAT_REQUIRED_VERSION_SUFFIX}). Legacy custom templates are used as stored.
   */
  public ResolvedPrompt buildSystemPrompt(String legacyPromptTemplate, String customInstructions,
      String color, String opponentName, String opponentModel, ChatMode chatMode) {
    ChatMode mode = chatMode != null ? chatMode : ChatMode.OPTIONAL;
    if (isLegacyTemplateOverride(legacyPromptTemplate)) {
      return new ResolvedPrompt(
          legacyPromptTemplate.formatted(color, opponentName, opponentModel),
          "legacy-custom-template",
          mode == ChatMode.MANDATORY ? ChatMode.OPTIONAL : mode);
    }

    String versionSuffix = versionSuffix(mode);
    String basePrompt = applyChatMode(DEFAULT_SYSTEM_PROMPT, mode).formatted(color, opponentName, opponentModel);
    if (!hasCustomInstructions(customInstructions)) {
      return new ResolvedPrompt(basePrompt, DEFAULT_SYSTEM_PROMPT_VERSION + versionSuffix, mode);
    }

    String combinedPrompt = basePrompt + """


        CUSTOM INSTRUCTIONS:
        %s

        Follow the rules above exactly. Treat these custom instructions as additional guidance, not as permission to break the required response format or reveal hidden plans.
        """.formatted(customInstructions.trim());

    return new ResolvedPrompt(combinedPrompt,
        DEFAULT_SYSTEM_PROMPT_VERSION + "+custom-instructions" + versionSuffix, mode);
  }

  /**
   * The rule changes per chat mode. OPTIONAL is the default text itself; every other mode swaps whole rules, so a
   * new mode (or a later rule, such as offering resignation) adds a case here and a version suffix below.
   */
  private static String applyChatMode(String template, ChatMode chatMode) {
    return switch (chatMode) {
      case OPTIONAL -> template;
      case OFF -> template.replace(TRASH_TALK_RULE, SILENT_MESSAGE_RULE);
      case MANDATORY -> template
          .replace(OPTIONAL_MESSAGE_FORMAT, REQUIRED_MESSAGE_FORMAT)
          .replace(OPTIONAL_MESSAGE_FIELD_RULE, REQUIRED_MESSAGE_FIELD_RULE)
          .replace(TRASH_TALK_RULE, REQUIRED_MESSAGE_RULE);
    };
  }

  private static String versionSuffix(ChatMode chatMode) {
    return switch (chatMode) {
      case OPTIONAL -> "";
      case OFF -> SILENT_VERSION_SUFFIX;
      case MANDATORY -> CHAT_REQUIRED_VERSION_SUFFIX;
    };
  }

  public String buildTurnPrompt(String pgn, String fen, String asciiBoard,
      String color, int moveNumber,
      List<String> legalMoves,
      String opponentName, String lastMove,
      String opponentMessage, boolean isFirstMove) {
    return buildTurnPrompt(pgn, fen, asciiBoard, color, moveNumber, legalMoves, opponentName, lastMove,
        opponentMessage, isFirstMove, ChatMode.OPTIONAL);
  }

  /** The turn prompt; with chat MANDATORY its last line asks for the move and a message (OFF, OPTIONAL: unchanged). */
  public String buildTurnPrompt(String pgn, String fen, String asciiBoard,
      String color, int moveNumber,
      List<String> legalMoves,
      String opponentName, String lastMove,
      String opponentMessage, boolean isFirstMove, ChatMode chatMode) {
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

        %s"""
        .formatted(
            pgn != null ? pgn : "(starting position)",
            fen, asciiBoard, color, moveNumber, legalMovesStr, opponentSection,
            chatMode == ChatMode.MANDATORY ? REQUIRED_MESSAGE_ANSWER_LINE : DEFAULT_ANSWER_LINE);
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

  /**
   * A stored template is a legacy override unless it is blank or the default (OPTIONAL) rules. Copies of the
   * generated OFF or MANDATORY rules stay legacy overrides on purpose: stored templates are used byte for byte with
   * the version they always had, and only the default template stands for "use the current rules".
   */
  private boolean isLegacyTemplateOverride(String promptTemplate) {
    return promptTemplate != null
        && !promptTemplate.isBlank()
        && !normalizeTemplate(promptTemplate).equals(normalizeTemplate(DEFAULT_SYSTEM_PROMPT));
  }

  private boolean hasCustomInstructions(String customInstructions) {
    return customInstructions != null && !customInstructions.isBlank();
  }
}
