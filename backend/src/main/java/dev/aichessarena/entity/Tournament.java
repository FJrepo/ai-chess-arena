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

    @Column(name = "shared_custom_instructions", columnDefinition = "TEXT")
    public String sharedCustomInstructions;

    /** Time limit for a whole move (all attempts) in seconds; null means no time limit per move. */
    @Column(name = "move_timeout_seconds")
    public Integer moveTimeoutSeconds = DEFAULT_MOVE_TIMEOUT_SECONDS;

    public static final int DEFAULT_MOVE_TIMEOUT_SECONDS = 60;
    public static final int MIN_MOVE_TIMEOUT_SECONDS = 10;
    public static final int MAX_MOVE_TIMEOUT_SECONDS = 3600;

    @Column(name = "max_retries", nullable = false)
    public int maxRetries = 3;

    @Column(name = "matchup_best_of", nullable = false)
    public int matchupBestOf = 1;

    @Column(name = "finals_best_of")
    public Integer finalsBestOf;

    /** Whether and how models talk (see {@link ChatMode}); a change applies from the next turn of running games. */
    @Enumerated(EnumType.STRING)
    @Column(name = "chat_mode", nullable = false, length = 16)
    public ChatMode chatMode = ChatMode.OPTIONAL;

    /**
     * The pre-1.4 column {@code trash_talk_enabled}, kept in sync with {@link #chatMode} (false only for OFF). Not
     * read by this version; to be dropped in a later release. Rolling back to an older version requires restoring
     * the pre-upgrade database backup (see docs/deployment.md).
     */
    @Column(name = "trash_talk_enabled", nullable = false)
    boolean legacyTrashTalkEnabled = true;

    /**
     * Whether models may resign instead of moving, from their {@value #MIN_RESIGNATION_MOVE}th move on. Off by
     * default (every game is played to the end); a change applies from the next turn of running games.
     */
    @Column(name = "resignation_allowed", nullable = false)
    public boolean resignationAllowed;

    /** The first move number at which a side may resign: a game cannot end on move 3 because a model gives up. */
    public static final int MIN_RESIGNATION_MOVE = 10;

    /** Cap on generated tokens per model answer (OpenRouter max_tokens; includes reasoning tokens). */
    @Column(name = "max_output_tokens", nullable = false)
    public int maxOutputTokens = DEFAULT_MAX_OUTPUT_TOKENS;

    public static final int DEFAULT_MAX_OUTPUT_TOKENS = 4096;
    public static final int MIN_MAX_OUTPUT_TOKENS = 128;
    public static final int MAX_MAX_OUTPUT_TOKENS = 32768;

    /** Client-generated id of the create request; makes retried creations return the same tournament. */
    @Column(name = "client_request_id", unique = true)
    public UUID clientRequestId;

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    public LocalDateTime updatedAt = LocalDateTime.now();

    @OneToMany(mappedBy = "tournament", cascade = CascadeType.ALL, orphanRemoval = true)
    public List<TournamentParticipant> participants = new ArrayList<>();

    @OneToMany(mappedBy = "tournament")
    public List<Game> games = new ArrayList<>();

    /** Panache routes writes of {@code chatMode} through this setter, which keeps the legacy column in sync. */
    public void setChatMode(ChatMode chatMode) {
        this.chatMode = chatMode;
        this.legacyTrashTalkEnabled = chatMode != ChatMode.OFF;
    }

    public ChatMode getChatMode() {
        return chatMode;
    }

    /** True unless chat is OFF; the API still reports this as {@code trashTalkEnabled}. */
    public boolean isTrashTalkEnabled() {
        return chatMode != ChatMode.OFF;
    }

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
