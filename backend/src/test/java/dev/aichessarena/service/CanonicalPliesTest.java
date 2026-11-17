package dev.aichessarena.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.aichessarena.entity.Game;
import dev.aichessarena.entity.Move;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CanonicalPliesTest {

    @ParameterizedTest
    @ValueSource(strings = {"80000000-0000-0000-0000-000000000000", "00000000-0000-0000-8000-000000000000"})
    void reasoningProjectionUsesTheSameSignedUuidOrderingAsFullMoves(String earlierId) {
        Game game = new Game();
        game.id = UUID.randomUUID();
        Move later = new Move();
        later.game = game;
        later.color = "WHITE";
        later.moveNumber = 1;
        later.createdAt = LocalDateTime.of(2026, 1, 1, 0, 0);
        later.id = new UUID(0, 0);

        Move earlier = new Move();
        earlier.game = game;
        earlier.color = later.color;
        earlier.moveNumber = later.moveNumber;
        earlier.createdAt = later.createdAt;
        earlier.id = UUID.fromString(earlierId);
        // An earlier book/override ply must still shadow any later model duplicate.
        earlier.isBook = true;

        var moves = List.of(later, earlier);
        assertEquals(List.of(earlier), CanonicalPlies.of(moves));
        assertEquals(List.of(ReasoningAnalytics.modelPly(earlier)), CanonicalPlies.reasoningOf(
                moves.stream().map(ReasoningAnalytics::modelPly).toList()));
    }
}
