package dev.aichessarena.service;

import dev.aichessarena.entity.Game;
import dev.aichessarena.repository.GameRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.util.UUID;

@ApplicationScoped
public class CostTrackingService {

    @Inject
    GameRepository gameRepository;

    @Transactional
    public void addMoveCost(UUID gameId, BigDecimal moveCost) {
        Game game = gameRepository.findById(gameId);
        if (game != null && moveCost != null) {
            game.totalCostUsd = game.totalCostUsd.add(moveCost);
            gameRepository.persist(game);
        }
    }
}
