package dev.aichessarena.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "moves")
public class Move extends PanacheEntityBase {

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

    @Column(nullable = false, length = 20)
    public String san;

    @Column(nullable = false)
    public String fen;

    @Column(name = "model_id")
    public String modelId;

    @Column(name = "prompt_version", length = 64)
    public String promptVersion;

    @Column(name = "prompt_hash", length = 128)
    public String promptHash;

    @Column(name = "prompt_tokens")
    public Integer promptTokens;

    @Column(name = "completion_tokens")
    public Integer completionTokens;

    @Column(name = "cost_usd")
    public BigDecimal costUsd;

    @Column(name = "response_time_ms")
    public Long responseTimeMs;

    @Column(name = "retry_count", nullable = false)
    public int retryCount = 0;

    @Column(name = "raw_response", columnDefinition = "TEXT")
    public String rawResponse;

    @Column(name = "is_override", nullable = false)
    public boolean isOverride = false;

    @Column(name = "evaluation_cp")
    public Integer evaluationCp;

    @Column(name = "evaluation_mate")
    public Integer evaluationMate;

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt = LocalDateTime.now();
}
