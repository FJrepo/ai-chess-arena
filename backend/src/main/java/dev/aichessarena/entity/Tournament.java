package dev.aichessarena.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "tournaments")
public class Tournament extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @Column(nullable = false)
    public String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public TournamentStatus status = TournamentStatus.CREATED;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public TournamentFormat format = TournamentFormat.SINGLE_ELIMINATION;

    @Enumerated(EnumType.STRING)
    @Column(name = "draw_policy", nullable = false)
    public DrawPolicy drawPolicy = DrawPolicy.WHITE_ADVANCES;

    @Column(name = "default_system_prompt", columnDefinition = "TEXT")
    public String defaultSystemPrompt;

    @Column(name = "move_timeout_seconds", nullable = false)
    public int moveTimeoutSeconds = 60;

    @Column(name = "max_retries", nullable = false)
    public int maxRetries = 3;

    @Column(name = "trash_talk_enabled", nullable = false)
    public boolean trashTalkEnabled = true;

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    public LocalDateTime updatedAt = LocalDateTime.now();

    @OneToMany(mappedBy = "tournament", cascade = CascadeType.ALL, orphanRemoval = true)
    public List<TournamentParticipant> participants = new ArrayList<>();

    @OneToMany(mappedBy = "tournament")
    public List<Game> games = new ArrayList<>();

    public enum TournamentStatus {
        CREATED, IN_PROGRESS, COMPLETED, CANCELLED
    }

    public enum TournamentFormat {
        SINGLE_ELIMINATION
    }

    public enum DrawPolicy {
        WHITE_ADVANCES,
        BLACK_ADVANCES,
        HIGHER_SEED_ADVANCES,
        RANDOM_ADVANCES,
        REPLAY_GAME
    }
}
