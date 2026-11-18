package dev.aichessarena.service;

import com.github.bhlangonijr.chesslib.Board;
import dev.aichessarena.entity.BanterLevel;
import dev.aichessarena.entity.ChatMode;
import dev.aichessarena.entity.GameConfiguration;
import dev.aichessarena.entity.MoveAttempt.Outcome;
import dev.aichessarena.entity.ReasoningEffort;
import dev.aichessarena.entity.Tournament;
import dev.aichessarena.repository.MoveRepository;
import dev.aichessarena.service.TurnPolicy.Action;
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
import java.util.function.Supplier;
import org.jboss.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

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

    @ConfigProperty(name = "openrouter.prompt-caching", defaultValue = "true")
    boolean promptCaching = true;

    @ConfigProperty(name = "openrouter.prompt-cache-max-prior-messages", defaultValue = "12")
    int maxPriorMessages = ConversationWindow.DEFAULT_MAX_PRIOR_MESSAGES;

    @ConfigProperty(name = "openrouter.prompt-cache-retained-messages", defaultValue = "4")
    int retainedMessages = ConversationWindow.DEFAULT_RETAINED_MESSAGES;

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
    GameTurnTiming turnTiming;

    @Inject
    GameLifecycleService gameLifecycleService;

    @Inject
    GameMoveService gameMoveService;

    @Inject
    MoveAttemptService moveAttemptService;

    @Inject
    TournamentAutoRunService autoRuns;

    public TurnResult playTurn(TurnRequest request) {
        boolean mayResign = mayResign(request);
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
                mayResign
        );

        if (promptCaching) {
            ConversationWindow.reanchor(request.history(), maxPriorMessages, retainedMessages);
        } else {
            // Human waits retain this backing list, so bounding only an outbound copy leaks old exchanges.
            List<OpenRouterService.ChatMsg> window = ConversationWindow.trim(request.history(), request.history().size(),
                    ConversationWindow.LEGACY_PRIOR_MESSAGES);
            request.history().clear();
            request.history().addAll(window);
        }
        request.history().add(new OpenRouterService.ChatMsg("user", turnPrompt, true));

        // Decided once per turn and before the move time starts: the catalog lookup must not eat the model's time.
        ReasoningEffort effort = openRouterService.resolveReasoningEffort(request.modelId(), request.reasoningEffort());

        // Without a move time limit there is no turn deadline; each request is still bounded by the OpenRouter
        // safety cap, which is a provider problem when reached, never the model's timeout.
        // One absolute deadline on the monotonic clock is enforced for the whole turn; every attempt gets exactly the
        // time that is left. The wall-clock instants are only shown to viewers.
        Integer moveTimeoutSeconds = request.moveTimeoutSeconds();
        GameTurnTiming.TurnTimes timing;
        if (turnTiming != null) {
            timing = turnTiming.record(request);
            if (timing == null) return TurnResult.stopped();
        } else {
            timing = GameTurnTiming.TurnTimes.start(moveTimeoutSeconds);
            broadcastTurnTiming(request.gameId(), request.totalCostUsd(), request.sideToMove(), timing.startedAt(),
                    timing.deadlineAt(), request.tournamentChatMode(), request.tournamentBanterLevel());
        }
        var attemptContext = new AttemptContext(effort, timing.configurationRevision());
        OpenRouterService.AnswerDeadline turnDeadline = null;

        int maxAttempts = Math.clamp(request.maxRetries(), Tournament.MIN_MOVE_ATTEMPTS, Tournament.MAX_MOVE_ATTEMPTS);
        if (maxAttempts != request.maxRetries()) {
            LOG.warnf("Game %s: clamped stored maxRetries from %d to %d", request.gameId(), request.maxRetries(), maxAttempts);
        }
        int providerFailures = 0;
        ProviderProblem providerProblem = null;
        int attempt = 1;
        while (true) {
            if (!request.shouldContinue().getAsBoolean()) {
                return TurnResult.stopped();
            }

            if (autoRuns != null && !autoRuns.beforeRequest(request.gameId())) {
                return TurnResult.paused();
            }

            if (turnDeadline == null && moveTimeoutSeconds != null) {
                // Database timing and admission work must finish before the model's first answer budget starts.
                turnDeadline = OpenRouterService.AnswerDeadline.after(
                        Duration.ofSeconds(moveTimeoutSeconds), nanoClock.getAsLong());
            } else {
                Action action = TurnPolicy.beforeRequest(remainingNanos(turnDeadline), providerProblem != null);
                if (action == Action.PAUSE_PROVIDER) {
                    // Attribute an unrecorded marker rejection to the request that actually triggered it.
                    if (providerProblem.cacheRejectionAttempt() != 0) {
                        recordAttempt(request, attemptContext, providerProblem.cacheRejectionAttempt(), Outcome.PROVIDER_RETRYABLE,
                                null, providerProblem.detail(), null);
                    }
                    return pauseForProvider(request, providerProblem.detail());
                }
                if (action == Action.FORFEIT_TIMEOUT) {
                    gameLifecycleService.forfeitTimeout(request.gameId(), request.sideToMove(), moveTimeoutSeconds);
                    return TurnResult.terminal();
                }
            }

            LOG.infof("Game %s: %s move %d, attempt %d",
                    request.gameId(), request.sideToMove(), request.moveNumber(), attempt);

            OpenRouterService.LlmResponse llmResponse = openRouterService.chat(
                    request.modelId(),
                    List.copyOf(request.history()),
                    turnDeadline,
                    request.maxOutputTokens(),
                    effort
            );

            if (llmResponse.isProviderFailure()) {
                String detail = withReasoningEffortHint(llmResponse, effort, request);
                LOG.warnf("Game %s: OpenRouter failure for %s (%s): %s",
                        request.gameId(), request.modelId(), llmResponse.outcome(), detail);
                Outcome outcome = llmResponse.outcome() == OpenRouterService.ProviderOutcome.FATAL
                        ? Outcome.PROVIDER_FATAL : Outcome.PROVIDER_RETRYABLE;
                recordAttempt(request, attemptContext, attempt, outcome, null, detail, llmResponse);
                Long delayMs = outcome == Outcome.PROVIDER_RETRYABLE && providerFailures < providerRetryBackoffMs.length
                        ? providerRetryBackoffMs[providerFailures] : null;
                Action action = TurnPolicy.afterProviderFailure(outcome,
                        delayMs == null ? null : remainingNanos(turnDeadline),
                        delayMs == null ? null : TimeUnit.MILLISECONDS.toNanos(delayMs));
                if (action == Action.PAUSE_PROVIDER) {
                    return pauseForProvider(request, detail);
                }
                providerFailures++;
                providerProblem = new ProviderProblem(detail, 0);
                broadcastRetry(request.gameId(), request.sideToMove(), attempt,
                        "%s; retrying in %ds (provider retry %d of %d)".formatted(
                                detail, Math.max(1, delayMs / 1000), providerFailures, providerRetryBackoffMs.length));
                if (!waitUnlessStopped(delayMs, request.shouldContinue())) {
                    return TurnResult.stopped();
                }
                continue;
            }

            if (llmResponse.cacheRejectionStatus() != null) {
                providerProblem = new ProviderProblem(
                        "OpenRouter rejected cache control (HTTP " + llmResponse.cacheRejectionStatus() + ")", attempt);
            }
            if (llmResponse.timedOut()) {
                return handleResponseTimeout(request, attemptContext, attempt, llmResponse, providerProblem);
            }

            ModelAnswer answer = assessModelAnswer(request, llmResponse, mayResign);
            Action action = TurnPolicy.afterModelResponse(answer.outcome(), maxAttempts - attempt,
                    providerProblem != null, moveTimeoutSeconds != null);
            switch (action) {
                case ACCEPT -> {
                    if (answer.outcome() == Outcome.RESIGNED) {
                        return resign(request, attemptContext, attempt, answer.parsed(), llmResponse);
                    }
                    return recordAcceptedMove(request, attemptContext, attempt, answer.parsed(), answer.move(), llmResponse);
                }
                case PAUSE_TRUNCATED -> {
                    return pauseForTruncation(request, attemptContext, attempt, answer.proposedMove(), llmResponse);
                }
                case RETRY_MODEL, FORFEIT_INVALID_MOVES -> recordRejectedAnswer(
                        request, attemptContext, attempt, maxAttempts, answer, llmResponse, action == Action.RETRY_MODEL);
                default -> throw new IllegalStateException("Unexpected action after assessing a model answer: " + action);
            }
            if (action == Action.FORFEIT_INVALID_MOVES) {
                break;
            }
            attempt++;
        }

        if (autoRuns != null && !autoRuns.beforeRequest(request.gameId())) return TurnResult.paused();
        gameLifecycleService.forfeitInvalidMoves(request.gameId(), request.sideToMove(), maxAttempts);
        return TurnResult.terminal();
    }

    private Long remainingNanos(OpenRouterService.AnswerDeadline deadline) {
        return deadline == null ? null : deadline.remainingNanos(nanoClock.getAsLong());
    }

    private record ProviderProblem(String detail, int cacheRejectionAttempt) {}

    private TurnResult handleResponseTimeout(TurnRequest request, AttemptContext attemptContext, int attempt,
                                             OpenRouterService.LlmResponse response, ProviderProblem providerProblem) {
        Integer timeoutSeconds = request.moveTimeoutSeconds();
        Action action = TurnPolicy.afterModelResponse(Outcome.TIMEOUT, 0, providerProblem != null, timeoutSeconds != null);
        if (action == Action.PAUSE_PROVIDER) {
            String detail = providerProblem != null
                    ? "Move time ran out after OpenRouter problems earlier in this turn (" + providerProblem.detail() + ")"
                    : "The OpenRouter request timed out although the tournament has no move time limit";
            recordAttempt(request, attemptContext, attempt, Outcome.PROVIDER_RETRYABLE, null, detail, response);
            return pauseForProvider(request, detail);
        }
        recordAttempt(request, attemptContext, attempt, Outcome.TIMEOUT, null, "No answer within the move time", response);
        LOG.warnf("Game %s: %s timed out while generating move", request.gameId(), request.modelId());
        gameLifecycleService.forfeitTimeout(request.gameId(), request.sideToMove(), timeoutSeconds);
        return TurnResult.terminal();
    }

    private ModelAnswer assessModelAnswer(TurnRequest request, OpenRouterService.LlmResponse response, boolean mayResign) {
        if (response.content() == null) {
            return ModelAnswer.rejected(response, Outcome.EMPTY_RESPONSE, null, null);
        }
        ResponseParserService.ParseResult parsed = responseParserService.parse(response.content());
        if (parsed.isFailure()) {
            return ModelAnswer.rejected(response, Outcome.PARSE_FAILURE, parsed, null);
        }
        if (parsed.resign()) {
            if (mayResign) {
                // An allowed resignation wins even when the model includes a move.
                return new ModelAnswer(Outcome.RESIGNED, parsed, null);
            }
            if (parsed.move() == null) {
                return ModelAnswer.rejected(response, Outcome.RESIGN_NOT_ALLOWED, parsed, null);
            }
        }

        ChessService.ValidMoveResult move = chessService.validateAndApply(request.board(), parsed.move());
        return move.valid() ? new ModelAnswer(Outcome.ACCEPTED, parsed, move)
                : ModelAnswer.rejected(response, Outcome.ILLEGAL_MOVE, parsed, move);
    }

    private record ModelAnswer(Outcome outcome, ResponseParserService.ParseResult parsed, ChessService.ValidMoveResult move) {
        static ModelAnswer rejected(OpenRouterService.LlmResponse response, Outcome outcome,
                                    ResponseParserService.ParseResult parsed, ChessService.ValidMoveResult move) {
            return new ModelAnswer(response.truncated() ? Outcome.TRUNCATED : outcome, parsed, move);
        }

        String proposedMove() {
            return move == null ? null : parsed.move();
        }
    }

    private void recordRejectedAnswer(TurnRequest request, AttemptContext attemptContext, int attempt, int maxAttempts,
                                      ModelAnswer answer, OpenRouterService.LlmResponse response, boolean retry) {
        String proposedMove = answer.proposedMove();
        String detail;
        String retryReason;
        Supplier<String> retryError;
        switch (answer.outcome()) {
            case EMPTY_RESPONSE -> {
                LOG.errorf("Game %s: null response from %s", request.gameId(), request.modelId());
                detail = "Model returned an empty response";
                retryReason = detail;
                retryError = promptService::getJsonParseError;
            }
            case PARSE_FAILURE -> {
                detail = answer.parsed().message();
                LOG.warnf("Game %s: parse failure from %s: %s", request.gameId(), request.modelId(), detail);
                retryReason = "Failed to parse response as JSON";
                retryError = promptService::getJsonParseError;
            }
            case RESIGN_NOT_ALLOWED -> {
                detail = promptService.getResignationNotAllowedError(request.resignationAllowed());
                LOG.warnf("Game %s: %s tried to resign at move %d: %s",
                        request.gameId(), request.modelId(), request.moveNumber(), detail);
                retryReason = "Tried to resign: " + detail;
                retryError = () -> detail;
            }
            case ILLEGAL_MOVE -> {
                detail = answer.move().error();
                LOG.warnf("Game %s: illegal move '%s' from %s: %s",
                        request.gameId(), proposedMove, request.modelId(), detail);
                retryReason = "Illegal move attempted: " + proposedMove;
                retryError = () -> promptService.getIllegalMoveError(proposedMove);
            }
            default -> throw new IllegalArgumentException("Expected an invalid model answer, got " + answer.outcome());
        }

        recordAttempt(request, attemptContext, attempt, answer.outcome(), proposedMove, detail, response);
        broadcastRetry(request.gameId(), request.sideToMove(), attempt, retryReason, answer.outcome(), proposedMove);
        if (retry) {
            if (response.content() != null) {
                request.history().add(new OpenRouterService.ChatMsg("assistant", response.content()));
            }
            request.history().add(new OpenRouterService.ChatMsg("user", promptService.buildRetryPrompt(
                    retryError.get(), request.fen(), request.legalMoves(), attempt + 1, maxAttempts)));
        }
    }

    private TurnResult recordAcceptedMove(TurnRequest request, AttemptContext attemptContext, int attempt,
                                          ResponseParserService.ParseResult parseResult, ChessService.ValidMoveResult moveResult,
                                          OpenRouterService.LlmResponse llmResponse) {
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
                    moveResult.san(),
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
            recordAttempt(request, attemptContext, attempt, Outcome.DISCARDED, parseResult.move(), e.getMessage(), llmResponse);
            return TurnResult.stopped();
        }
        // Attempt recording can pause the run and stop its loop at the budget cap. Finalize the
        // committed move first, using the live board (including repetition history). The lifecycle
        // rechecks cancellation under the locks before completing or advancing the tournament.
        boolean terminal = chessService.isGameOver(request.board());
        if (terminal) {
            gameLifecycleService.completeGame(request.gameId(), chessService.getGameEndInfo(request.board()));
        } else {
            terminal = gameLifecycleService.adjudicateIfEligible(request.gameId(), moveResult.fen());
        }
        recordAttempt(request, attemptContext, attempt, Outcome.ACCEPTED, parseResult.move(), null, llmResponse);
        return terminal ? TurnResult.terminal() : TurnResult.recorded(moveResult.san(), message);
    }

    /** Whether resignation is offered and the current move is at least this side's tenth non-book move. */
    boolean mayResign(TurnRequest request) {
        return request.resignationAllowed()
                && 1 + moveRepository.countNonBookByGameIdAndColor(request.gameId(), request.sideToMove())
                >= Tournament.MIN_RESIGNATION_MOVE;
    }

    /**
     * Ends the game by this side's resignation. Its message is kept as last words under the chat-mode rules (dropped
     * with chat OFF). The answer is recorded as RESIGNED, with any move sent along as the ignored proposal; if the game
     * changed meanwhile it is recorded as DISCARDED instead, like a move that could not be played.
     */
    private TurnResult resign(TurnRequest request, AttemptContext attemptContext, int attempt, ResponseParserService.ParseResult parseResult,
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
            recordAttempt(request, attemptContext, attempt, Outcome.DISCARDED, parseResult.move(), e.getMessage(), llmResponse);
            return TurnResult.stopped();
        }
        recordAttempt(request, attemptContext, attempt, Outcome.RESIGNED, parseResult.move(), null, llmResponse);
        return TurnResult.terminal();
    }

    private TurnResult pauseForTruncation(TurnRequest request, AttemptContext attemptContext, int attempt,
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
        recordAttempt(request, attemptContext, attempt, Outcome.TRUNCATED, proposedMove, detail, response);
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

    private record AttemptContext(ReasoningEffort effort, Integer configurationRevision) {}

    private void recordAttempt(TurnRequest request, AttemptContext attemptContext, int attempt, Outcome outcome,
                               String proposedMove, String errorMessage, OpenRouterService.LlmResponse response) {
        try {
            moveAttemptService.record(new MoveAttemptService.AttemptRecord(
                    request.gameId(), request.moveNumber(), request.sideToMove(), attempt, request.modelId(),
                    outcome, proposedMove, errorMessage, response, attemptContext.effort(), request.statusRevision(),
                    attemptContext.configurationRevision()));
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
                                     Instant turnStartedAt, Instant turnDeadlineAt, ChatMode tournamentChatMode,
                                     BanterLevel tournamentBanterLevel) {
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
                tournamentChatMode != null ? tournamentChatMode.name() : null,
                tournamentBanterLevel != null ? tournamentBanterLevel.name() : null
        );
    }

    public record TurnRequest(
            UUID gameId,
            String pgn,
            String fen,
            Board board,
            String sideToMove,
            int moveNumber,
            // Null means the tournament withholds the legal move list in all prompts.
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
            // from its own non-book move Tournament.MIN_RESIGNATION_MOVE on.
            boolean resignationAllowed,
            // The reasoning effort this side asks for (participant override, else the tournament's); null for the
            // model default. playTurn maps it to a level the model supports before sending it.
            ReasoningEffort reasoningEffort,
            BanterLevel tournamentBanterLevel,
            Long statusRevision,
            GameConfiguration configuration
    ) {
        public TurnRequest(UUID gameId, String pgn, String fen, Board board, String sideToMove, int moveNumber,
                           List<String> legalMoves, String modelId, String playerName, String opponentName,
                           List<OpenRouterService.ChatMsg> history, String lastMoveSan, String lastMessage,
                           boolean isFirstMove, int maxRetries, Integer moveTimeoutSeconds, String promptVersion,
                           String promptHash, BigDecimal totalCostUsd, BooleanSupplier shouldContinue,
                           int maxOutputTokens, ChatMode chatMode, ChatMode tournamentChatMode,
                           boolean resignationAllowed, ReasoningEffort reasoningEffort, BanterLevel banterLevel) {
            this(gameId, pgn, fen, board, sideToMove, moveNumber, legalMoves, modelId, playerName, opponentName,
                    history, lastMoveSan, lastMessage, isFirstMove, maxRetries, moveTimeoutSeconds, promptVersion,
                    promptHash, totalCostUsd, shouldContinue, maxOutputTokens, chatMode, tournamentChatMode,
                    resignationAllowed, reasoningEffort, banterLevel, null, null);
        }
        /** Compatibility for callers without banter metadata. */
        public TurnRequest(UUID gameId, String pgn, String fen, Board board, String sideToMove, int moveNumber,
                           List<String> legalMoves, String modelId, String playerName, String opponentName,
                           List<OpenRouterService.ChatMsg> history, String lastMoveSan, String lastMessage,
                           boolean isFirstMove, int maxRetries, Integer moveTimeoutSeconds, String promptVersion,
                           String promptHash, BigDecimal totalCostUsd, BooleanSupplier shouldContinue,
                           int maxOutputTokens, ChatMode chatMode, ChatMode tournamentChatMode,
                           boolean resignationAllowed, ReasoningEffort reasoningEffort) {
            this(gameId, pgn, fen, board, sideToMove, moveNumber, legalMoves, modelId, playerName, opponentName,
                    history, lastMoveSan, lastMessage, isFirstMove, maxRetries, moveTimeoutSeconds, promptVersion,
                    promptHash, totalCostUsd, shouldContinue, maxOutputTokens, chatMode, tournamentChatMode,
                    resignationAllowed, reasoningEffort, null);
        }

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
