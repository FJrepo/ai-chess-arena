export type CapturablePiece = 'Q' | 'R' | 'B' | 'N' | 'P';
type PieceSymbol = CapturablePiece | 'K';
type Side = 'WHITE' | 'BLACK';

interface PieceCounts {
  white: Record<PieceSymbol, number>;
  black: Record<PieceSymbol, number>;
}

interface MoveLike {
  fen: string;
  color: Side;
  san: string;
}

export interface CapturedMaterial {
  whiteCaptured: CapturablePiece[];
  blackCaptured: CapturablePiece[];
  whiteCaptureValue: number;
  blackCaptureValue: number;
  materialDelta: number;
}

const STANDARD_START_FEN = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1';
const PIECE_ORDER: CapturablePiece[] = ['Q', 'R', 'B', 'N', 'P'];
const PIECE_VALUES: Record<CapturablePiece, number> = {
  Q: 9,
  R: 5,
  B: 3,
  N: 3,
  P: 1,
};

function createPieceCounter(): Record<PieceSymbol, number> {
  return { K: 0, Q: 0, R: 0, B: 0, N: 0, P: 0 };
}

function createCaptureCounter(): Record<CapturablePiece, number> {
  return { Q: 0, R: 0, B: 0, N: 0, P: 0 };
}

function parseFenCounts(fen: string): PieceCounts | null {
  const board = fen.trim().split(/\s+/)[0];
  const ranks = board.split('/');
  if (ranks.length !== 8) {
    return null;
  }

  const counts: PieceCounts = {
    white: createPieceCounter(),
    black: createPieceCounter(),
  };

  for (const rank of ranks) {
    for (const symbol of rank) {
      if (symbol >= '1' && symbol <= '8') {
        continue;
      }

      const normalized = symbol.toUpperCase() as PieceSymbol;
      if (!(normalized in counts.white)) {
        continue;
      }

      if (symbol === normalized) {
        counts.white[normalized] += 1;
      } else {
        counts.black[normalized] += 1;
      }
    }
  }

  return counts;
}

function removedPieces(
  previous: Record<PieceSymbol, number>,
  next: Record<PieceSymbol, number>,
): Record<PieceSymbol, number> {
  return {
    K: Math.max(0, previous.K - next.K),
    Q: Math.max(0, previous.Q - next.Q),
    R: Math.max(0, previous.R - next.R),
    B: Math.max(0, previous.B - next.B),
    N: Math.max(0, previous.N - next.N),
    P: Math.max(0, previous.P - next.P),
  };
}

function flattenCapturedPieces(counter: Record<CapturablePiece, number>): CapturablePiece[] {
  const pieces: CapturablePiece[] = [];
  for (const piece of PIECE_ORDER) {
    const total = counter[piece];
    for (let i = 0; i < total; i++) {
      pieces.push(piece);
    }
  }
  return pieces;
}

function scoreCapturedPieces(counter: Record<CapturablePiece, number>): number {
  return PIECE_ORDER.reduce((sum, piece) => sum + counter[piece] * PIECE_VALUES[piece], 0);
}

function emptyCapturedMaterial(): CapturedMaterial {
  return {
    whiteCaptured: [],
    blackCaptured: [],
    whiteCaptureValue: 0,
    blackCaptureValue: 0,
    materialDelta: 0,
  };
}

export function deriveCapturedMaterial(
  moves: MoveLike[],
  upToMoveIndex: number,
  startingFen = STANDARD_START_FEN,
): CapturedMaterial {
  if (moves.length === 0 || upToMoveIndex < 0) {
    return emptyCapturedMaterial();
  }

  const startCounts = parseFenCounts(startingFen);
  if (!startCounts) {
    return emptyCapturedMaterial();
  }

  const maxIndex = Math.min(upToMoveIndex, moves.length - 1);
  const currentMove = moves[maxIndex];
  const currentCounts = parseFenCounts(currentMove.fen);

  if (!currentCounts) {
    return emptyCapturedMaterial();
  }

  const capturedByWhite = createCaptureCounter();
  const capturedByBlack = createCaptureCounter();

  // Basic diff from starting position
  const whiteRemoved = removedPieces(startCounts.white, currentCounts.white);
  const blackRemoved = removedPieces(startCounts.black, currentCounts.black);

  // Promotions can create "extra" pieces, which would show as negative removal.
  // We only count pieces that are actually MISSING compared to the start.
  for (const piece of PIECE_ORDER) {
    capturedByWhite[piece] = blackRemoved[piece];
    capturedByBlack[piece] = whiteRemoved[piece];
  }

  const whiteCaptureValue = scoreCapturedPieces(capturedByWhite);
  const blackCaptureValue = scoreCapturedPieces(capturedByBlack);

  return {
    whiteCaptured: flattenCapturedPieces(capturedByWhite),
    blackCaptured: flattenCapturedPieces(capturedByBlack),
    whiteCaptureValue,
    blackCaptureValue,
    materialDelta: whiteCaptureValue - blackCaptureValue,
  };
}
