import { lastMoveSquares } from './last-move';

const START_FEN = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1';
const AFTER_E4 = 'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1';
const AFTER_E5 = 'rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2';

describe('lastMoveSquares', () => {
  it('finds a white and a black pawn move', () => {
    expect(lastMoveSquares(START_FEN, AFTER_E4)).toEqual({ from: 'e2', to: 'e4' });
    expect(lastMoveSquares(AFTER_E4, AFTER_E5)).toEqual({ from: 'e7', to: 'e5' });
  });

  it('finds a piece move', () => {
    const afterNf3 = 'rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2';
    expect(lastMoveSquares(AFTER_E5, afterNf3)).toEqual({ from: 'g1', to: 'f3' });
  });

  it('finds a capture', () => {
    const beforeCapture = 'rnbqkbnr/ppp1pppp/8/3p4/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2';
    const afterExd5 = 'rnbqkbnr/ppp1pppp/8/3P4/8/8/PPPP1PPP/RNBQKBNR b KQkq - 0 2';
    expect(lastMoveSquares(beforeCapture, afterExd5)).toEqual({ from: 'e4', to: 'd5' });
  });

  it('returns the king squares for castling on either side, for both colors', () => {
    const whiteToCastle = 'r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1';
    const blackToCastle = 'r3k2r/8/8/8/8/8/8/R3K2R b KQkq - 0 1';

    expect(lastMoveSquares(whiteToCastle, 'r3k2r/8/8/8/8/8/8/R4RK1 b kq - 1 1')).toEqual({
      from: 'e1',
      to: 'g1',
    });
    expect(lastMoveSquares(whiteToCastle, 'r3k2r/8/8/8/8/8/8/2KR3R b kq - 1 1')).toEqual({
      from: 'e1',
      to: 'c1',
    });
    expect(lastMoveSquares(blackToCastle, 'r4rk1/8/8/8/8/8/8/R3K2R w KQ - 1 2')).toEqual({
      from: 'e8',
      to: 'g8',
    });
    expect(lastMoveSquares(blackToCastle, '2kr3r/8/8/8/8/8/8/R3K2R w KQ - 1 2')).toEqual({
      from: 'e8',
      to: 'c8',
    });
  });

  it('ignores the captured pawn square of an en passant capture', () => {
    const whiteEp = 'rnbqkbnr/ppp1p1pp/8/3pPp2/8/8/PPPP1PPP/RNBQKBNR w KQkq f6 0 3';
    const afterExf6 = 'rnbqkbnr/ppp1p1pp/5P2/3p4/8/8/PPPP1PPP/RNBQKBNR b KQkq - 0 3';
    expect(lastMoveSquares(whiteEp, afterExf6)).toEqual({ from: 'e5', to: 'f6' });

    const blackEp = 'rnbqkbnr/pppp1ppp/8/8/3Pp3/8/PPP1PPPP/RNBQKBNR b KQkq d3 0 3';
    const afterExd3 = 'rnbqkbnr/pppp1ppp/8/8/8/3p4/PPP1PPPP/RNBQKBNR w KQkq - 0 4';
    expect(lastMoveSquares(blackEp, afterExd3)).toEqual({ from: 'e4', to: 'd3' });
  });

  it('finds promotions, with and without a capture, for both colors', () => {
    expect(lastMoveSquares('8/P7/8/8/8/8/8/K6k w - - 0 1', 'Q7/8/8/8/8/8/8/K6k b - - 0 1')).toEqual(
      { from: 'a7', to: 'a8' },
    );
    expect(
      lastMoveSquares('1r6/P7/8/8/8/8/8/K6k w - - 0 1', '1N6/8/8/8/8/8/8/K6k b - - 0 1'),
    ).toEqual({ from: 'a7', to: 'b8' });
    expect(lastMoveSquares('k7/8/8/8/8/8/p7/7K b - - 0 1', 'k7/8/8/8/8/8/8/q6K w - - 0 2')).toEqual(
      { from: 'a2', to: 'a1' },
    );
  });

  it('accepts a bare piece placement without the other FEN fields', () => {
    expect(
      lastMoveSquares(
        'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR',
        'rnbqkbnr/pppppppp/8/8/3P4/8/PPP1PPPP/RNBQKBNR',
      ),
    ).toEqual({ from: 'd2', to: 'd4' });
  });

  it('returns null when the positions are not exactly one move apart', () => {
    expect(lastMoveSquares(START_FEN, START_FEN)).toBeNull();
    // Two plies: both sides moved.
    expect(lastMoveSquares(START_FEN, AFTER_E5)).toBeNull();
    // Two moves by the same side.
    expect(
      lastMoveSquares(START_FEN, 'rnbqkbnr/pppppppp/8/8/3PP3/8/PPP2PPP/RNBQKBNR b KQkq - 0 1'),
    ).toBeNull();
  });

  it('returns null for malformed placements', () => {
    expect(lastMoveSquares('', AFTER_E4)).toBeNull();
    expect(
      lastMoveSquares(START_FEN, 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP b KQkq - 0 1'),
    ).toBeNull();
    expect(lastMoveSquares(START_FEN, 'rnbqkbnr/pppppppp/9/8/4P3/8/PPPP1PPP/RNBQKBNR')).toBeNull();
    expect(lastMoveSquares(START_FEN, 'rnbqkbnr/pppppppp/8/8/4X3/8/PPPP1PPP/RNBQKBNR')).toBeNull();
    expect(lastMoveSquares(START_FEN, 'rnbqkbnr/pppppppp/7/8/4P3/8/PPPP1PPP/RNBQKBNR')).toBeNull();
  });
});
