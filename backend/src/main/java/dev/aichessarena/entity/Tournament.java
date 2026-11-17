package dev.aichessarena.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.math.BigDecimal;
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
    @Column(name = "auto_run_status", nullable = false, length = 16)
    public AutoRunStatus autoRunStatus = AutoRunStatus.OFF;

    @Column(name = "max_concurrent_games", nullable = false)
    public int maxConcurrentGames = 2;

    @Column(name = "budget_usd", precision = 19, scale = 2)
    public BigDecimal budgetUsd;

    @Column(name = "auto_run_pause_reason", length = 500)
    public String autoRunPauseReason;

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

    /**
     * No move time limit for new tournaments and games outside a tournament. Explicit limits remain hard
     * deadlines (running out of time forfeits). Existing tournaments keep their stored value.
     */
    public static final Integer DEFAULT_MOVE_TIMEOUT_SECONDS = null;
    public static final int MIN_MOVE_TIMEOUT_SECONDS = 10;
    public static final int MAX_MOVE_TIMEOUT_SECONDS = 3600;

    public static final int MIN_MOVE_ATTEMPTS = 1;
    public static final int MAX_MOVE_ATTEMPTS = 20;

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

    /** Optional tone for chat; applied from the next turn and ignored when chat is OFF. */
    @Enumerated(EnumType.STRING)
    @Column(name = "banter_level", length = 16)
    public BanterLevel banterLevel;

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

    /** Optional decisive-position adjudication for model-vs-model games, read anew each turn. */
    @Column(name = "adjudication_enabled", nullable = false)
    public boolean adjudicationEnabled;

    @Enumerated(EnumType.STRING)
    @Column(name = "opening_mode", nullable = false, length = 16)
    public OpeningMode openingMode = OpeningMode.STANDARD;

    @Column(name = "opening_id", length = 64)
    public String openingId;

    /** The first move number at which a side may resign: a game cannot end on move 3 because a model gives up. */
    public static final int MIN_RESIGNATION_MOVE = 10;

    /**
     * OpenRouter {@code reasoning.effort} for every model of the tournament unless a participant overrides it; null
     * means the model's default (nothing is sent). A change applies from the next turn of running games.
     */
    @Convert(converter = ReasoningEffortConverter.class)
    @Column(name = "reasoning_effort", length = 16)
    public ReasoningEffort reasoningEffort;

    /** Cap on generated tokens per model answer (OpenRouter max_tokens; includes reasoning tokens). */
    @Column(name = "max_output_tokens", nullable = false)
    public int maxOutputTokens = DEFAULT_MAX_OUTPUT_TOKENS;

    public static final int DEFAULT_MAX_OUTPUT_TOKENS = 16384;
    public static final int MIN_MAX_OUTPUT_TOKENS = 128;
    public static final int MAX_MAX_OUTPUT_TOKENS = 262144;

    /** Client-generated id of the create request; makes retried creations return the same tournament. */
    @Column(name = "client_request_id", unique = true)
    public UUID clientRequestId;

    /** Original create intent, used to finish generation after an interrupted request; null means draft/legacy. */
    @Enumerated(EnumType.STRING)
    @Column(name = "creation_draw_mode", length = 16)
    public BracketDrawMode creationDrawMode;

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    public LocalDateTime updatedAt = LocalDateTime.now();

    // Locking refreshes this row only. Participant writers refresh their own row under the tournament
    // lock; cascading REFRESH through an already loaded participant can re-enter this tournament's load.
    @OneToMany(mappedBy = "tournament", cascade = {CascadeType.PERSIST, CascadeType.MERGE, CascadeType.REMOVE,
            CascadeType.DETACH}, orphanRemoval = true)
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

    public enum AutoRunStatus {
        OFF, RUNNING, PAUSED, FINISHED
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
