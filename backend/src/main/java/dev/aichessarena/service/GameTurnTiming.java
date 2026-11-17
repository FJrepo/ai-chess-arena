package dev.aichessarena.service;

import dev.aichessarena.entity.Game;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveRepository;
import dev.aichessarena.websocket.GameWebSocket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.transaction.TransactionSynchronizationRegistry;

import java.time.Duration;
import java.time.Instant;
import java.util.function.LongSupplier;

@ApplicationScoped
class GameTurnTiming {

    @Inject
    GameRepository games;

    @Inject
    MoveRepository moves;

    @Inject
    LiveGameUpdates updates;

    @Inject
    GameWebSocket socket;

    @Inject
    TransactionSynchronizationRegistry registry;

    record TurnTimes(Instant startedAt, Instant deadlineAt, OpenRouterService.AnswerDeadline deadline) {
        static TurnTimes start(Integer timeoutSeconds, LongSupplier nanoClock) {
            Instant startedAt = Instant.now();
            if (timeoutSeconds == null) return new TurnTimes(startedAt, null, null);
            return new TurnTimes(startedAt, startedAt.plusSeconds(timeoutSeconds),
                    OpenRouterService.AnswerDeadline.after(Duration.ofSeconds(timeoutSeconds), nanoClock.getAsLong()));
        }
    }

    @Transactional
    TurnTimes record(GameTurnService.TurnRequest request, LongSupplier nanoClock) {
        Game game = games.findByIdForUpdate(request.gameId());
        if (game == null || game.status != Game.GameStatus.IN_PROGRESS || !request.shouldContinue().getAsBoolean()
                || !GameMoveService.samePosition(game.currentFen, request.fen())
                || GameLifecycleService.isTournamentCancelled(game)
                || (request.statusRevision() != null && request.statusRevision() != game.statusRevision)) {
            return null;
        }
        var id = game.id;
        int totalMoves = Math.toIntExact(moves.count("game.id", id));
        var cost = game.totalCostUsd;
        String color = game.activeColor();
        TurnTimes timing = TurnTimes.start(request.moveTimeoutSeconds(), nanoClock);
        game.turnStartedAt = timing.startedAt();
        game.turnDeadlineAt = timing.deadlineAt();
        String chatMode = request.tournamentChatMode() == null ? null : request.tournamentChatMode().name();
        String banter = request.tournamentBanterLevel() == null ? null : request.tournamentBanterLevel().name();
        var metadata = updates.change("turnSettings", game.id);
        if (chatMode != null) metadata.put("tournamentChatMode", chatMode).put("tournamentBanterLevel", banter);
        updates.changed(game, metadata);
        AfterCommit.run(registry, () -> socket.broadcastGameStatus(id, "IN_PROGRESS", null, null, totalMoves,
                cost, color, timing.startedAt(), timing.deadlineAt(), null, chatMode, banter));
        return timing;
    }
}
