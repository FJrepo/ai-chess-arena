/** The from/to squares of a move in algebraic coordinates, e.g. { from: 'e2', to: 'e4' }. */
export interface LastMove {
  from: string;
  to: string;
}

type Side = 'white' | 'black';
type Placement = (string | null)[];

interface ParsedFen {
  squares: Placement;
  /** Side to move, when the FEN has that field. */
  sideToMove: Side | null;
  /** En-passant target square, when the FEN has that field and it is not '-'. */
  enPassant: string | null;
  /** Fullmove number, when the FEN has that field. */
  fullmove: number | null;
}

const FILES = 'abcdefgh';
const PIECES = 'pnbrqkPNBRQK';

/**
 * Derives the squares of the move that leads from `previousFen` to `currentFen` by diffing the two
 * positions, without a full chess engine. The whole transition must be a single move:
 * - exactly one side moved; the other lost at most one piece (not its king), on the target square,
 *   or, for en passant, the pawn beside the target square;
 * - the moving piece moves the way it can (sliding pieces along a clear line), and is unchanged
 *   unless a pawn promotes on the last rank;
 * - castling moves king and rook between their home and castled squares; the king's squares are
 *   returned (e1 to g1), as in UCI notation;
 * - where both FENs carry them, side to move flips, the fullmove number advances after Black's move,
 *   and an en-passant capture lands on the previous FEN's en-passant square.
 *
 * Returns null when the positions are malformed, identical, or not exactly one move apart.
 */
export function lastMoveSquares(previousFen: string, currentFen: string): LastMove | null {
  const before = parseFen(previousFen);
  const after = parseFen(currentFen);
  if (!before || !after) {
    return null;
  }

  const white = diffForSide(before.squares, after.squares, 'white');
  const black = diffForSide(before.squares, after.squares, 'black');
  const movers = [white, black].filter((diff) => diff.arrived.length > 0);
  if (movers.length !== 1) {
    return null;
  }
  const mover = movers[0];
  const opponent = mover === white ? black : white;
  if (!sideFieldsAgree(before, after, mover.side)) {
    return null;
  }

  if (mover.left.length === 2 && mover.arrived.length === 2) {
    return opponent.left.length === 0 ? castling(before.squares, after.squares, mover) : null;
  }
  if (mover.left.length !== 1 || mover.arrived.length !== 1) {
    return null;
  }

  const from = mover.left[0];
  const to = mover.arrived[0];
  const piece = before.squares[from]!;
  const target = before.squares[to];
  if (!pieceArrivesAs(piece, after.squares[to]!, to)) {
    return null;
  }

  // At most one opponent piece is lost: on the target square, or the pawn taken en passant.
  if (opponent.left.length === 0) {
    if (target !== null || !canMove(before.squares, piece, from, to, false)) {
      return null;
    }
  } else if (opponent.left.length === 1 && opponent.left[0] === to) {
    if (isKing(target) || !canMove(before.squares, piece, from, to, true)) {
      return null;
    }
  } else if (opponent.left.length === 1) {
    if (!isEnPassant(before, after.squares, piece, from, to, opponent.left[0])) {
      return null;
    }
  } else {
    return null;
  }
  return { from: toCoord(from), to: toCoord(to) };
}

interface SideDiff {
  side: Side;
  /** Squares this side's pieces left (or were captured on). */
  left: number[];
  /** Squares this side's pieces arrived on. */
  arrived: number[];
}

/** A validated pawn move that captures on an empty destination square. */
export function isEnPassantCapture(previousFen: string, currentFen: string): boolean {
  const move = lastMoveSquares(previousFen, currentFen);
  if (!move || move.from[0] === move.to[0]) return false;
  const before = parseFen(previousFen);
  if (!before || before.enPassant !== move.to) return false;
  const from = indexOf(FILES.indexOf(move.from[0]), Number(move.from[1]));
  const to = indexOf(FILES.indexOf(move.to[0]), Number(move.to[1]));
  return isPawn(before.squares[from]) && before.squares[to] === null;
}

function diffForSide(before: Placement, after: Placement, side: Side): SideDiff {
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
  return { side, left, arrived };
}

/** Side to move and fullmove number, where both FENs have them, must match one move by `mover`. */
function sideFieldsAgree(before: ParsedFen, after: ParsedFen, mover: Side): boolean {
  if (before.sideToMove !== null && before.sideToMove !== mover) {
    return false;
  }
  if (after.sideToMove !== null && after.sideToMove === mover) {
    return false;
  }
  if (before.fullmove !== null && after.fullmove !== null) {
    const expected = mover === 'black' ? before.fullmove + 1 : before.fullmove;
    if (after.fullmove !== expected) {
      return false;
    }
  }
  return true;
}

/** The piece on the target square is the moved piece, or what a pawn promoted to on the last rank. */
function pieceArrivesAs(piece: string, arrived: string, to: number): boolean {
  if (arrived === piece) {
    return true;
  }
  const lastRank = sideOf(piece) === 'white' ? 8 : 1;
  return isPawn(piece) && 'nbrqNBRQ'.includes(arrived) && rankOf(to) === lastRank;
}

