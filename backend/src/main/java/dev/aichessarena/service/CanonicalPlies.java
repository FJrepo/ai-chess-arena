package dev.aichessarena.service;

import dev.aichessarena.entity.Move;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Selects the first stored move per ply in databases where V8 could not add the unique index. */
public final class CanonicalPlies {

    /** Earliest stored first; the id breaks timestamp ties independently of load order. */
    private static final Comparator<Move> FIRST_STORED = Comparator
            .comparing((Move move) -> move.createdAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(move -> move.id, Comparator.nullsLast(Comparator.naturalOrder()));

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
        Map<PlyKey, Move> firstMovePerPly = new HashMap<>();
        for (Move move : moves) {
            UUID gameId = move.game == null ? null : move.game.id;
            firstMovePerPly.merge(new PlyKey(gameId, move.moveNumber, move.color), move,
                    (kept, other) -> FIRST_STORED.compare(other, kept) < 0 ? other : kept);
        }
        List<Move> plies = new ArrayList<>(firstMovePerPly.values());
        plies.sort(PLAY_ORDER);
        return plies;
    }

    private record PlyKey(UUID gameId, int moveNumber, String color) {}
}
