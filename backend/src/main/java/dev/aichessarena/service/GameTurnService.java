package dev.aichessarena.service;

import com.github.bhlangonijr.chesslib.Board;
import dev.aichessarena.entity.ChatMode;
import dev.aichessarena.entity.MoveAttempt.Outcome;
import dev.aichessarena.entity.ReasoningEffort;
import dev.aichessarena.entity.Tournament;
import dev.aichessarena.repository.MoveRepository;
import dev.aichessarena.websocket.GameWebSocket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.jboss.logging.Logger;

@ApplicationScoped
public class GameTurnService {

    private static final Logger LOG = Logger.getLogger(GameTurnService.class);
    private static final long STOP_POLL_INTERVAL_MS = 250L;

    /**
     * Waits between OpenRouter retries after a retryable provider failure (429, 5xx, network).
     * These retries never consume the model's own attempts; once exhausted the game is paused.
     */
    long[] providerRetryBackoffMs = {1_000L, 3_000L, 9_000L};

    Sleeper sleeper = Thread::sleep;

    /** Monotonic clock for the turn deadline ({@link System#nanoTime()}); replaceable in tests. */
    LongSupplier nanoClock = System::nanoTime;

    @Inject
    ChessService chessService;

    @Inject
    OpenRouterService openRouterService;

    @Inject
    PromptService promptService;

    @Inject
    ResponseParserService responseParserService;

    @Inject
    MoveRepository moveRepository;

    @Inject
    GameWebSocket gameWebSocket;

    @Inject
    GameLifecycleService gameLifecycleService;

    @Inject
    GameMoveService gameMoveService;

    @Inject
    MoveAttemptService moveAttemptService;

