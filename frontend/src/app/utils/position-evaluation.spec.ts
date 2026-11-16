import { Game, Move } from '../models/tournament.model';
import {
  NO_EVALUATION,
  evaluationAt,
  evaluationDescription,
  evaluationLabel,
  hasEvaluation,
  whiteBarShare,
} from './position-evaluation';

function move(
  color: 'WHITE' | 'BLACK',
  evaluationCp: number | null = null,
  evaluationMate: number | null = null,
): Move {
  return {
    id: '',
    moveNumber: 1,
    color,
    san: 'x',
    fen: 'fen',
    modelId: null,
    promptVersion: null,
    promptHash: null,
    promptTokens: null,
    completionTokens: null,
    costUsd: null,
    responseTimeMs: null,
    retryCount: 0,
    isOverride: false,
    evaluationCp,
    evaluationMate,
    createdAt: '2026-03-06T10:02:00Z',
  };
}

function game(
  status: Game['status'],
  resultReason: string | null = null,
): Pick<Game, 'status' | 'resultReason'> {
  return { status, resultReason };
}

describe('evaluationAt', () => {
  it('has no evaluation for the start position or an empty move list', () => {
    expect(evaluationAt([move('WHITE', 30)], -1, game('IN_PROGRESS'))).toEqual(NO_EVALUATION);
    expect(evaluationAt([], -1, game('COMPLETED', 'CHECKMATE'))).toEqual(NO_EVALUATION);
    expect(evaluationAt([], 0, null)).toEqual(NO_EVALUATION);
  });

  it('returns the stored centipawn or mate score of the viewed move', () => {
    const moves = [move('WHITE', 35), move('BLACK', null, -3)];

    expect(evaluationAt(moves, 0, game('IN_PROGRESS'))).toEqual({
      cp: 35,
      mate: null,
      checkmated: null,
    });
    expect(evaluationAt(moves, 1, game('IN_PROGRESS'))).toEqual({
      cp: null,
      mate: -3,
      checkmated: null,
    });
  });

  it('keeps a stored 0.0 as a known, level evaluation', () => {
    const evaluation = evaluationAt([move('WHITE', 0)], 0, game('IN_PROGRESS'));
    expect(evaluation.cp).toBe(0);
    expect(hasEvaluation(evaluation)).toBe(true);
  });

  it('shows an unevaluated move as unknown instead of 0.0 or an earlier score', () => {
    const moves = [move('WHITE', 120), move('BLACK')];

    const evaluation = evaluationAt(moves, 1, game('IN_PROGRESS'));

    expect(evaluation).toEqual(NO_EVALUATION);
    expect(hasEvaluation(evaluation)).toBe(false);
  });

  it('shows the unevaluated last move of a finished game that did not end on the board as unknown', () => {
    const moves = [move('WHITE', 40), move('BLACK')];

    expect(evaluationAt(moves, 1, game('COMPLETED', 'RESIGNATION'))).toEqual(NO_EVALUATION);
    expect(evaluationAt(moves, 1, game('FORFEIT', 'TIMEOUT'))).toEqual(NO_EVALUATION);
  });

  it('shows the stored score of the last move of a forfeited game', () => {
    const moves = [move('WHITE', 40), move('BLACK', -210)];
    expect(evaluationAt(moves, 1, game('FORFEIT', 'TIMEOUT')).cp).toBe(-210);
  });

  it('shows the final position of a checkmate as a win for the mating side, even without a score', () => {
    const whiteMates = [move('WHITE', 50), move('BLACK', 900), move('WHITE')];
    const blackMates = [move('WHITE', 50), move('BLACK')];

    expect(evaluationAt(whiteMates, 2, game('COMPLETED', 'CHECKMATE'))).toEqual({
      cp: null,
      mate: 0,
      checkmated: 'BLACK',
    });
    expect(evaluationAt(blackMates, 1, game('COMPLETED', 'CHECKMATE'))).toEqual({
      cp: null,
      mate: 0,
      checkmated: 'WHITE',
    });
  });

  it('prefers the checkmate over a stale stored score on the final position', () => {
    const moves = [move('WHITE', 12)];
    expect(evaluationAt(moves, 0, game('COMPLETED', 'CHECKMATE')).checkmated).toBe('BLACK');
  });

  it("reads Stockfish's unsigned mate 0 as checkmate of the side to move, before the game status arrives", () => {
    const moves = [move('WHITE', 20), move('BLACK', null, 0)];
    expect(evaluationAt(moves, 1, game('IN_PROGRESS')).checkmated).toBe('WHITE');
  });

  it('keeps the stored scores of earlier moves in a game that ended in checkmate', () => {
    const moves = [move('WHITE', 50), move('BLACK', null, -1), move('WHITE')];
    const finished = game('COMPLETED', 'CHECKMATE');

    expect(evaluationAt(moves, 0, finished)).toEqual({ cp: 50, mate: null, checkmated: null });
    expect(evaluationAt(moves, 1, finished)).toEqual({ cp: null, mate: -1, checkmated: null });
  });

  it('shows an unevaluated final stalemate or insufficient-material position as level', () => {
    const moves = [move('WHITE', 300), move('BLACK')];

    expect(evaluationAt(moves, 1, game('COMPLETED', 'STALEMATE')).cp).toBe(0);
    expect(evaluationAt(moves, 1, game('COMPLETED', 'INSUFFICIENT_MATERIAL')).cp).toBe(0);
    expect(evaluationAt(moves, 0, game('COMPLETED', 'STALEMATE')).cp).toBe(300);
    expect(evaluationAt(moves, 1, game('COMPLETED', 'REPETITION'))).toEqual(NO_EVALUATION);
  });

  it('shows a final stalemate or insufficient-material position as level even with a stored score', () => {
    const level = { cp: 0, mate: null, checkmated: null };
    const withCp = [move('WHITE', 40), move('BLACK', 300)];
    const withMate = [move('WHITE', 40), move('BLACK', null, -4)];

    expect(evaluationAt(withCp, 1, game('COMPLETED', 'STALEMATE'))).toEqual(level);
    expect(evaluationAt(withCp, 1, game('COMPLETED', 'INSUFFICIENT_MATERIAL'))).toEqual(level);
    expect(evaluationAt(withMate, 1, game('COMPLETED', 'STALEMATE'))).toEqual(level);
    expect(evaluationLabel(evaluationAt(withCp, 1, game('COMPLETED', 'STALEMATE')))).toBe('0.0');
  });

  it('keeps a stored score on the last move of a drawn game that is not a dead position', () => {
    const moves = [move('WHITE', 40), move('BLACK', 300)];

    expect(evaluationAt(moves, 1, game('COMPLETED', 'REPETITION')).cp).toBe(300);
    expect(evaluationAt(moves, 1, game('COMPLETED', 'FIFTY_MOVE')).cp).toBe(300);
  });

  it('keeps the stored score of the last move while the game is still running', () => {
    const moves = [move('WHITE', 40), move('BLACK', 300)];
    expect(evaluationAt(moves, 1, game('IN_PROGRESS', 'STALEMATE')).cp).toBe(300);
  });
});

