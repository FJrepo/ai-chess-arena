package dev.aichessarena.service;

import com.github.bhlangonijr.chesslib.Board;
import dev.aichessarena.repository.MoveRepository;
import dev.aichessarena.websocket.GameWebSocket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.jboss.logging.Logger;

@ApplicationScoped
public class GameTurnService {

    private static final Logger LOG = Logger.getLogger(GameTurnService.class);

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
                request.isFirstMove()
        );

        request.history().add(new OpenRouterService.ChatMsg("user", turnPrompt));

        Instant turnStartedAt = Instant.now();
        Instant turnDeadlineAt = turnStartedAt.plusSeconds(request.moveTimeoutSeconds());
        long turnDeadlineAtMs = turnDeadlineAt.toEpochMilli();

        broadcastTurnTiming(request.gameId(), request.totalCostUsd(), request.sideToMove(), turnStartedAt, turnDeadlineAt);

        for (int attempt = 1; attempt <= request.maxRetries(); attempt++) {
            if (!request.shouldContinue().getAsBoolean()) {
                return TurnResult.stopped();
            }

            long remainingMs = turnDeadlineAtMs - System.currentTimeMillis();
            if (remainingMs <= 0) {
                gameLifecycleService.forfeitTimeout(request.gameId(), request.sideToMove(), request.moveTimeoutSeconds());
                return TurnResult.terminal();
            }

            LOG.infof("Game %s: %s move %d, attempt %d",
                    request.gameId(), request.sideToMove(), request.moveNumber(), attempt);

            OpenRouterService.LlmResponse llmResponse = openRouterService.chat(
                    request.modelId(),
                    request.history(),
                    Duration.ofMillis(Math.max(remainingMs, 100L))
            );

            if (llmResponse.timedOut()) {
                LOG.warnf("Game %s: %s timed out while generating move", request.gameId(), request.modelId());
                gameLifecycleService.forfeitTimeout(request.gameId(), request.sideToMove(), request.moveTimeoutSeconds());
                return TurnResult.terminal();
            }

            if (llmResponse.content() == null) {
                LOG.errorf("Game %s: null response from %s", request.gameId(), request.modelId());
                broadcastRetry(request.gameId(), request.sideToMove(), attempt, "Model returned an empty response");
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
                LOG.warnf("Game %s: parse failure from %s: %s",
                        request.gameId(), request.modelId(), parseResult.message());
                broadcastRetry(request.gameId(), request.sideToMove(), attempt, "Failed to parse response as JSON");
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

            ChessService.ValidMoveResult moveResult = chessService.validateAndApply(request.board(), parseResult.move());
            if (!moveResult.valid()) {
                LOG.warnf("Game %s: illegal move '%s' from %s: %s",
                        request.gameId(), parseResult.move(), request.modelId(), moveResult.error());
                broadcastRetry(
                        request.gameId(),
                        request.sideToMove(),
                        attempt,
                        "Illegal move attempted: " + parseResult.move()
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
            gameMoveService.recordModelMove(
                    request.gameId(),
                    request.moveNumber(),
                    request.sideToMove(),
                    parseResult.move(),
                    moveResult.fen(),
                    request.modelId(),
                    request.playerName(),
                    parseResult.message(),
                    llmResponse,
                    attempt - 1,
                    request.promptVersion(),
                    request.promptHash()
            );
            return TurnResult.recorded(parseResult.move(), parseResult.message());
        }

        gameLifecycleService.forfeitInvalidMoves(request.gameId(), request.sideToMove(), request.maxRetries());
        return TurnResult.terminal();
    }

    private void broadcastRetry(UUID gameId, String color, int attemptNumber, String reason) {
        gameWebSocket.broadcastRetry(gameId, color, attemptNumber, reason);
    }

    private void broadcastTurnTiming(UUID gameId, BigDecimal totalCostUsd, String activeColor,
                                     Instant turnStartedAt, Instant turnDeadlineAt) {
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
                turnDeadlineAt
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
            int moveTimeoutSeconds,
            String promptVersion,
            String promptHash,
            BigDecimal totalCostUsd,
            BooleanSupplier shouldContinue
    ) {
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

        public boolean moveRecorded() {
            return status == Status.MOVE_RECORDED;
        }

        public boolean isTerminal() {
            return status == Status.TERMINAL;
        }
    }

    public enum Status {
        MOVE_RECORDED,
        STOPPED,
        TERMINAL
    }
}
