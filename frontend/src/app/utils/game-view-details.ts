import type { Move } from '../models/tournament.model';
import { isEnPassantCapture, lastMoveSquares } from './last-move';

type DetailMove = Pick<
  Move,
  | 'modelId'
  | 'isOverride'
  | 'isBook'
  | 'isInherited'
  | 'isCanonical'
  | 'moveNumber'
  | 'color'
  | 'san'
  | 'fen'
>;

function isModelMove(move: DetailMove): boolean {
  return (
    !!move.modelId &&
    !move.isOverride &&
    !move.isBook &&
    !move.isInherited &&
    move.isCanonical !== false
  );
}

export function isBongcloudMove(previousFen: string, move: DetailMove): boolean {
  if (!isModelMove(move) || move.moveNumber < 1 || move.moveNumber > 3) return false;
  const white = move.color === 'WHITE';
  const target = white ? 'e2' : 'e7';
  if (move.san.replace(/[+#]$/, '') !== `K${target}`) return false;

  const squares = lastMoveSquares(previousFen, move.fen);
  return squares?.from === (white ? 'e1' : 'e8') && squares.to === target;
}

export function liveMoveDetail(previousFen: string, move: DetailMove): string | null {
  if (!isModelMove(move)) return null;
  if (/^[a-h]x[a-h][36][+#]?$/.test(move.san) && isEnPassantCapture(previousFen, move.fen)) {
    return 'Google en passant. Holy hell!';
  }
  return isBongcloudMove(previousFen, move) ? 'Bongcloud detected. Bold.' : null;
}
