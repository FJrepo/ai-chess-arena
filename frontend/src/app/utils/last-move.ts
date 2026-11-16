/** The from/to squares of a move in algebraic coordinates, e.g. { from: 'e2', to: 'e4' }. */
export interface LastMove {
  from: string;
  to: string;
}

type Placement = (string | null)[];

const FILES = 'abcdefgh';
const PIECES = 'pnbrqkPNBRQK';

/**
 * Derives the squares of the move that leads from `previousFen` to `currentFen` by diffing their piece
 * placement, without a chess engine. Exactly one side may have moved:
 * - a regular move, capture or promotion changes one square the mover left and one it arrived on;
 * - en passant also empties the captured pawn's square, which belongs to the opponent and is ignored;
 * - castling moves king and rook; the king's squares are returned (e1 to g1), as in UCI notation.
 *
 * Returns null when the placements are malformed, identical, or not one move apart.
 */
export function lastMoveSquares(previousFen: string, currentFen: string): LastMove | null {
  const before = parsePlacement(previousFen);
  const after = parsePlacement(currentFen);
  if (!before || !after) {
    return null;
  }

  const white = diffForSide(before, after, 'white');
  const black = diffForSide(before, after, 'black');
  const moved = [white, black].filter((diff) => diff.arrived.length > 0);
  if (moved.length !== 1) {
    return null;
  }
  const { left, arrived } = moved[0];

  if (left.length === 1 && arrived.length === 1) {
    return { from: toCoord(left[0]), to: toCoord(arrived[0]) };
  }
  if (left.length === 2 && arrived.length === 2) {
    const kingFrom = left.find((i) => isKing(before[i]));
    const kingTo = arrived.find((i) => isKing(after[i]));
    const rookFrom = left.find((i) => isRook(before[i]));
    const rookTo = arrived.find((i) => isRook(after[i]));
    if (kingFrom != null && kingTo != null && rookFrom != null && rookTo != null) {
      return { from: toCoord(kingFrom), to: toCoord(kingTo) };
    }
  }
  return null;
}

/** Squares one side's pieces left and arrived on (indices into the placement). */
function diffForSide(
  before: Placement,
  after: Placement,
  side: 'white' | 'black',
): { left: number[]; arrived: number[] } {
  const left: number[] = [];
  const arrived: number[] = [];
  for (let i = 0; i < 64; i++) {
    if (before[i] === after[i]) {
      continue;
    }
    if (sideOf(before[i]) === side) {
      left.push(i);
    }
    if (sideOf(after[i]) === side) {
      arrived.push(i);
    }
  }
  return { left, arrived };
}

/** The 64 squares of a FEN's piece placement, a8 first and h1 last; null if the placement is malformed. */
function parsePlacement(fen: string): Placement | null {
  const ranks = (fen ?? '').trim().split(/\s+/)[0].split('/');
  if (ranks.length !== 8) {
    return null;
  }
  const squares: Placement = [];
  for (const rank of ranks) {
    let width = 0;
    for (const ch of rank) {
      if (ch >= '1' && ch <= '8') {
        const empty = Number(ch);
        width += empty;
        for (let i = 0; i < empty; i++) {
          squares.push(null);
        }
      } else if (PIECES.includes(ch)) {
        width += 1;
        squares.push(ch);
      } else {
        return null;
      }
    }
    if (width !== 8) {
      return null;
    }
  }
  return squares;
}

function sideOf(piece: string | null): 'white' | 'black' | null {
  if (!piece) {
    return null;
  }
  return piece === piece.toUpperCase() ? 'white' : 'black';
}

function isKing(piece: string | null): boolean {
  return piece === 'K' || piece === 'k';
}

function isRook(piece: string | null): boolean {
  return piece === 'R' || piece === 'r';
}

function toCoord(index: number): string {
  return `${FILES[index % 8]}${8 - Math.floor(index / 8)}`;
}