describe('evaluationLabel and whiteBarShare', () => {
  it('formats centipawn scores', () => {
    expect(evaluationLabel({ cp: 42, mate: null, checkmated: null })).toBe('+0.4');
    expect(evaluationLabel({ cp: -130, mate: null, checkmated: null })).toBe('-1.3');
    expect(evaluationLabel({ cp: 0, mate: null, checkmated: null })).toBe('0.0');
    expect(whiteBarShare({ cp: 0, mate: null, checkmated: null })).toBe(50);
    expect(whiteBarShare({ cp: 100000, mate: null, checkmated: null })).toBe(95);
    expect(whiteBarShare({ cp: -100000, mate: null, checkmated: null })).toBe(5);
  });

  it('formats forced mates', () => {
    expect(evaluationLabel({ cp: null, mate: 3, checkmated: null })).toBe('M3');
    expect(evaluationLabel({ cp: null, mate: -2, checkmated: null })).toBe('-M2');
    expect(whiteBarShare({ cp: null, mate: 3, checkmated: null })).toBe(100);
    expect(whiteBarShare({ cp: null, mate: -2, checkmated: null })).toBe(0);
  });

  it('shows a delivered checkmate as the result, with the bar fully on the winner', () => {
    expect(evaluationLabel({ cp: null, mate: 0, checkmated: 'BLACK' })).toBe('1-0');
    expect(whiteBarShare({ cp: null, mate: 0, checkmated: 'BLACK' })).toBe(100);
    expect(evaluationLabel({ cp: null, mate: 0, checkmated: 'WHITE' })).toBe('0-1');
    expect(whiteBarShare({ cp: null, mate: 0, checkmated: 'WHITE' })).toBe(0);
  });

  it('shows an unknown evaluation as a dash on a neutral bar, not as 0.0', () => {
    expect(evaluationLabel(NO_EVALUATION)).toBe('–');
    expect(whiteBarShare(NO_EVALUATION)).toBe(50);
  });

  it('describes only the unknown and checkmate states', () => {
    expect(evaluationDescription(NO_EVALUATION)).toBe('Evaluation unavailable');
    expect(evaluationDescription({ cp: null, mate: 0, checkmated: 'BLACK' })).toBe(
      'White wins by checkmate',
    );
    expect(evaluationDescription({ cp: null, mate: 0, checkmated: 'WHITE' })).toBe(
      'Black wins by checkmate',
    );
    expect(evaluationDescription({ cp: 0, mate: null, checkmated: null })).toBeNull();
    expect(evaluationDescription({ cp: null, mate: -2, checkmated: null })).toBeNull();
  });
});
