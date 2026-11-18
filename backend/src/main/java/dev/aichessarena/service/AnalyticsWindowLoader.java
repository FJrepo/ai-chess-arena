package dev.aichessarena.service;

import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Move;
import dev.aichessarena.repository.ChatMessageRepository;
import dev.aichessarena.repository.ChatMessageRepository.PlyMessage;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveAttemptRepository;
import dev.aichessarena.repository.MoveAttemptRepository.AttemptUsage;
import dev.aichessarena.repository.MoveAttemptRepository.RejectedAttemptCost;
import dev.aichessarena.repository.MoveAttemptRepository.ResignationTurnAttempt;
import dev.aichessarena.repository.MoveRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Loads the games of an analytics window: finished games by completion time, unfinished ones by creation time.
 * Byes are never part of a window. Related rows follow the selected games.
 */
@ApplicationScoped
public class AnalyticsWindowLoader {

    @Inject
    GameRepository gameRepository;

    @Inject
    MoveRepository moveRepository;

    @Inject
    MoveAttemptRepository moveAttemptRepository;

    @Inject
    ChatMessageRepository chatMessageRepository;

    AnalyticsScope loadHealthScope(int days, UUID tournamentId) {
        return loadHealthScope(days, tournamentId, null);
    }

    AnalyticsScope loadHealthScope(int days, UUID tournamentId, Boolean legalMoveHints) {
        return loadScope(days, tournamentId, legalMoveHints, false);
    }

    private AnalyticsScope loadScope(int days, UUID tournamentId, Boolean legalMoveHints, boolean includeModelAnalytics) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
        List<Game> games = gameRepository.findInWindow(cutoff, tournamentId, legalMoveHints).stream()
                .filter(game -> !AnalyticsGameOutcomeClassifier.isBye(game))
                .filter(game -> !includeModelAnalytics || !game.isPractice())
                .filter(game -> game.status != Game.GameStatus.CANCELLED || game.isPractice())
                .toList();
        return scopeFor(
                cutoff,
                games,
                moveRepository.findInWindow(cutoff, tournamentId),
                moveAttemptRepository.findRejectedCostsInWindow(cutoff, tournamentId),
                includeModelAnalytics ? chatMessageRepository.findPlyMessagesInWindow(cutoff, tournamentId) : List.of(),
                includeModelAnalytics
                        ? moveAttemptRepository.findResignationTurnAttemptsInWindow(cutoff, tournamentId) : List.of(),
                includeModelAnalytics ? moveAttemptRepository.findUsageInWindow(cutoff, tournamentId) : List.of()
        );
    }

    AnalyticsScope loadTerminalScope(int days, UUID tournamentId) {
        return loadTerminalScope(days, tournamentId, null);
    }

    AnalyticsScope loadTerminalScope(int days, UUID tournamentId, Boolean legalMoveHints) {
        AnalyticsScope scope = loadScope(days, tournamentId, legalMoveHints, true);
        List<Game> terminalGames = scope.games().stream()
                .filter(game -> AnalyticsGameOutcomeClassifier.isTerminal(game.status))
                .toList();
        return scopeFor(scope.cutoff(), terminalGames, scope.moves(), scope.rejectedAttemptCosts(), scope.messages(),
                scope.resignationAttempts(), scope.attemptUsage());
    }

    /** Keeps only the moves, attempt costs, messages and resignation attempts that belong to {@code games}. */
    static AnalyticsScope scopeFor(LocalDateTime cutoff, List<Game> games, List<Move> moves, List<RejectedAttemptCost> attemptCosts,
                                   List<PlyMessage> messages, List<ResignationTurnAttempt> resignationAttempts,
                                   List<AttemptUsage> attemptUsage) {
        Set<UUID> gameIds = games.stream().map(game -> game.id).collect(Collectors.toSet());
        List<Move> gameMoves = moves.stream()
                .filter(move -> move.game != null && gameIds.contains(move.game.id))
                .toList();
        List<RejectedAttemptCost> gameAttemptCosts = attemptCosts.stream()
                .filter(cost -> gameIds.contains(cost.gameId()))
                .toList();
        List<PlyMessage> gameMessages = messages.stream()
                .filter(message -> gameIds.contains(message.gameId()))
                .toList();
        List<ResignationTurnAttempt> gameResignationAttempts = resignationAttempts.stream()
                .filter(attempt -> gameIds.contains(attempt.gameId()))
                .toList();
        List<AttemptUsage> gameUsage = attemptUsage.stream()
                .filter(usage -> gameIds.contains(usage.gameId()))
                .toList();
        return new AnalyticsScope(cutoff, games, gameMoves, gameAttemptCosts, gameMessages, gameResignationAttempts, gameUsage);
    }

    record AnalyticsScope(LocalDateTime cutoff, List<Game> games, List<Move> moves, List<RejectedAttemptCost> rejectedAttemptCosts,
                          List<PlyMessage> messages, List<ResignationTurnAttempt> resignationAttempts,
                          List<AttemptUsage> attemptUsage) {
    }
}
