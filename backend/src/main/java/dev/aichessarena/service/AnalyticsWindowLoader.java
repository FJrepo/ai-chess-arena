package dev.aichessarena.service;

import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Move;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class AnalyticsWindowLoader {

    @Inject
    GameRepository gameRepository;

    @Inject
    MoveRepository moveRepository;

    AnalyticsScope loadScope(int days, UUID tournamentId) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
        List<Game> games = tournamentId == null
                ? gameRepository.findCreatedAfter(cutoff)
                : gameRepository.findByTournamentIdAndCreatedAfter(tournamentId, cutoff);
        List<Move> moves = tournamentId == null
                ? moveRepository.findCreatedAfter(cutoff)
                : moveRepository.findByTournamentIdAndCreatedAfter(tournamentId, cutoff);
        return new AnalyticsScope(games, moves);
    }

    AnalyticsScope loadTerminalScope(int days, UUID tournamentId) {
        AnalyticsScope scope = loadScope(days, tournamentId);
        List<Game> terminalGames = scope.games().stream()
                .filter(game -> game.status == Game.GameStatus.COMPLETED || game.status == Game.GameStatus.FORFEIT)
                .toList();

        Map<UUID, Game> terminalGamesById = terminalGames.stream()
                .collect(Collectors.toMap(game -> game.id, game -> game));

        List<Move> terminalMoves = scope.moves().stream()
                .filter(move -> move.game != null && move.game.id != null && terminalGamesById.containsKey(move.game.id))
                .toList();

        return new AnalyticsScope(terminalGames, terminalMoves);
    }

    record AnalyticsScope(List<Game> games, List<Move> moves) {
    }
}
