import { Move } from '../models/tournament.model';
import { sortMovesInPlayOrder } from './move-order';

describe('sortMovesInPlayOrder', () => {
  it('puts White before Black within a move number, even with equal timestamps', () => {
    const at = '2026-03-01T10:00:00Z';
    const moves = [
      move(2, 'BLACK', 'Nc6', at),
      move(1, 'BLACK', 'c5', at),
      move(2, 'WHITE', 'Nf3', at),
      move(1, 'WHITE', 'e4', at),
    ];

    expect(sortMovesInPlayOrder(moves).map((m) => m.san)).toEqual(['e4', 'c5', 'Nf3', 'Nc6']);
  });

  it('orders duplicate plies by instant when fractional seconds differ in length', () => {
    const moves = [
      { ...move(1, 'WHITE', 'd4', '2026-11-18T23:15:24.1Z'), id: 'a' },
      { ...move(1, 'WHITE', 'e4', '2026-11-18T23:15:24Z'), id: 'b' },
      { ...move(1, 'WHITE', 'c4', '2026-11-18T23:15:24.000001Z'), id: 'c' },
    ];

    expect(sortMovesInPlayOrder(moves).map((m) => m.san)).toEqual(['e4', 'c4', 'd4']);
  });

  it('breaks equal instants by id', () => {
    const moves = [
      { ...move(1, 'WHITE', 'd4', '2026-11-18T23:15:24.500Z'), id: 'b' },
      { ...move(1, 'WHITE', 'e4', '2026-11-18T23:15:24.5Z'), id: 'a' },
    ];

    expect(sortMovesInPlayOrder(moves).map((m) => m.san)).toEqual(['e4', 'd4']);
  });

  it('does not change the given list', () => {
    const moves = [move(1, 'BLACK', 'c5', 'b'), move(1, 'WHITE', 'e4', 'a')];

    sortMovesInPlayOrder(moves);

    expect(moves.map((m) => m.san)).toEqual(['c5', 'e4']);
  });
});

function move(moveNumber: number, color: Move['color'], san: string, createdAt: string): Move {
  return {
    id: `${moveNumber}-${color}`,
    moveNumber,
    color,
    san,
    fen: '',
    modelId: null,
    promptVersion: null,
    promptHash: null,
    promptTokens: null,
    completionTokens: null,
    costUsd: null,
    responseTimeMs: null,
    retryCount: 0,
    isOverride: false,
    createdAt,
  } as Move;
}