/** Whether `piece` can go from `from` to `to` in `squares`; pawns move straight and capture diagonally. */
function canMove(
  squares: Placement,
  piece: string,
  from: number,
  to: number,
  capture: boolean,
): boolean {
  const df = fileOf(to) - fileOf(from);
  const dr = rankOf(to) - rankOf(from);
  switch (piece.toLowerCase()) {
    case 'p': {
      const forward = sideOf(piece) === 'white' ? 1 : -1;
      if (capture) {
        return Math.abs(df) === 1 && dr === forward;
      }
      if (df !== 0) {
        return false;
      }
      const homeRank = sideOf(piece) === 'white' ? 2 : 7;
      return (
        dr === forward ||
        (dr === 2 * forward && rankOf(from) === homeRank && squares[from - 8 * forward] === null)
      );
    }
    case 'n':
      return (
        (Math.abs(df) === 1 && Math.abs(dr) === 2) || (Math.abs(df) === 2 && Math.abs(dr) === 1)
      );
    case 'k':
      return Math.max(Math.abs(df), Math.abs(dr)) === 1;
    case 'b':
      return Math.abs(df) === Math.abs(dr) && pathIsClear(squares, from, to);
    case 'r':
      return (df === 0 || dr === 0) && pathIsClear(squares, from, to);
    case 'q':
      return (
        (df === 0 || dr === 0 || Math.abs(df) === Math.abs(dr)) && pathIsClear(squares, from, to)
      );
    default:
      return false;
  }
}

/** Every square strictly between `from` and `to` (on a line) is empty. */
function pathIsClear(squares: Placement, from: number, to: number): boolean {
  const stepFile = Math.sign(fileOf(to) - fileOf(from));
  const stepRank = Math.sign(rankOf(to) - rankOf(from));
  let file = fileOf(from) + stepFile;
  let rank = rankOf(from) + stepRank;
  while (file !== fileOf(to) || rank !== rankOf(to)) {
    if (squares[indexOf(file, rank)] !== null) {
      return false;
    }
    file += stepFile;
    rank += stepRank;
  }
  return true;
}

/** A pawn captures diagonally onto the empty en-passant square and removes the pawn beside it. */
function isEnPassant(
  before: ParsedFen,
  after: Placement,
  piece: string,
  from: number,
  to: number,
  lostSquare: number,
): boolean {
  const capturedRank = sideOf(piece) === 'white' ? 5 : 4;
  return (
    isPawn(piece) &&
    rankOf(from) === capturedRank &&
    before.squares[to] === null &&
    canMove(before.squares, piece, from, to, true) &&
    lostSquare === indexOf(fileOf(to), rankOf(from)) &&
    isPawn(before.squares[lostSquare]) &&
    after[lostSquare] === null &&
    (before.enPassant === null || before.enPassant === toCoord(to))
  );
}

/** King and rook went from their home squares to the castled squares on one wing. */
function castling(before: Placement, after: Placement, mover: SideDiff): LastMove | null {
  const homeRank = mover.side === 'white' ? 1 : 8;
  const king = mover.side === 'white' ? 'K' : 'k';
  const rook = mover.side === 'white' ? 'R' : 'r';
  const wings = [
    { rookFrom: 'h', kingTo: 'g', rookTo: 'f' },
    { rookFrom: 'a', kingTo: 'c', rookTo: 'd' },
  ];
  for (const wing of wings) {
    const kingFrom = indexOf(4, homeRank);
    const kingTo = indexOf(FILES.indexOf(wing.kingTo), homeRank);
    const rookFrom = indexOf(FILES.indexOf(wing.rookFrom), homeRank);
    const rookTo = indexOf(FILES.indexOf(wing.rookTo), homeRank);
    const matches =
      sameSquares(mover.left, [kingFrom, rookFrom]) &&
      sameSquares(mover.arrived, [kingTo, rookTo]) &&
      before[kingFrom] === king &&
      before[rookFrom] === rook &&
      after[kingTo] === king &&
      after[rookTo] === rook &&
      before[kingTo] === null &&
      before[rookTo] === null &&
      pathIsClear(before, kingFrom, rookFrom);
    if (matches) {
      return { from: toCoord(kingFrom), to: toCoord(kingTo) };
    }
  }
  return null;
}

function sameSquares(actual: number[], expected: number[]): boolean {
  return actual.length === expected.length && expected.every((square) => actual.includes(square));
}

/** Piece placement and the optional side, en-passant and fullmove fields; null if malformed. */
function parseFen(fen: string): ParsedFen | null {
  const fields = (fen ?? '').trim().split(/\s+/);
  const squares = parsePlacement(fields[0]);
  if (!squares) {
    return null;
  }
  const side = fields[1];
  const enPassant = fields[3];
  const fullmove = fields[5] !== undefined ? Number(fields[5]) : NaN;
  return {
    squares,
    sideToMove: side === 'w' ? 'white' : side === 'b' ? 'black' : null,
    enPassant: enPassant && /^[a-h][36]$/.test(enPassant) ? enPassant : null,
    fullmove: Number.isInteger(fullmove) && fullmove > 0 ? fullmove : null,
  };
}

/** The 64 squares of a FEN's piece placement, a8 first and h1 last; null if the placement is malformed. */
function parsePlacement(placement: string): Placement | null {
  const ranks = placement.split('/');
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

function sideOf(piece: string | null): Side | null {
  if (!piece) {
    return null;
  }
  return piece === piece.toUpperCase() ? 'white' : 'black';
}

function isKing(piece: string | null): boolean {
  return piece === 'K' || piece === 'k';
}

function isPawn(piece: string | null): boolean {
  return piece === 'P' || piece === 'p';
}

/** File 0 (a) to 7 (h). */
function fileOf(index: number): number {
  return index % 8;
}

/** Rank 1 to 8. */
function rankOf(index: number): number {
  return 8 - Math.floor(index / 8);
}

function indexOf(file: number, rank: number): number {
  return (8 - rank) * 8 + file;
}

function toCoord(index: number): string {
  return `${FILES[fileOf(index)]}${rankOf(index)}`;
}
