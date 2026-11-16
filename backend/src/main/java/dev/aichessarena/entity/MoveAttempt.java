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
        ACCEPTED,
        ILLEGAL_MOVE,
        PARSE_FAILURE,
        EMPTY_RESPONSE,
        TRUNCATED,
        TIMEOUT,
        PROVIDER_RETRYABLE,
        PROVIDER_FATAL
    }
}
