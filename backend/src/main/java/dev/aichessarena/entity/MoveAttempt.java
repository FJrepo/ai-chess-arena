package dev.aichessarena.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** One model attempt at a ply, including failed ones (illegal move, unparseable, truncated, provider errors). */
@Entity
@Table(name = "move_attempts")
public class MoveAttempt extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "game_id", nullable = false)
    public Game game;

    @Column(name = "move_number", nullable = false)
    public int moveNumber;

    @Column(nullable = false)
    public String color;

    @Column(name = "attempt_number", nullable = false)
    public int attemptNumber;

    @Column(name = "model_id")
    public String modelId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public Outcome outcome;

    @Column(name = "proposed_move")
    public String proposedMove;

    @Column(name = "prompt_tokens")
    public Integer promptTokens;

    @Column(name = "completion_tokens")
    public Integer completionTokens;

    @Column(name = "reasoning_tokens")
    public Integer reasoningTokens;

    @Column(name = "cost_usd", precision = 12, scale = 8)
    public BigDecimal costUsd;

    @Column(name = "response_time_ms")
    public Long responseTimeMs;

    @Column(name = "finish_reason")
    public String finishReason;

    @Column(name = "error_message", length = 500)
    public String errorMessage;

    @Column(name = "raw_response", columnDefinition = "TEXT")
    public String rawResponse;

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt = LocalDateTime.now();

    public enum Outcome {
        ACCEPTED(false),
        ILLEGAL_MOVE(true),
        PARSE_FAILURE(true),
        EMPTY_RESPONSE(true),
        /** Cut off at the tournament's output-token limit: a configuration problem, not the model's fault. */
        TRUNCATED(false),
        TIMEOUT(true),
        PROVIDER_RETRYABLE(false),
        PROVIDER_FATAL(false),
        /** A legal answer that was not played because the position or game status changed meanwhile. */
        DISCARDED(false),
        /**
         * The model tried to resign without a move although resigning was not allowed (not offered in the game, or
         * before its 10th move): an invalid answer like an illegal move, retried and counted the same way.
         */
        RESIGN_NOT_ALLOWED(true),
        /** An allowed resignation: the game ended with the opponent winning. Costed with the game result. */
        RESIGNED(false);

        private final boolean modelFailure;

        Outcome(boolean modelFailure) {
            this.modelFailure = modelFailure;
        }

        /**
         * Whether the attempt failed through the model's own fault. Only these may count against a model's
         * reliability; cut-off answers, provider errors and discarded legal answers must not.
         */
        public boolean isModelFailure() {
            return modelFailure;
        }

        /**
         * Whether the answer's cost reaches the game total elsewhere: an accepted move with the move, a resignation
         * with the game result. All other attempts add their cost when they are recorded.
         */
        public boolean isCostedElsewhere() {
            return this == ACCEPTED || this == RESIGNED;
        }
    }
}
