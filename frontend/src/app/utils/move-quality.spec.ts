import { Move, MoveQuality, SideQuality } from '../models/tournament.model';
import {
  applyPlyQualities,
  qualityBadge,
  qualityCounts,
  qualityTooltip,
  sideQualityTooltip,
} from './move-quality';

function quality(overrides: Partial<MoveQuality> = {}): MoveQuality {
  return {
    judgement: null,
    centipawnLoss: 12,
    winChanceBefore: 0.52,
    winChanceAfter: 0.5,
    excluded: null,
    ...overrides,
  };
}

function move(moveNumber: number, color: 'WHITE' | 'BLACK'): Move {
  return {
    id: `m-${moveNumber}-${color}`,
    moveNumber,
    color,
    san: 'e4',
    fen: 'fen',
    modelId: 'model-a',
    promptVersion: null,
    promptHash: null,
    promptTokens: null,
    completionTokens: null,
    costUsd: null,
    responseTimeMs: 100,
    retryCount: 0,
    isOverride: false,
    createdAt: '2026-03-06T10:02:00Z',
  };
}

describe('move quality', () => {
  it('maps judgements to the ?!, ? and ?? annotations and their tones', () => {
    expect(qualityBadge(quality({ judgement: 'INACCURACY' }))).toEqual({
      glyph: '?!',
      tone: 'inaccuracy',
      label: 'Inaccuracy',
    });
    expect(qualityBadge(quality({ judgement: 'MISTAKE' }))?.glyph).toBe('?');
    expect(qualityBadge(quality({ judgement: 'BLUNDER' }))?.tone).toBe('blunder');
  });

  it('has no badge for a fine, excluded or not yet judged move', () => {
    expect(qualityBadge(quality())).toBeNull();
    expect(qualityBadge(quality({ excluded: 'FORCED', centipawnLoss: null }))).toBeNull();
    expect(qualityBadge(null)).toBeNull();
    expect(qualityBadge(undefined)).toBeNull();
  });

  it('explains a blunder with the mover’s winning chances and the loss in pawns', () => {
    const blunder = quality({
      judgement: 'BLUNDER',
      centipawnLoss: 618,
      winChanceBefore: 0.7372,
      winChanceAfter: 0.2236,
    });

    expect(qualityTooltip(blunder)).toBe('Blunder: winning chances 74% → 22% (−6.2)');
  });

  it('notes forced moves and overrides, and has no tooltip for fine moves', () => {
    expect(qualityTooltip(quality({ excluded: 'FORCED', centipawnLoss: null }))).toBe(
      'Only legal move: not judged',
    );
    expect(qualityTooltip(quality({ excluded: 'OVERRIDE', centipawnLoss: null }))).toBe(
      'Operator override: not judged',
    );
    expect(qualityTooltip(quality())).toBeNull();
    expect(qualityTooltip(null)).toBeNull();
  });

  it('applies the qualities of an evaluation update to the matching moves only', () => {
    const moves = [move(1, 'WHITE'), move(1, 'BLACK'), move(2, 'WHITE')];
    const mistake = quality({ judgement: 'MISTAKE', centipawnLoss: 150 });

    const updated = applyPlyQualities(moves, [
      { moveNumber: 1, color: 'BLACK', quality: mistake },
      { moveNumber: 2, color: 'WHITE', quality: null },
      { moveNumber: 3, color: 'WHITE', quality: mistake },
    ]);

    expect(updated[0]).toBe(moves[0]);
    expect(updated[1].quality).toEqual(mistake);
    expect(updated[2].quality).toBeNull();
    expect(applyPlyQualities(moves, undefined)).toBe(moves);
  });

  it('lists a side’s counts worst first and describes them', () => {
    const side: SideQuality = {
      analyzedMoves: 35,
      averageCentipawnLoss: 41,
      inaccuracies: 3,
      mistakes: 1,
      blunders: 0,
    };

    expect(qualityCounts(side).map((entry) => `${entry.glyph}${entry.count}`)).toEqual([
      '??0',
      '?1',
      '?!3',
    ]);
    expect(sideQualityTooltip(side)).toBe(
      'Average centipawn loss 41 over 35 judged moves; 0 blunders, 1 mistake, 3 inaccuracies',
    );
  });
});
