package dev.aichessarena.service;

import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Move;
import dev.aichessarena.repository.ChatMessageRepository;
import dev.aichessarena.repository.ChatMessageRepository.PlyMessage;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveAttemptRepository;
import dev.aichessarena.repository.MoveAttemptRepository.RejectedAttemptCost;
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
 * Byes are never part of a window. Moves, rejected attempts and chat messages follow the selected games.
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

    AnalyticsScope loadScope(int days, UUID tournamentId) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
        List<Game> games = gameRepository.findInWindow(cutoff, tournamentId).stream()
                .filter(game -> !AnalyticsGameOutcomeClassifier.isBye(game))
                .toList();
        return scopeFor(
                games,
                moveRepository.findInWindow(cutoff, tournamentId),
                moveAttemptRepository.findRejectedCostsInWindow(cutoff, tournamentId),
                chatMessageRepository.findPlyMessagesInWindow(cutoff, tournamentId)
        );
    }

    AnalyticsScope loadTerminalScope(int days, UUID tournamentId) {
        AnalyticsScope scope = loadScope(days, tournamentId);
        List<Game> terminalGames = scope.games().stream()
                .filter(game -> AnalyticsGameOutcomeClassifier.isTerminal(game.status))
                .toList();
        return scopeFor(terminalGames, scope.moves(), scope.rejectedAttemptCosts(), scope.messages());
    }

    /** Keeps only the moves, attempt costs and messages that belong to {@code games}. */
    static AnalyticsScope scopeFor(List<Game> games, List<Move> moves, List<RejectedAttemptCost> attemptCosts,
                                   List<PlyMessage> messages) {
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
        return new AnalyticsScope(games, gameMoves, gameAttemptCosts, gameMessages);
    }

    record AnalyticsScope(List<Game> games, List<Move> moves, List<RejectedAttemptCost> rejectedAttemptCosts,
                          List<PlyMessage> messages) {
    }
}
