import { deriveCapturedMaterial } from './captured-material';

describe('deriveCapturedMaterial', () => {
  it('tracks captures from standard move history', () => {
    const moves = [
      {
        fen: 'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1',
        color: 'WHITE' as const,
        san: 'e4',
      },
      {
        fen: 'rnbqkbnr/ppp1pppp/8/3p4/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2',
        color: 'BLACK' as const,
        san: 'd5',
      },
      {
        fen: 'rnbqkbnr/ppp1pppp/8/3P4/8/8/PPPP1PPP/RNBQKBNR b KQkq - 0 2',
        color: 'WHITE' as const,
        san: 'exd5',
      },
    ];

    const material = deriveCapturedMaterial(moves, 2);
    expect(material.whiteCaptured).toEqual(['P']);
    expect(material.blackCaptured).toEqual([]);
    expect(material.materialDelta).toBe(1);
  });

  it('does not count own pawn loss during promotion as capture', () => {
    const startFen = '8/P7/8/8/8/8/8/K6k w - - 0 1';
    const moves = [
      {
        fen: 'Q7/8/8/8/8/8/8/K6k b - - 0 1',
        color: 'WHITE' as const,
        san: 'a8=Q',
      },
    ];

    const material = deriveCapturedMaterial(moves, 0, startFen);
    expect(material.whiteCaptured).toEqual([]);
    expect(material.blackCaptured).toEqual([]);
    expect(material.materialDelta).toBe(0);
  });

  it('counts capture on promotion correctly', () => {
    const startFen = '1r6/P7/8/8/8/8/8/K6k w - - 0 1';
    const moves = [
      {
        fen: '1Q6/8/8/8/8/8/8/K6k b - - 0 1',
        color: 'WHITE' as const,
        san: 'axb8=Q+',
      },
    ];

    const material = deriveCapturedMaterial(moves, 0, startFen);
    expect(material.whiteCaptured).toEqual(['R']);
    expect(material.blackCaptured).toEqual([]);
    expect(material.materialDelta).toBe(5);
  });
});