    public TurnResult playTurn(TurnRequest request) {
        String turnPrompt = promptService.buildTurnPrompt(
                request.pgn(),
                request.fen(),
                chessService.toAsciiBoard(request.board()),
                request.sideToMove(),
                request.moveNumber(),
                request.legalMoves(),
                request.opponentName(),
                request.lastMoveSan(),
                request.lastMessage(),
                request.isFirstMove(),
                request.chatMode(),
                mayResign(request)
        );

        int turnStart = request.history().size();
        request.history().add(new OpenRouterService.ChatMsg("user", turnPrompt));

        // Decided once per turn and before the move time starts: the catalog lookup must not eat the model's time.
        ReasoningEffort effort = openRouterService.resolveReasoningEffort(request.modelId(), request.reasoningEffort());

        // Without a move time limit there is no turn deadline; each request is still bounded by the OpenRouter
        // safety cap, which is a provider problem when reached, never the model's timeout.
        // One absolute deadline on the monotonic clock is enforced for the whole turn; every attempt gets exactly the
        // time that is left. The wall-clock instants are only shown to viewers.
        Integer moveTimeoutSeconds = request.moveTimeoutSeconds();
        Instant turnStartedAt = Instant.now();
        Instant turnDeadlineAt = null;
        OpenRouterService.AnswerDeadline turnDeadline = null;
        if (moveTimeoutSeconds != null) {
            turnDeadlineAt = turnStartedAt.plusSeconds(moveTimeoutSeconds);
            turnDeadline = OpenRouterService.AnswerDeadline.after(
                    Duration.ofSeconds(moveTimeoutSeconds), nanoClock.getAsLong());
        }

        broadcastTurnTiming(request.gameId(), request.totalCostUsd(), request.sideToMove(), turnStartedAt, turnDeadlineAt,
                request.tournamentChatMode());

        int providerFailures = 0;
        String lastProviderFailure = null;
        for (int attempt = 1; attempt <= request.maxRetries(); attempt++) {
            if (!request.shouldContinue().getAsBoolean()) {
                return TurnResult.stopped();
            }

            if (turnDeadline != null && turnDeadline.remainingNanos(nanoClock.getAsLong()) <= 0) {
                if (lastProviderFailure != null) {
                    // The deadline was eaten by provider outages, not by the model.
                    return pauseForProvider(request, lastProviderFailure);
                }
                gameLifecycleService.forfeitTimeout(request.gameId(), request.sideToMove(), moveTimeoutSeconds);
                return TurnResult.terminal();
            }

            LOG.infof("Game %s: %s move %d, attempt %d",
                    request.gameId(), request.sideToMove(), request.moveNumber(), attempt);

            OpenRouterService.LlmResponse llmResponse = openRouterService.chat(
                    request.modelId(),
                    ConversationWindow.trim(request.history(), turnStart, ConversationWindow.DEFAULT_PRIOR_MESSAGES),
                    turnDeadline,
                    request.maxOutputTokens(),
                    effort
            );

            if (llmResponse.isProviderFailure()) {
                String detail = withReasoningEffortHint(llmResponse, effort, request);
                LOG.warnf("Game %s: OpenRouter failure for %s (%s): %s",
                        request.gameId(), request.modelId(), llmResponse.outcome(), detail);
                recordAttempt(request, effort, attempt,
                        llmResponse.outcome() == OpenRouterService.ProviderOutcome.FATAL
                                ? Outcome.PROVIDER_FATAL : Outcome.PROVIDER_RETRYABLE,
                        null, detail, llmResponse);
                if (llmResponse.outcome() == OpenRouterService.ProviderOutcome.FATAL
                        || providerFailures >= providerRetryBackoffMs.length) {
                    return pauseForProvider(request, detail);
                }
                long delayMs = providerRetryBackoffMs[providerFailures];
                providerFailures++;
                lastProviderFailure = detail;
                if (turnDeadline != null
                        && turnDeadline.remainingNanos(nanoClock.getAsLong()) <= TimeUnit.MILLISECONDS.toNanos(delayMs)) {
                    return pauseForProvider(request, detail);
                }
                broadcastRetry(request.gameId(), request.sideToMove(), attempt,
                        "%s; retrying in %ds (provider retry %d of %d)".formatted(
                                detail, Math.max(1, delayMs / 1000), providerFailures, providerRetryBackoffMs.length));
                if (!waitUnlessStopped(delayMs, request.shouldContinue())) {
                    return TurnResult.stopped();
                }
                attempt--; // provider failures do not use up the model's attempts
                continue;
            }

            if (llmResponse.timedOut()) {
                recordAttempt(request, effort, attempt, Outcome.TIMEOUT, null, "No answer within the move time", llmResponse);
                if (lastProviderFailure != null) {
                    // Outages earlier in this turn ate part of the move time; don't score that as a model timeout.
                    return pauseForProvider(request,
                            "Move time ran out after OpenRouter problems earlier in this turn (" + lastProviderFailure + ")");
                }
                if (moveTimeoutSeconds == null) {
                    // Without a move time limit the model cannot run out of time: this timeout is technical.
                    return pauseForProvider(request, "The OpenRouter request timed out although the tournament has "
                            + "no move time limit");
                }
                LOG.warnf("Game %s: %s timed out while generating move", request.gameId(), request.modelId());
                gameLifecycleService.forfeitTimeout(request.gameId(), request.sideToMove(), moveTimeoutSeconds);
                return TurnResult.terminal();
            }

            if (llmResponse.content() == null) {
                if (llmResponse.truncated()) {
                    return pauseForTruncation(request, effort, attempt, null, llmResponse);
                }
                LOG.errorf("Game %s: null response from %s", request.gameId(), request.modelId());
                recordAttempt(request, effort, attempt, Outcome.EMPTY_RESPONSE, null, "Model returned an empty response", llmResponse);
                broadcastRetry(request.gameId(), request.sideToMove(), attempt, "Model returned an empty response",
                        Outcome.EMPTY_RESPONSE, null);
                if (attempt < request.maxRetries()) {
                    request.history().add(new OpenRouterService.ChatMsg(
                            "user",
                            promptService.buildRetryPrompt(
                                    promptService.getJsonParseError(),
                                    request.fen(),
                                    request.legalMoves(),
                                    attempt + 1,
                                    request.maxRetries()
                            )
                    ));
                }
                continue;
            }

            ResponseParserService.ParseResult parseResult = responseParserService.parse(llmResponse.content());
            if (parseResult.isFailure()) {
                if (llmResponse.truncated()) {
                    return pauseForTruncation(request, effort, attempt, null, llmResponse);
                }
                LOG.warnf("Game %s: parse failure from %s: %s",
                        request.gameId(), request.modelId(), parseResult.message());
                recordAttempt(request, effort, attempt, Outcome.PARSE_FAILURE, null, parseResult.message(), llmResponse);
                broadcastRetry(request.gameId(), request.sideToMove(), attempt, "Failed to parse response as JSON",
                        Outcome.PARSE_FAILURE, null);
                if (attempt < request.maxRetries()) {
                    request.history().add(new OpenRouterService.ChatMsg("assistant", llmResponse.content()));
                    request.history().add(new OpenRouterService.ChatMsg(
                            "user",
                            promptService.buildRetryPrompt(
                                    promptService.getJsonParseError(),
                                    request.fen(),
                                    request.legalMoves(),
                                    attempt + 1,
                                    request.maxRetries()
                            )
                    ));
                }
                continue;
            }

            if (parseResult.resign()) {
                if (mayResign(request)) {
                    // Resign wins: a move sent along is ignored.
                    return resign(request, effort, attempt, parseResult, llmResponse);
                }
                if (parseResult.move() == null) {
                    if (llmResponse.truncated()) {
                        return pauseForTruncation(request, effort, attempt, null, llmResponse);
                    }
                    // Not allowed and nothing else to play: an invalid answer like an illegal move.
                    String error = promptService.getResignationNotAllowedError(request.resignationAllowed());
                    LOG.warnf("Game %s: %s tried to resign at move %d: %s",
                            request.gameId(), request.modelId(), request.moveNumber(), error);
                    recordAttempt(request, effort, attempt, Outcome.RESIGN_NOT_ALLOWED, null, error, llmResponse);
                    broadcastRetry(request.gameId(), request.sideToMove(), attempt, "Tried to resign: " + error,
                            Outcome.RESIGN_NOT_ALLOWED, null);
                    if (attempt < request.maxRetries()) {
                        request.history().add(new OpenRouterService.ChatMsg("assistant", llmResponse.content()));
                        request.history().add(new OpenRouterService.ChatMsg(
                                "user",
                                promptService.buildRetryPrompt(
                                        error,
                                        request.fen(),
                                        request.legalMoves(),
                                        attempt + 1,
                                        request.maxRetries()
                                )
                        ));
                    }
                    continue;
                }
                // Not allowed, but a move came along: the flag is ignored and the move is played (or rejected) as
                // usual.
            }

            ChessService.ValidMoveResult moveResult = chessService.validateAndApply(request.board(), parseResult.move());
            if (!moveResult.valid()) {
                if (llmResponse.truncated()) {
                    return pauseForTruncation(request, effort, attempt, parseResult.move(), llmResponse);
                }
                LOG.warnf("Game %s: illegal move '%s' from %s: %s",
                        request.gameId(), parseResult.move(), request.modelId(), moveResult.error());
                recordAttempt(request, effort, attempt, Outcome.ILLEGAL_MOVE, parseResult.move(), moveResult.error(), llmResponse);
                broadcastRetry(
                        request.gameId(),
                        request.sideToMove(),
                        attempt,
                        "Illegal move attempted: " + parseResult.move(),
                        Outcome.ILLEGAL_MOVE,
                        parseResult.move()
                );
                if (attempt < request.maxRetries()) {
                    request.history().add(new OpenRouterService.ChatMsg("assistant", llmResponse.content()));
                    request.history().add(new OpenRouterService.ChatMsg(
                            "user",
                            promptService.buildRetryPrompt(
                                    promptService.getIllegalMoveError(parseResult.move()),
                                    request.fen(),
                                    request.legalMoves(),
                                    attempt + 1,
                                    request.maxRetries()
                            )
                    ));
                }
                continue;
            }

            request.history().add(new OpenRouterService.ChatMsg("assistant", llmResponse.content()));
            // With chat OFF, messages are neither stored, broadcast nor relayed to the opponent. A missing message never
            // rejects a legal move, not even with chat MANDATORY: the move is recorded with its chat mode and without a
            // chat message, which the talkativeness metric counts as a silent move.
            ChatMode chatMode = request.chatMode() != null ? request.chatMode() : ChatMode.OPTIONAL;
            String message = chatMode.allowsMessages() && parseResult.message() != null
                    && !parseResult.message().isBlank() ? parseResult.message() : null;
            try {
                gameMoveService.recordModelMove(
                        request.gameId(),
                        request.moveNumber(),
                        request.sideToMove(),
                        parseResult.move(),
                        moveResult.fen(),
                        request.modelId(),
                        request.playerName(),
                        message,
                        llmResponse,
                        attempt - 1,
                        request.promptVersion(),
                        request.promptHash(),
                        request.fen(),
                        chatMode
                );
            } catch (GameStateConflictException e) {
                LOG.warnf("Game %s: discarding %s move %s: %s",
                        request.gameId(), request.sideToMove(), parseResult.move(), e.getMessage());
                // The move transaction has rolled back; keep the paid answer and its cost (added once, here).
                recordAttempt(request, effort, attempt, Outcome.DISCARDED, parseResult.move(), e.getMessage(), llmResponse);
                return TurnResult.stopped();
            }
            recordAttempt(request, effort, attempt, Outcome.ACCEPTED, parseResult.move(), null, llmResponse);
            return TurnResult.recorded(parseResult.move(), message);
        }

        gameLifecycleService.forfeitInvalidMoves(request.gameId(), request.sideToMove(), request.maxRetries());
        return TurnResult.terminal();
    }

