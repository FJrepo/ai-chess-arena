package dev.aichessarena.service;

import io.quarkus.arc.Arc;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class MoveEvaluationCoordinator {

    private static final Logger LOG = Logger.getLogger(MoveEvaluationCoordinator.class);

    @Inject
    StockfishService stockfishService;

    void queueEvaluation(@Observes(during = TransactionPhase.AFTER_SUCCESS) MoveEvaluationRequested request) {
        LOG.infof("Queuing Stockfish evaluation for move %d in game %s", request.moveNumber(), request.gameId());
        stockfishService.evaluate(request.fen(), 12).thenAccept(result -> {
            try {
                Arc.container().instance(GameEngineService.class).get().updateMoveEvaluation(request.moveId(), result);
            } catch (Exception e) {
                LOG.errorf(e, "Failed to update move evaluation for move %s", request.moveId());
            }
        }).exceptionally(ex -> {
            LOG.errorf(ex, "Stockfish evaluation failed for move %s", request.moveId());
            return null;
        });
    }
}
