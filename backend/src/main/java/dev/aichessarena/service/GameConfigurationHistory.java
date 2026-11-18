package dev.aichessarena.service;

import dev.aichessarena.dto.GameConfigurationRevisionDto;
import dev.aichessarena.entity.ChatMode;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.GameConfiguration;
import dev.aichessarena.entity.GameConfigurationRevision;
import dev.aichessarena.entity.ReasoningEffort;
import dev.aichessarena.entity.Tournament;
import dev.aichessarena.entity.TournamentParticipant;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class GameConfigurationHistory {

    @Inject
    PromptService prompts;

    @Inject
    EntityManager entityManager;

    GameConfiguration capture(Game game) {
        Tournament tournament = game.tournament;
        return new GameConfiguration(
                tournament == null ? null : tournament.format.name(),
                tournament == null ? null : tournament.drawPolicy.name(),
                tournament == null ? null : tournament.matchupBestOf,
                tournament == null ? null : tournament.finalsBestOf,
                tournament == null ? null : tournament.openingMode.name(),
                tournament == null ? null : tournament.openingId,
                game.openingId,
                GameEngineService.moveTimeoutSecondsFor(game),
                tournament == null ? 3 : tournament.maxRetries,
                tournament == null ? Tournament.DEFAULT_MAX_OUTPUT_TOKENS : tournament.maxOutputTokens,
                tournament == null || tournament.chatMode == null ? ChatMode.OPTIONAL.name() : tournament.chatMode.name(),
                tournament == null || tournament.banterLevel == null ? null : tournament.banterLevel.name(),
                tournament != null && tournament.resignationAllowed,
                tournament != null && tournament.adjudicationEnabled,
                game.hasLegalMoveHints(),
                tournament == null ? null : apiValue(tournament.reasoningEffort),
                tournament == null ? null : fingerprint(tournament.sharedCustomInstructions),
                tournament == null ? null : fingerprint(tournament.defaultSystemPrompt),
                player(game.whiteParticipant, game.whitePlayerName, game.whiteModelId, game.playerType("WHITE"),
                        game.requestedReasoningEffort("WHITE")),
                player(game.blackParticipant, game.blackPlayerName, game.blackModelId, game.playerType("BLACK"),
                        game.requestedReasoningEffort("BLACK"))
        );
    }

    private GameConfiguration.Player player(TournamentParticipant participant, String name, String modelId,
                                            Game.PlayerType type, ReasoningEffort requested) {
        return new GameConfiguration.Player(name, modelId, type.name(),
                participant == null ? null : apiValue(participant.reasoningEffort),
                apiValue(requested),
                participant == null ? null : fingerprint(participant.customInstructions),
                participant == null ? null : fingerprint(participant.customSystemPrompt));
    }

    private static String apiValue(ReasoningEffort effort) {
        return effort == null ? null : effort.apiValue();
    }

    private String fingerprint(String text) {
        return text == null || text.isBlank() ? null : prompts.computePromptHash(text);
    }

    record Revision(int number, boolean added) {}

    /** The caller holds the game lock. Settings describe a model turn or the adjudication decision. */
    Revision recordRevision(UUID gameId, GameConfiguration settings) {
        GameConfigurationRevision latest = entityManager.createQuery("""
                from GameConfigurationRevision r where r.gameId = :gameId order by r.revision desc
                """, GameConfigurationRevision.class)
                .setParameter("gameId", gameId).setMaxResults(1).getSingleResultOrNull();
        if (latest != null && latest.settings.equals(settings)) {
            return new Revision(latest.revision, false);
        }

        GameConfigurationRevision row = new GameConfigurationRevision();
        row.gameId = gameId;
        row.revision = latest == null ? 1 : latest.revision + 1;
        row.settings = settings;
        entityManager.persist(row);
        return new Revision(row.revision, true);
    }

    /** Read in the same database snapshot as the game's attempts. */
    public List<GameConfigurationRevisionDto> read(UUID gameId) {
        return entityManager.createQuery("""
                select new dev.aichessarena.dto.GameConfigurationRevisionDto(r.revision, r.settings)
                from GameConfigurationRevision r where r.gameId = :gameId order by r.revision
                """, GameConfigurationRevisionDto.class)
                .setParameter("gameId", gameId).getResultList();
    }
}