    /** Whether this side may resign now: offered by its prompt and at least its {@code MIN_RESIGNATION_MOVE}th move. */
    static boolean mayResign(TurnRequest request) {
        return request.resignationAllowed() && request.moveNumber() >= Tournament.MIN_RESIGNATION_MOVE;
    }

    /**
     * Ends the game by this side's resignation. Its message is kept as last words under the chat-mode rules (dropped
     * with chat OFF). The answer is recorded as RESIGNED, with any move sent along as the ignored proposal; if the game
     * changed meanwhile it is recorded as DISCARDED instead, like a move that could not be played.
     */
    private TurnResult resign(TurnRequest request, ReasoningEffort effort, int attempt, ResponseParserService.ParseResult parseResult,
                              OpenRouterService.LlmResponse llmResponse) {
        request.history().add(new OpenRouterService.ChatMsg("assistant", llmResponse.content()));
        ChatMode chatMode = request.chatMode() != null ? request.chatMode() : ChatMode.OPTIONAL;
        String message = chatMode.allowsMessages() && parseResult.message() != null
                && !parseResult.message().isBlank() ? parseResult.message() : null;
        try {
            gameLifecycleService.resign(new GameLifecycleService.Resignation(
                    request.gameId(),
                    request.moveNumber(),
                    request.sideToMove(),
                    request.modelId(),
                    request.playerName(),
                    message,
                    llmResponse.costUsd() != null ? BigDecimal.valueOf(llmResponse.costUsd()) : null,
                    request.fen()
            ));
        } catch (GameStateConflictException e) {
            LOG.warnf("Game %s: discarding %s resignation: %s", request.gameId(), request.sideToMove(), e.getMessage());
            recordAttempt(request, effort, attempt, Outcome.DISCARDED, parseResult.move(), e.getMessage(), llmResponse);
            return TurnResult.stopped();
        }
        recordAttempt(request, effort, attempt, Outcome.RESIGNED, parseResult.move(), null, llmResponse);
        return TurnResult.terminal();
    }

