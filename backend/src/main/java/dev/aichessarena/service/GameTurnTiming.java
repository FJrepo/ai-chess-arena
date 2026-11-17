package dev.aichessarena.service;

import dev.aichessarena.entity.ChatMode;
import dev.aichessarena.entity.Game;
import dev.aichessarena.repository.GameRepository;
import dev.aichessarena.repository.MoveRepository;
import dev.aichessarena.websocket.GameWebSocket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.transaction.TransactionSynchronizationRegistry;

import java.time.Instant;

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

    record TurnTimes(Instant startedAt, Instant deadlineAt) {
        static TurnTimes start(Integer timeoutSeconds) {
            Instant startedAt = Instant.now();
            return new TurnTimes(startedAt, timeoutSeconds == null ? null : startedAt.plusSeconds(timeoutSeconds));
        }
    }

    @Transactional
    TurnTimes record(GameTurnService.TurnRequest request) {
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
        TurnTimes timing = TurnTimes.start(request.moveTimeoutSeconds());
        game.turnStartedAt = timing.startedAt();
        game.turnDeadlineAt = timing.deadlineAt();
        String chatMode = request.tournamentChatMode() == null ? null : request.tournamentChatMode().name();
        String banter = request.tournamentBanterLevel() == null ? null : request.tournamentBanterLevel().name();
        game.turnChatMode = request.chatMode() == null ? ChatMode.OPTIONAL : request.chatMode();
        boolean appliesTone = game.turnChatMode.allowsMessages()
                && !PromptService.LEGACY_CUSTOM_TEMPLATE_VERSION.equals(request.promptVersion());
        game.turnBanterLevel = appliesTone ? request.tournamentBanterLevel() : null;
        updates.changed(game);
        AfterCommit.run(registry, () -> socket.broadcastGameStatus(id, "IN_PROGRESS", null, null, totalMoves,
                cost, color, timing.startedAt(), timing.deadlineAt(), null, chatMode, banter));
        return timing;
    }
}
