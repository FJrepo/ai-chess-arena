import { Component, Input, signal, OnChanges } from '@angular/core';

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
  piece: string;
  pieceColor: 'white' | 'black' | null;
  isLight: boolean;
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

  squares = signal<Square[][]>([]);
  fileLabels = signal<string[]>([]);
  rankLabels = signal<string[]>([]);

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
}