    private TurnResult pauseForTruncation(TurnRequest request, ReasoningEffort effort, int attempt,
                                          String proposedMove, OpenRouterService.LlmResponse response) {
        boolean reasoned = response.reasoningTokens() != null && response.reasoningTokens() > 0;
        String reasoning = reasoned ? " (%d of them reasoning)".formatted(response.reasoningTokens()) : "";
        int effective = response.effectiveMaxOutputTokens() != null
                ? response.effectiveMaxOutputTokens() : request.maxOutputTokens();
        boolean atModelMaximum = response.modelMaxCompletionTokens() != null
                && effective == response.modelMaxCompletionTokens();
        String remedy;
        if (atModelMaximum) {
            remedy = "Raising the tournament limit cannot help. "
                    + (openRouterService.offersReasoningEffort(request.modelId())
                    ? "Lower the reasoning effort and resume."
                    : "This model cannot finish within its own output maximum.");
        } else {
            remedy = reasoned ? "Raise the limit or lower the reasoning effort and resume."
                    : "Raise the limit and resume.";
        }
        String limit = atModelMaximum
                ? String.format(java.util.Locale.ROOT, "model's maximum of %,d output tokens", effective)
                : "%d-token output limit".formatted(effective);
        String detail = "%s's answer was cut off at the %s%s. %s"
                .formatted(request.playerName() != null ? request.playerName() : request.modelId(),
                        limit, reasoning, remedy);
        LOG.warnf("Game %s: %s", request.gameId(), detail);
        recordAttempt(request, effort, attempt, Outcome.TRUNCATED, proposedMove, detail, response);
        gameLifecycleService.pauseWithReason(request.gameId(), detail);
        return TurnResult.paused();
    }

