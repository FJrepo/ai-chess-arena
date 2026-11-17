export type PieceColor = 'white' | 'black';
export type PieceType = 'K' | 'Q' | 'R' | 'B' | 'N' | 'P';

const PIECE_NAMES: Record<PieceType, string> = {
  K: 'king',
  Q: 'queen',
  R: 'rook',
  B: 'bishop',
  N: 'knight',
  P: 'pawn',
};

export function pieceImage(color: PieceColor, type: PieceType): string {
  return `pieces/cburnett/${color === 'white' ? 'w' : 'b'}${type}.svg`;
}

export function pieceName(color: PieceColor, type: PieceType): string {
  return `${color} ${PIECE_NAMES[type]}`;
}
