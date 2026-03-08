import { Component, EventEmitter, Input, OnChanges, Output, signal } from '@angular/core';

const PIECE_UNICODE: Record<string, string> = {
  K: '\u2654',
  Q: '\u2655',
  R: '\u2656',
  B: '\u2657',
  N: '\u2658',
  P: '\u2659',
  k: '\u265A',
  q: '\u265B',
  r: '\u265C',
  b: '\u265D',
  n: '\u265E',
  p: '\u265F',
};

interface Square {
  file: number;
  rank: number;
  coord: string;
  pieceCode: string | null;
  piece: string;
  pieceColor: 'white' | 'black' | null;
  isLight: boolean;
}

export interface BoardMoveAttempt {
  from: string;
  to: string;
  promotionRequired: boolean;
}

@Component({
  selector: 'app-chessboard',
  imports: [],
  templateUrl: './chessboard.html',
  styleUrl: './chessboard.scss',
})
export class ChessboardComponent implements OnChanges {
  @Input() fen = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1';
  @Input() flipped = false;
  @Input() interactiveColor: 'WHITE' | 'BLACK' | null = null;
  @Output() moveAttempt = new EventEmitter<BoardMoveAttempt>();

  squares = signal<Square[][]>([]);
  fileLabels = signal<string[]>([]);
  rankLabels = signal<string[]>([]);
  selectedSquare = signal<string | null>(null);
  dragSource = signal<string | null>(null);
  dropTarget = signal<string | null>(null);

  ngOnChanges() {
    this.updateBoard();
  }

  updateBoard() {
    const placement = this.fen.split(' ')[0];
    const rows = placement.split('/');
    const board: Square[][] = [];

    for (let rank = 0; rank < 8; rank++) {
      const row: Square[] = [];
      let file = 0;
      for (const ch of rows[rank]) {
        if (ch >= '1' && ch <= '8') {
          for (let i = 0; i < parseInt(ch); i++) {
            row.push({
              file,
              rank: 7 - rank,
              coord: this.toCoord(file, 7 - rank),
              pieceCode: null,
              piece: '',
              pieceColor: null,
              isLight: (file + (7 - rank)) % 2 !== 0,
            });
            file++;
          }
        } else {
          const pieceColor = ch === ch.toUpperCase() ? 'white' : 'black';
          row.push({
            file,
            rank: 7 - rank,
            coord: this.toCoord(file, 7 - rank),
            pieceCode: ch,
            piece: PIECE_UNICODE[ch] || '',
            pieceColor,
            isLight: (file + (7 - rank)) % 2 !== 0,
          });
          file++;
        }
      }
      board.push(row);
    }

    if (this.flipped) {
      board.reverse();
      board.forEach((row) => row.reverse());
      this.fileLabels.set(['h', 'g', 'f', 'e', 'd', 'c', 'b', 'a']);
      this.rankLabels.set(['1', '2', '3', '4', '5', '6', '7', '8']);
    } else {
      this.fileLabels.set(['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h']);
      this.rankLabels.set(['8', '7', '6', '5', '4', '3', '2', '1']);
    }

    this.squares.set(board);
  }

  canDrag(square: Square): boolean {
    return this.isControllablePiece(square);
  }

  selectSquare(square: Square): void {
    if (!this.isControllablePiece(square)) {
      if (this.selectedSquare() && square.coord !== this.selectedSquare()) {
        this.tryMove(this.selectedSquare()!, square);
      }
      return;
    }

    this.selectedSquare.set(this.selectedSquare() === square.coord ? null : square.coord);
  }

  onDragStart(event: DragEvent, square: Square): void {
    if (!this.isControllablePiece(square)) {
      event.preventDefault();
      return;
    }
    this.dragSource.set(square.coord);
    this.selectedSquare.set(square.coord);
    event.dataTransfer?.setData('text/plain', square.coord);
    event.dataTransfer?.setDragImage((event.target as HTMLElement) ?? new Image(), 20, 20);
  }

  onDragEnd(): void {
    this.dragSource.set(null);
    this.dropTarget.set(null);
  }

  onDragOver(event: DragEvent, square: Square): void {
    if (!this.dragSource()) {
      return;
    }
    event.preventDefault();
    this.dropTarget.set(square.coord);
  }

  onDragLeave(square: Square): void {
    if (this.dropTarget() === square.coord) {
      this.dropTarget.set(null);
    }
  }

  onDrop(event: DragEvent, square: Square): void {
    event.preventDefault();
    const from = event.dataTransfer?.getData('text/plain') || this.dragSource();
    this.dragSource.set(null);
    this.dropTarget.set(null);
    if (!from) {
      return;
    }
    this.tryMove(from, square);
  }

  isSelected(square: Square): boolean {
    return this.selectedSquare() === square.coord;
  }

  isDropTarget(square: Square): boolean {
    return this.dropTarget() === square.coord;
  }

  private tryMove(from: string, targetSquare: Square): void {
    if (from === targetSquare.coord) {
      this.selectedSquare.set(null);
      return;
    }

    const sourceSquare = this.findSquare(from);
    if (!sourceSquare) {
      this.selectedSquare.set(null);
      return;
    }

    this.selectedSquare.set(null);
    this.moveAttempt.emit({
      from,
      to: targetSquare.coord,
      promotionRequired: this.requiresPromotion(sourceSquare, targetSquare.coord),
    });
  }

  private requiresPromotion(square: Square, destination: string): boolean {
    const pieceCode = square.pieceCode;
    if (pieceCode !== 'P' && pieceCode !== 'p') {
      return false;
    }
    const targetRank = Number.parseInt(destination[1], 10);
    return targetRank === 1 || targetRank === 8;
  }

  private isControllablePiece(square: Square): boolean {
    if (!square.pieceCode || !this.interactiveColor) {
      return false;
    }
    return (
      (this.interactiveColor === 'WHITE' && square.pieceColor === 'white') ||
      (this.interactiveColor === 'BLACK' && square.pieceColor === 'black')
    );
  }

  private findSquare(coord: string): Square | null {
    for (const row of this.squares()) {
      const found = row.find((square) => square.coord === coord);
      if (found) {
        return found;
      }
    }
    return null;
  }

  private toCoord(file: number, rank: number): string {
    return `${String.fromCharCode(97 + file)}${rank + 1}`;
  }
}