    /**
     * A provider failure's message, with a hint when OpenRouter rejected a request that carried a reasoning effort as
     * invalid (HTTP 400 or 422): the level is a likely cause and can be changed for this participant before resuming.
     */
    static String withReasoningEffortHint(OpenRouterService.LlmResponse response, ReasoningEffort effort,
                                          TurnRequest request) {
        String detail = response.errorMessage();
        int status = response.httpStatus();
        boolean rejected = status == 400 || status == 422;
        if (effort == null || !rejected) {
            return detail;
        }
        String player = request.playerName() != null ? request.playerName() : request.modelId();
        String base = detail == null ? "OpenRouter rejected the request" : detail.replaceAll("[.\\s]+$", "");
        return ("%s. The request asked for reasoning effort \"%s\"; if the model does not accept it, choose another "
                + "level for %s (or Model default for the tournament) and resume.")
                .formatted(base, effort.apiValue(), player);
    }

    private void recordAttempt(TurnRequest request, ReasoningEffort effort, int attempt, Outcome outcome,
                               String proposedMove, String errorMessage, OpenRouterService.LlmResponse response) {
        try {
            moveAttemptService.record(new MoveAttemptService.AttemptRecord(
                    request.gameId(), request.moveNumber(), request.sideToMove(), attempt, request.modelId(),
                    outcome, proposedMove, errorMessage, response, effort));
        } catch (RuntimeException e) {
            // The attempt is committed in its own transaction; a failed commit must not interrupt the game.
            LOG.warnf(e, "Game %s: could not record %s attempt", request.gameId(), outcome);
        }
    }

    private TurnResult pauseForProvider(TurnRequest request, String detail) {
        gameLifecycleService.pauseForProviderFailure(request.gameId(), detail);
        return TurnResult.paused();
    }

