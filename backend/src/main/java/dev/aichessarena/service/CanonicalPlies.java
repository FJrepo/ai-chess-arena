package dev.aichessarena.service;

import dev.aichessarena.entity.Move;
import dev.aichessarena.repository.MoveRepository.ReasoningPly;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** Selects the first stored move per ply in databases where V8 could not add the unique index. */
public final class CanonicalPlies {

    /** Earliest stored first; the id breaks timestamp ties independently of load order. */
    private static final Comparator<Move> FIRST_STORED = storedOrder(move -> move.createdAt, move -> move.id);
    private static final Comparator<ReasoningPly> FIRST_STORED_REASONING =
            storedOrder(ReasoningPly::createdAt, ReasoningPly::id);

    private static <T> Comparator<T> storedOrder(Function<T, LocalDateTime> createdAt, Function<T, UUID> id) {
        return Comparator.comparing(createdAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(id, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    /** Moves in play order within each game, with White before Black. */
    public static final Comparator<Move> PLAY_ORDER = Comparator
            .comparingInt((Move move) -> move.moveNumber)
            .thenComparingInt(move -> "WHITE".equals(move.color) ? 0 : 1)
            .thenComparing(FIRST_STORED);

    private CanonicalPlies() {}

    /**
     * One move per (game, move number, color), using createdAt then id, in play order within each game.
     * The input and stored rows are unchanged; a later duplicate never supplies a missing evaluation.
     */
    public static List<Move> of(List<Move> moves) {
        List<Move> plies = firstByPly(moves,
                move -> new PlyKey(move.game == null ? null : move.game.id, move.moveNumber, move.color),
                FIRST_STORED);
        plies.sort(PLAY_ORDER);
        return plies;
    }

    /** Uses the same signed UUID tie-break as full moves, even on databases with legacy duplicates. */
    static List<ReasoningPly> reasoningOf(List<ReasoningPly> moves) {
        return firstByPly(moves, move -> new PlyKey(move.gameId(), move.moveNumber(), move.color()),
                FIRST_STORED_REASONING);
    }

    private static <T> List<T> firstByPly(List<T> moves, Function<T, PlyKey> key, Comparator<T> order) {
        Map<PlyKey, T> firstMovePerPly = new HashMap<>();
        for (T move : moves) {
            firstMovePerPly.merge(key.apply(move), move, (kept, other) -> order.compare(other, kept) < 0 ? other : kept);
        }
        return new ArrayList<>(firstMovePerPly.values());
    }

    private record PlyKey(UUID gameId, int moveNumber, String color) {}
}
