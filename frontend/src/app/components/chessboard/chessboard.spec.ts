import { TestBed } from '@angular/core/testing';
import { ChessboardComponent } from './chessboard';

function squareAt(board: ChessboardComponent, coord: string) {
  const square = board
    .squares()
    .flat()
    .find((candidate) => candidate.coord === coord);
  if (!square) {
    throw new Error(`no square ${coord}`);
  }
  return square;
}

describe('ChessboardComponent last-move highlight', () => {
  it('marks only the from and to squares of the last move', () => {
    const board = new ChessboardComponent();
    board.fen = 'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1';
    board.lastMove = { from: 'e2', to: 'e4' };
    board.ngOnChanges();

    const marked = board
      .squares()
      .flat()
      .filter((square) => board.isLastMoveFrom(square) || board.isLastMoveTo(square))
      .map((square) => square.coord);

    expect(marked.sort()).toEqual(['e2', 'e4']);
    expect(board.isLastMoveFrom(squareAt(board, 'e2'))).toBe(true);
    expect(board.isLastMoveTo(squareAt(board, 'e2'))).toBe(false);
    expect(board.isLastMoveTo(squareAt(board, 'e4'))).toBe(true);
    expect(board.isLastMoveFrom(squareAt(board, 'e4'))).toBe(false);
  });

  it('keeps the highlight on the same squares when the board is flipped', () => {
    const board = new ChessboardComponent();
    board.lastMove = { from: 'g1', to: 'f3' };
    board.flipped = true;
    board.ngOnChanges();

    expect(board.isLastMoveFrom(squareAt(board, 'g1'))).toBe(true);
    expect(board.isLastMoveTo(squareAt(board, 'f3'))).toBe(true);
  });

  it('marks nothing without a last move', () => {
    const board = new ChessboardComponent();
    board.ngOnChanges();

    const marked = board
      .squares()
      .flat()
      .filter((square) => board.isLastMoveFrom(square) || board.isLastMoveTo(square));

    expect(marked).toEqual([]);
  });

  it('renders the last-move classes on the from and to squares', () => {
    const fixture = TestBed.createComponent(ChessboardComponent);
    fixture.componentRef.setInput(
      'fen',
      'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1',
    );
    fixture.componentRef.setInput('lastMove', { from: 'e2', to: 'e4' });
    fixture.detectChanges();
    const element: HTMLElement = fixture.nativeElement;

    expect(element.querySelectorAll('.square.last-move-from')).toHaveLength(1);
    expect(element.querySelectorAll('.square.last-move-to')).toHaveLength(1);
    expect(element.querySelector('.square.last-move-to .piece')?.textContent?.trim()).toBe(
      '\u265F',
    );

    fixture.componentRef.setInput('lastMove', null);
    fixture.detectChanges();
    expect(element.querySelectorAll('.last-move-from, .last-move-to')).toHaveLength(0);
  });
});