    private boolean waitUnlessStopped(long delayMs, BooleanSupplier shouldContinue) {
        long remaining = delayMs;
        try {
            while (remaining > 0) {
                long slice = Math.min(remaining, STOP_POLL_INTERVAL_MS);
                sleeper.sleep(slice);
                remaining -= slice;
                if (!shouldContinue.getAsBoolean()) {
                    return false;
                }
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void broadcastRetry(UUID gameId, String color, int attemptNumber, String reason) {
        gameWebSocket.broadcastRetry(gameId, color, attemptNumber, reason);
    }

    private void broadcastRetry(UUID gameId, String color, int attemptNumber, String reason,
                                Outcome outcome, String proposedMove) {
        gameWebSocket.broadcastRetry(gameId, color, attemptNumber, reason, outcome.name(), proposedMove);
    }

    public void broadcastHumanTurnReady(UUID gameId, BigDecimal totalCostUsd, String activeColor) {
        int totalMoves = Math.toIntExact(moveRepository.count("game.id", gameId));
        gameWebSocket.broadcastGameStatus(
                gameId,
                "IN_PROGRESS",
                null,
                null,
                totalMoves,
                totalCostUsd,
                activeColor,
                null,
                null
        );
    }

    /**
     * Also tells open game views the tournament's configured chat mode, so a mode changed during play shows up live.
     * The configured mode, not this side's effective one: a legacy template makes MANDATORY effectively OPTIONAL
     * for one side only, and the label must not flip from turn to turn.
     */
    private void broadcastTurnTiming(UUID gameId, BigDecimal totalCostUsd, String activeColor,
                                     Instant turnStartedAt, Instant turnDeadlineAt, ChatMode tournamentChatMode) {
        int totalMoves = Math.toIntExact(moveRepository.count("game.id", gameId));
        gameWebSocket.broadcastGameStatus(
                gameId,
                "IN_PROGRESS",
                null,
                null,
                totalMoves,
                totalCostUsd,
                activeColor,
                turnStartedAt,
                turnDeadlineAt,
                null,
                tournamentChatMode != null ? tournamentChatMode.name() : null
        );
    }

    public record TurnRequest(
            UUID gameId,
            String pgn,
            String fen,
            Board board,
            String sideToMove,
            int moveNumber,
            List<String> legalMoves,
            String modelId,
            String playerName,
            String opponentName,
            List<OpenRouterService.ChatMsg> history,
            String lastMoveSan,
            String lastMessage,
            boolean isFirstMove,
            int maxRetries,
            // Seconds for the whole turn (all attempts); null means no move time limit.
            Integer moveTimeoutSeconds,
            String promptVersion,
            String promptHash,
            BigDecimal totalCostUsd,
            BooleanSupplier shouldContinue,
            int maxOutputTokens,
            // The chat mode that applies to this side's answer (see GameConversationService.PromptContext); it decides
            // the prompt, whether the message is kept and the mode recorded on the move.
            ChatMode chatMode,
            // The tournament's configured chat mode, shown live in the game view.
            ChatMode tournamentChatMode,
            // Whether this side's prompt offers resignation (see GameConversationService.PromptContext); it may resign
            // from move Tournament.MIN_RESIGNATION_MOVE on.
            boolean resignationAllowed,
            // The reasoning effort this side asks for (participant override, else the tournament's); null for the
            // model default. playTurn maps it to a level the model supports before sending it.
            ReasoningEffort reasoningEffort
    ) {
        /** A request without a reasoning effort (the model's default). */
        public TurnRequest(UUID gameId, String pgn, String fen, Board board, String sideToMove, int moveNumber,
                           List<String> legalMoves, String modelId, String playerName, String opponentName,
                           List<OpenRouterService.ChatMsg> history, String lastMoveSan, String lastMessage,
                           boolean isFirstMove, int maxRetries, Integer moveTimeoutSeconds, String promptVersion,
                           String promptHash, BigDecimal totalCostUsd, BooleanSupplier shouldContinue,
                           int maxOutputTokens, ChatMode chatMode, ChatMode tournamentChatMode,
                           boolean resignationAllowed) {
            this(gameId, pgn, fen, board, sideToMove, moveNumber, legalMoves, modelId, playerName, opponentName,
                    history, lastMoveSan, lastMessage, isFirstMove, maxRetries, moveTimeoutSeconds, promptVersion,
                    promptHash, totalCostUsd, shouldContinue, maxOutputTokens, chatMode, tournamentChatMode,
                    resignationAllowed, null);
        }

        /** A request for a game without resignation. */
        public TurnRequest(UUID gameId, String pgn, String fen, Board board, String sideToMove, int moveNumber,
                           List<String> legalMoves, String modelId, String playerName, String opponentName,
                           List<OpenRouterService.ChatMsg> history, String lastMoveSan, String lastMessage,
                           boolean isFirstMove, int maxRetries, Integer moveTimeoutSeconds, String promptVersion,
                           String promptHash, BigDecimal totalCostUsd, BooleanSupplier shouldContinue,
                           int maxOutputTokens, ChatMode chatMode, ChatMode tournamentChatMode) {
            this(gameId, pgn, fen, board, sideToMove, moveNumber, legalMoves, modelId, playerName, opponentName,
                    history, lastMoveSan, lastMessage, isFirstMove, maxRetries, moveTimeoutSeconds, promptVersion,
                    promptHash, totalCostUsd, shouldContinue, maxOutputTokens, chatMode, tournamentChatMode, false,
                    null);
        }
    }

    public record TurnResult(Status status, String moveSan, String message) {
        public static TurnResult recorded(String moveSan, String message) {
            return new TurnResult(Status.MOVE_RECORDED, moveSan, message);
        }

        public static TurnResult stopped() {
            return new TurnResult(Status.STOPPED, null, null);
        }

        public static TurnResult terminal() {
            return new TurnResult(Status.TERMINAL, null, null);
        }

        public static TurnResult paused() {
            return new TurnResult(Status.PAUSED, null, null);
        }

        public boolean moveRecorded() {
            return status == Status.MOVE_RECORDED;
        }

        public boolean isTerminal() {
            return status == Status.TERMINAL;
        }

        /** The turn paused the game itself (with a visible reason); the loop must not overwrite that state. */
        public boolean pausedGame() {
            return status == Status.PAUSED;
        }
    }

    public enum Status {
        MOVE_RECORDED,
        STOPPED,
        PAUSED,
        TERMINAL
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
