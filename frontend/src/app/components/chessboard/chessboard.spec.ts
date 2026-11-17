import { ComponentFixture, TestBed } from '@angular/core/testing';
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
    expect(element.querySelector('.square.last-move-to img.piece')?.getAttribute('src')).toBe(
      'pieces/cburnett/wP.svg',
    );

    fixture.componentRef.setInput('lastMove', null);
    fixture.detectChanges();
    expect(element.querySelectorAll('.last-move-from, .last-move-to')).toHaveLength(0);
  });
});

function renderedSquareAt(fixture: ComponentFixture<ChessboardComponent>, coord: string) {
  const index = fixture.componentInstance
    .squares()
    .flat()
    .findIndex((square) => square.coord === coord);
  return (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>('.square')[index];
}

describe('ChessboardComponent piece images', () => {
  const START_FEN = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1';

  function renderBoard() {
    const fixture = TestBed.createComponent(ChessboardComponent);
    fixture.componentRef.setInput('fen', START_FEN);
    fixture.componentRef.setInput('interactiveColor', 'WHITE');
    fixture.detectChanges();
    return fixture;
  }

  it('renders 32 piece images and leaves empty squares empty', () => {
    const fixture = renderBoard();
    const element: HTMLElement = fixture.nativeElement;

    expect(element.querySelectorAll('.square')).toHaveLength(64);
    expect(element.querySelectorAll('img.piece')).toHaveLength(32);
    expect(renderedSquareAt(fixture, 'e4').querySelector('img')).toBeNull();
  });

  it.each([
    ['K', 'e1', 'wK', 'white king'],
    ['Q', 'd1', 'wQ', 'white queen'],
    ['R', 'a1', 'wR', 'white rook'],
    ['B', 'c1', 'wB', 'white bishop'],
    ['N', 'b1', 'wN', 'white knight'],
    ['P', 'e2', 'wP', 'white pawn'],
    ['k', 'e8', 'bK', 'black king'],
    ['q', 'd8', 'bQ', 'black queen'],
    ['r', 'a8', 'bR', 'black rook'],
    ['b', 'c8', 'bB', 'black bishop'],
    ['n', 'b8', 'bN', 'black knight'],
    ['p', 'e7', 'bP', 'black pawn'],
  ])('renders %s on %s with image %s and name %s', (code, coord, image, name) => {
    const fixture = renderBoard();
    expect(squareAt(fixture.componentInstance, coord).pieceCode).toBe(code);

    const piece = renderedSquareAt(fixture, coord).querySelector('img.piece');
    expect(piece?.getAttribute('src')).toBe(`pieces/cburnett/${image}.svg`);
    expect(piece?.getAttribute('alt')).toBe(`${name} on ${coord}`);
    expect(piece?.getAttribute('draggable')).toBe(code === code.toUpperCase() ? 'true' : 'false');
  });

  it('keeps image names and coordinates correct when flipped', () => {
    const fixture = renderBoard();
    const element: HTMLElement = fixture.nativeElement;
    expect(element.querySelector('.square img')?.getAttribute('alt')).toBe('black rook on a8');

    fixture.componentRef.setInput('flipped', true);
    fixture.detectChanges();

    const topLeft = element.querySelector('.square img');
    expect(topLeft?.getAttribute('alt')).toBe('white rook on h1');
    expect(topLeft?.getAttribute('src')).toBe('pieces/cburnett/wR.svg');
    expect(renderedSquareAt(fixture, 'c1').querySelector('img')?.getAttribute('alt')).toBe(
      'white bishop on c1',
    );
    expect(element.querySelector('.rank-label')?.textContent?.trim()).toBe('1');
    expect(element.querySelector('.file-label')?.textContent?.trim()).toBe('h');
  });

  it('selects through an image click and emits a move on a square click', () => {
    const fixture = renderBoard();
    const emit = vi.spyOn(fixture.componentInstance.moveAttempt, 'emit');
    const source = renderedSquareAt(fixture, 'e2');
    source.querySelector('img')!.click();
    fixture.detectChanges();
    expect(source.classList.contains('selected')).toBe(true);

    renderedSquareAt(fixture, 'e4').click();
    fixture.detectChanges();
    expect(emit).toHaveBeenCalledWith({ from: 'e2', to: 'e4', promotionRequired: false });
    expect(source.classList.contains('selected')).toBe(false);
  });

  it('uses the image as the drag ghost and lets drag/drop bubble through target images', () => {
    const fixture = renderBoard();
    const board = fixture.componentInstance;
    const emit = vi.spyOn(board.moveAttempt, 'emit');
    const source = renderedSquareAt(fixture, 'e2').querySelector('img')!;
    const target = renderedSquareAt(fixture, 'd7');
    const dataTransfer = {
      setData: vi.fn(),
      getData: vi.fn(() => 'e2'),
      setDragImage: vi.fn(),
    };
    const start = new Event('dragstart', { bubbles: true, cancelable: true });
    Object.defineProperty(start, 'dataTransfer', { value: dataTransfer });
    source.dispatchEvent(start);
    expect(start.defaultPrevented).toBe(false);
    expect(dataTransfer.setData).toHaveBeenCalledWith('text/plain', 'e2');
    expect(dataTransfer.setDragImage).toHaveBeenCalledWith(source, 20, 20);
    expect(board.dragSource()).toBe('e2');

    const over = new Event('dragover', { bubbles: true, cancelable: true });
    target.querySelector('img')!.dispatchEvent(over);
    fixture.detectChanges();
    expect(over.defaultPrevented).toBe(true);
    expect(target.classList.contains('drop-target')).toBe(true);

    const drop = new Event('drop', { bubbles: true, cancelable: true });
    Object.defineProperty(drop, 'dataTransfer', { value: dataTransfer });
    target.querySelector('img')!.dispatchEvent(drop);
    fixture.detectChanges();
    expect(drop.defaultPrevented).toBe(true);
    expect(emit).toHaveBeenCalledWith({ from: 'e2', to: 'd7', promotionRequired: false });
    expect(board.dragSource()).toBeNull();
    expect(target.classList.contains('drop-target')).toBe(false);
  });

  it('clears drag state when an image drag ends without a drop', () => {
    const fixture = renderBoard();
    const source = renderedSquareAt(fixture, 'e2').querySelector('img')!;
    source.dispatchEvent(new Event('dragstart', { bubbles: true }));
    renderedSquareAt(fixture, 'e4').dispatchEvent(
      new Event('dragover', { bubbles: true, cancelable: true }),
    );
    source.dispatchEvent(new Event('dragend', { bubbles: true }));
    fixture.detectChanges();

    expect(fixture.componentInstance.dragSource()).toBeNull();
    expect(fixture.componentInstance.dropTarget()).toBeNull();
    expect((fixture.nativeElement as HTMLElement).querySelector('.drop-target')).toBeNull();
  });
});
