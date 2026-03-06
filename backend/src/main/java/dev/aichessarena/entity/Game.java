package dev.aichessarena.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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

    @Enumerated(EnumType.STRING)
    public GameResult result;

    @Enumerated(EnumType.STRING)
    @Column(name = "result_reason")
    public ResultReason resultReason;

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

    @Column(name = "total_cost_usd", nullable = false)
    public BigDecimal totalCostUsd = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "started_at")
    public LocalDateTime startedAt;

    @Column(name = "completed_at")
    public LocalDateTime completedAt;

    @OneToMany(mappedBy = "game", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("moveNumber ASC")
    public List<Move> moves = new ArrayList<>();

    @OneToMany(mappedBy = "game", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt ASC")
    public List<ChatMessage> chatMessages = new ArrayList<>();

    public enum GameStatus {
        WAITING, IN_PROGRESS, PAUSED, COMPLETED, FORFEIT
    }

    public enum GameResult {
        WHITE_WINS, BLACK_WINS, DRAW, WHITE_FORFEIT, BLACK_FORFEIT
    }

    public enum ResultReason {
        CHECKMATE, STALEMATE, REPETITION, FIFTY_MOVE, INSUFFICIENT_MATERIAL,
        TIMEOUT, FORFEIT_INVALID_MOVES, RESIGNATION
    }
}
