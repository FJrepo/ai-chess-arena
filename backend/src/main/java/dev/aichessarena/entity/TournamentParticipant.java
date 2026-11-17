package dev.aichessarena.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "tournament_participants")
public class TournamentParticipant extends PanacheEntityBase {

    public enum ControlType {
        AI,
        HUMAN
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tournament_id", nullable = false)
    public Tournament tournament;

    @Column(name = "player_name", nullable = false)
    public String playerName;

    @Column(name = "model_id")
    public String modelId;

    @Enumerated(EnumType.STRING)
    @Column(name = "control_type", nullable = false)
    public ControlType controlType = ControlType.AI;

    @Column(name = "custom_system_prompt", columnDefinition = "TEXT")
    public String customSystemPrompt;

    @Column(name = "custom_instructions", columnDefinition = "TEXT")
    public String customInstructions;

    @Column(nullable = false)
    public int seed = 0;

    /**
     * Overrides the tournament's reasoning effort for this participant; null uses the tournament's. Can be changed
     * before the tournament ends (also while its games run or are paused); it applies from the participant's next turn.
     */
    @Convert(converter = ReasoningEffortConverter.class)
    @Column(name = "reasoning_effort", length = 16)
    public ReasoningEffort reasoningEffort;

    /** The level this participant asks for: its own override, else the tournament's; null for the model default. */
    public ReasoningEffort effectiveReasoningEffort() {
        if (reasoningEffort != null) {
            return reasoningEffort;
        }
        return tournament != null ? tournament.reasoningEffort : null;
    }
}
