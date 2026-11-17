package dev.aichessarena.service;

import dev.aichessarena.dto.LeaderboardDto;
import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.TournamentParticipant;
import dev.aichessarena.repository.LeaderboardRepository;
import dev.aichessarena.repository.MoveAttemptRepository;
import dev.aichessarena.repository.MoveRepository;
import dev.aichessarena.service.EloRatingService.RatedGame;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class LeaderboardService {

    @Inject
    LeaderboardRepository repository;

    @Inject
    EloRatingService ratings;

    @Inject
    MoveAttemptRepository moveAttemptRepository;

    @Inject
    MoveRepository moveRepository;

    /** Recompute per request so finishing, deleting or correcting games cannot leave stale ratings. */
    public LeaderboardDto getLeaderboard(boolean includeForfeits) {
        return ratings.calculate(repository.findFinishedModelGames().stream()
                .filter(LeaderboardService::isRatedGame)
                .map(game -> new RatedGame(game.id, game.completedAt, game.whiteModelId.trim(),
                        game.blackModelId.trim(), game.result))
                .toList(), includeForfeits, ReasoningAnalytics.bySide(
                        moveAttemptRepository.findReasoningAttempts(null, null),
                        CanonicalPlies.reasoningOf(moveRepository.findFinishedReasoningPlies())));
    }

    static boolean isRatedGame(Game game) {
        return AnalyticsGameOutcomeClassifier.isTerminal(game.status)
                && !AnalyticsGameOutcomeClassifier.isBye(game)
                && game.id != null && game.result != null && game.completedAt != null
                && hasModel(game.whiteModelId, game.whiteParticipant)
                && hasModel(game.blackModelId, game.blackParticipant);
    }

    private static boolean hasModel(String modelId, TournamentParticipant participant) {
        return modelId != null && !modelId.isBlank()
                && (participant == null || participant.controlType != TournamentParticipant.ControlType.HUMAN);
    }
}
