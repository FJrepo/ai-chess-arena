import { Game, Move } from '../models/tournament.model';

export type Side = 'WHITE' | 'BLACK';

/** Engine evaluation of a shown position, always from White's point of view. */
export interface PositionEvaluation {
  /** Centipawns, positive when White is better; null when unknown or when a mate score applies. */
  cp: number | null;
  /** Moves until mate, positive when White mates; null when there is no forced mate or it is unknown. */
  mate: number | null;
  /** The side that is checkmated in the shown position, if the game ended there by checkmate. */
  checkmated: Side | null;
}

/** No stored evaluation for the shown position (not evaluated yet, or Stockfish was unavailable). */
export const NO_EVALUATION: PositionEvaluation = { cp: null, mate: null, checkmated: null };

/** Result reasons whose final position is a dead draw on the board itself (Stockfish scores it 0). */
const DEAD_DRAW_REASONS = new Set(['STALEMATE', 'INSUFFICIENT_MATERIAL']);

/**
 * The evaluation to show for the position after `moves[index]`.
 *
 * Only the stored evaluation of that move counts: a move without one is shown as unknown rather than
 * as 0.0 or as an older move's score. The start position (index -1) has no stored evaluation either.
 * The final position of a game that ended on the board is known without an engine: checkmate is a win
 * for the side that delivered it, and stalemate or insufficient material is a draw (0.0), even if a
 * different score was stored for it.
 */
export function evaluationAt(
  moves: readonly Move[],
  index: number,
  game: Pick<Game, 'status' | 'resultReason'> | null,
): PositionEvaluation {
  const move = index >= 0 ? moves[index] : undefined;
  if (!move) {
    return NO_EVALUATION;
  }
  const checkmated: Side = move.color === 'WHITE' ? 'BLACK' : 'WHITE';
  const isFinalPosition = index === moves.length - 1 && game?.status === 'COMPLETED';

  // The result of a game that ended on the board decides its final position, whatever was stored.
  if (isFinalPosition && game?.resultReason === 'CHECKMATE') {
    return { cp: null, mate: 0, checkmated };
  }
  if (isFinalPosition && game?.resultReason && DEAD_DRAW_REASONS.has(game.resultReason)) {
    return { cp: 0, mate: null, checkmated: null };
  }
  // Stockfish reports a checkmated position as "mate 0", which carries no sign.
  if (move.evaluationMate === 0) {
    return { cp: null, mate: 0, checkmated };
  }
  if (move.evaluationMate != null) {
    return { cp: null, mate: move.evaluationMate, checkmated: null };
  }
  if (move.evaluationCp != null) {
    return { cp: move.evaluationCp, mate: null, checkmated: null };
  }
  return NO_EVALUATION;
}

/** Whether there is an evaluation to show. */
export function hasEvaluation(evaluation: PositionEvaluation): boolean {
  return evaluation.checkmated != null || evaluation.mate != null || evaluation.cp != null;
}

/** Short score for the evaluation bar: "+0.4", "M3", "-M2", "1-0" / "0-1" after checkmate, "–" if unknown. */
export function evaluationLabel(evaluation: PositionEvaluation): string {
  if (evaluation.checkmated) {
    return evaluation.checkmated === 'BLACK' ? '1-0' : '0-1';
  }
  if (evaluation.mate != null) {
    return evaluation.mate > 0 ? `M${evaluation.mate}` : `-M${Math.abs(evaluation.mate)}`;
  }
  if (evaluation.cp != null) {
    const pawns = evaluation.cp / 100;
    return pawns > 0 ? `+${pawns.toFixed(1)}` : pawns.toFixed(1);
  }
  return '–';
}

/**
 * Spoken description for the states whose short label needs explaining: an unknown evaluation and a
 * delivered checkmate. Null for regular scores, whose label speaks for itself.
 */
export function evaluationDescription(evaluation: PositionEvaluation): string | null {
  if (evaluation.checkmated) {
    return evaluation.checkmated === 'BLACK'
      ? 'White wins by checkmate'
      : 'Black wins by checkmate';
  }
  return hasEvaluation(evaluation) ? null : 'Evaluation unavailable';
}

/** Accessible name of the evaluation bar: the description, or the spoken score. */
export function evaluationAccessibleLabel(evaluation: PositionEvaluation): string {
  const description = evaluationDescription(evaluation);
  if (description) {
    return description;
  }
  if (evaluation.mate != null) {
    const side = evaluation.mate > 0 ? 'White' : 'Black';
    return `Evaluation: ${side} mates in ${Math.abs(evaluation.mate)}`;
  }
  return `Evaluation: ${evaluationLabel(evaluation)}`;
}

/** White's share of the evaluation bar in percent; 50 when the evaluation is unknown. */
export function whiteBarShare(evaluation: PositionEvaluation): number {
  if (evaluation.checkmated) {
    return evaluation.checkmated === 'BLACK' ? 100 : 0;
  }
  if (evaluation.mate != null) {
    return evaluation.mate > 0 ? 100 : 0;
  }
  if (evaluation.cp == null) {
    return 50;
  }
  const pawns = evaluation.cp / 100;
  const percentage = 50 + 50 * (Math.atan(pawns / 1.5) / (Math.PI / 2));
  return Math.max(5, Math.min(95, percentage));
}
