import { Move } from '../models/tournament.model';

/**
 * Returns the moves in play order. White and Black share a move number, so the color decides within a number
 * (White first), then the creation time. The backend already orders them this way; this guards the board and
 * the next-ply logic against any other order.
 */
export function sortMovesInPlayOrder(moves: readonly Move[]): Move[] {
  return [...moves].sort(
    (a, b) =>
      a.moveNumber - b.moveNumber ||
      colorRank(a.color) - colorRank(b.color) ||
      (a.createdAt ?? '').localeCompare(b.createdAt ?? ''),
  );
}

function colorRank(color: Move['color']): number {
  return color === 'WHITE' ? 0 : 1;
}
