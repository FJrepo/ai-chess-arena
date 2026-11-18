package dev.aichessarena.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "games")
public class Game extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tournament_id")
    public Tournament tournament;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "white_participant_id")
    public TournamentParticipant whiteParticipant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "black_participant_id")
    public TournamentParticipant blackParticipant;

    @Enumerated(EnumType.STRING)
    @Column(name = "practice_kind", length = 32)
    public PracticeKind practiceKind;

    @Enumerated(EnumType.STRING)
    @Column(name = "white_practice_control", length = 16)
    public PlayerType whitePracticeControl;

    @Enumerated(EnumType.STRING)
    @Column(name = "black_practice_control", length = 16)
    public PlayerType blackPracticeControl;

    @Column(name = "practice_source_game_id")
    public UUID practiceSourceGameId;

    @Column(name = "practice_source_ply")
    public Integer practiceSourcePly;

    /** Immutable creation identity retained when deletion clears the source navigation link. */
    @Column(name = "practice_origin_game_id", updatable = false)
    public UUID practiceOriginGameId;

    @Column(name = "practice_request_id", updatable = false)
    public UUID practiceRequestId;

    @Column(name = "practice_request_move_id", updatable = false)
    public UUID practiceRequestMoveId;

    @Column(name = "stockfish_elo")
    public Integer stockfishElo;

    @Column(name = "white_player_name")
    public String whitePlayerName;

    @Column(name = "white_model_id")
    public String whiteModelId;

    @Column(name = "black_player_name")
    public String blackPlayerName;

    @Column(name = "black_model_id")
    public String blackModelId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public GameStatus status = GameStatus.WAITING;

    /**
     * Grows by one with every status or resume request change made by GameLifecycleService, in the same
     * transaction and under the row lock. Live status updates carry it so clients can ignore an older update that
     * arrives after a newer one.
     */
    @Column(name = "status_revision", nullable = false)
    public long statusRevision;

    @Column(name = "live_revision", nullable = false)
    public long liveRevision;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "starting_configuration")
    public GameConfiguration startingConfiguration;

    @Column(name = "adjudication_configuration_revision")
    public Integer adjudicationConfigurationRevision;

    @Column(name = "turn_started_at")
    public Instant turnStartedAt;

    @Column(name = "turn_deadline_at")
    public Instant turnDeadlineAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "turn_chat_mode", length = 16)
    public ChatMode turnChatMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "turn_banter_level", length = 16)
    public BanterLevel turnBanterLevel;

    /**
     * The token of the game loop that owns this game, or null when no loop does (not started, paused, finished or
     * waiting for a human move). Every write a loop makes checks it under the row lock, so a stale loop (for example
     * one from before a restart) can never commit a move or record an attempt. See GameRunOwnership.
     */
    @Column(name = "run_owner")
    public UUID runOwner;

    /** Until when {@link #runOwner} holds the game without renewing; set exactly when there is an owner. */
    @Column(name = "run_lease_until")
    public Instant runLeaseUntil;

    /**
     * When a model request was sent whose answer is not recorded yet; null otherwise. Set before the request leaves and
     * cleared in the transaction that records its answer, so after a restart it shows a request that may already have
     * been charged.
     */
    @Column(name = "request_sent_at")
    public Instant requestSentAt;

    public void clearTurn() {
        turnStartedAt = null;
        turnDeadlineAt = null;
        turnChatMode = null;
        turnBanterLevel = null;
    }

    public String activeColor() {
        if (status != GameStatus.IN_PROGRESS || currentFen == null) return null;
        String[] fields = currentFen.split(" ", 3);
        if (fields.length < 2) return null;
        return switch (fields[1]) {
            case "w" -> "WHITE";
            case "b" -> "BLACK";
            default -> null;
        };
    }

    @Enumerated(EnumType.STRING)
    public GameResult result;

    @Enumerated(EnumType.STRING)
    @Column(name = "result_reason")
    public ResultReason resultReason;

    @Column(name = "status_detail", length = 500)
    public String statusDetail;

    @Column(name = "resume_requested_at")
    public Instant resumeRequestedAt;

    /** Set on the game that decided its series: who advanced (also when a draw policy decided a level series). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "advanced_participant_id")
    public TournamentParticipant advancedParticipant;

    /** Immutable book metadata copied at game creation; null for standard games and legacy rows. */
    @Column(name = "opening_id", length = 64)
    public String openingId;

    @Column(name = "opening_name", length = 120)
    public String openingName;

    @Column(name = "opening_eco", length = 3)
    public String openingEco;

    @Column(columnDefinition = "TEXT")
    public String pgn;

    @Column(name = "current_fen", nullable = false)
    public String currentFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    @Column(name = "bracket_round")
    public String bracketRound;

    @Column(name = "bracket_position")
    public Integer bracketPosition;

    @Column(name = "series_id")
    public UUID seriesId;

    @Column(name = "series_game_number", nullable = false)
    public int seriesGameNumber = 1;

    @Column(name = "series_best_of", nullable = false)
    public int seriesBestOf = 1;

    @Column(name = "total_cost_usd", nullable = false, precision = 12, scale = 8)
    public BigDecimal totalCostUsd = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "started_at")
    public LocalDateTime startedAt;

    @Column(name = "completed_at")
    public LocalDateTime completedAt;

    /** Play order: White and Black share a move number, and "WHITE" sorts after "BLACK", hence color DESC. */
    @OneToMany(mappedBy = "game", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("moveNumber ASC, color DESC, createdAt ASC, id ASC")
    public List<Move> moves = new ArrayList<>();

    @OneToMany(mappedBy = "game", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt ASC")
    public List<ChatMessage> chatMessages = new ArrayList<>();

    public boolean hasLegalMoveHints() {
        return tournament == null || tournament.legalMoveHints;
    }

    /**
     * The reasoning effort a side asks for: its participant's override, else the tournament's; null for the model
     * default, a human side and a game outside a tournament. Read anew every turn, so a change applies from the next.
     */
    public ReasoningEffort requestedReasoningEffort(String color) {
        TournamentParticipant participant = "WHITE".equals(color) ? whiteParticipant : blackParticipant;
        if (participant != null) {
            return participant.controlType == TournamentParticipant.ControlType.HUMAN
                    ? null : participant.effectiveReasoningEffort();
        }
        return tournament != null ? tournament.reasoningEffort : null;
    }

    public boolean isPractice() {
        return practiceKind != null;
    }

    public PlayerType playerType(String color) {
        boolean white = "WHITE".equals(color);
        if (isPractice()) return white ? whitePracticeControl : blackPracticeControl;
        TournamentParticipant participant = white ? whiteParticipant : blackParticipant;
        return participant != null && participant.controlType == TournamentParticipant.ControlType.HUMAN
                ? PlayerType.HUMAN : PlayerType.AI;
    }

    public enum PlayerType {
        AI, HUMAN, STOCKFISH
    }

    public enum PracticeKind {
        FROM_GAME, HUMAN_STOCKFISH
    }

    public enum GameStatus {
        WAITING, IN_PROGRESS, PAUSED, COMPLETED, FORFEIT, CANCELLED
    }

    public enum GameResult {
        WHITE_WINS, BLACK_WINS, DRAW, WHITE_FORFEIT, BLACK_FORFEIT
    }

    public enum ResultReason {
        CHECKMATE, STALEMATE, REPETITION, FIFTY_MOVE, INSUFFICIENT_MATERIAL,
        TIMEOUT, FORFEIT_INVALID_MOVES, RESIGNATION, ADJUDICATION
    }
}
