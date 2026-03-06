package dev.aichessarena.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "tournament_participants")
public class TournamentParticipant extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tournament_id", nullable = false)
    public Tournament tournament;

    @Column(name = "player_name", nullable = false)
    public String playerName;

    @Column(name = "model_id", nullable = false)
    public String modelId;

    @Column(name = "custom_system_prompt", columnDefinition = "TEXT")
    public String customSystemPrompt;

    @Column(name = "custom_instructions", columnDefinition = "TEXT")
    public String customInstructions;

    @Column(nullable = false)
    public int seed = 0;
}
