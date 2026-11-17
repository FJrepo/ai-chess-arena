import {
  Move,
  MoveJudgement,
  MoveQuality,
  PlyQuality,
  SideQuality,
} from '../models/tournament.model';

/** Tone of a quality badge; the stylesheets map it to the app's colours. */
export type QualityTone = 'inaccuracy' | 'mistake' | 'blunder';

/** Annotation for a move with a notable error. */
export interface QualityBadge {
  glyph: '?!' | '?' | '??';
  tone: QualityTone;
  label: 'Inaccuracy' | 'Mistake' | 'Blunder';
}

const BADGES: Record<MoveJudgement, QualityBadge> = {
  INACCURACY: { glyph: '?!', tone: 'inaccuracy', label: 'Inaccuracy' },
  MISTAKE: { glyph: '?', tone: 'mistake', label: 'Mistake' },
  BLUNDER: { glyph: '??', tone: 'blunder', label: 'Blunder' },
};

/** The badge of a judged error; null for a fine, excluded or not (yet) judged move. */
export function qualityBadge(quality: MoveQuality | null | undefined): QualityBadge | null {
  return quality?.judgement ? BADGES[quality.judgement] : null;
}

/** A probability (0–1) as a whole percentage, e.g. 0.718 -> "72%". */
function percent(probability: number): string {
  return `${Math.round(probability * 100)}%`;
}

/**
 * Tooltip for a move's quality: "Blunder: winning chances 72% → 18% (−3.2)" for an error (the mover's
 * winning chances and centipawn loss in pawns), a note for a forced move or an override, and null otherwise.
 */
export function qualityTooltip(quality: MoveQuality | null | undefined): string | null {
  if (!quality) {
    return null;
  }
  if (quality.excluded === 'FORCED') {
    return 'Only legal move: not judged';
  }
  if (quality.excluded === 'OVERRIDE') {
    return 'Operator override: not judged';
  }
  const badge = qualityBadge(quality);
  if (!badge) {
    return null;
  }
  const loss = ((quality.centipawnLoss ?? 0) / 100).toFixed(1);
  return (
    `${badge.label}: winning chances ${percent(quality.winChanceBefore)} → ` +
    `${percent(quality.winChanceAfter)} (−${loss})`
  );
}

/** Separate from quality judgement: a mate can be missed while retaining high winning chances. */
export function missedMateTooltip(quality: MoveQuality | null | undefined): string | null {
  if (!quality?.missedMate || quality.excluded) return null;
  return quality.missedMateIn != null ? `Missed mate in ${quality.missedMateIn}` : 'Missed mate';
}

/** Applies the qualities of an evaluation update to the matching moves; other moves stay as they are. */
export function applyPlyQualities(
  moves: Move[],
  qualities: readonly PlyQuality[] | undefined,
): Move[] {
  if (!qualities?.length) {
    return moves;
  }
  const updated = new Set<string>();
  return moves.map((move) => {
    const key = `${move.moveNumber}-${move.color}`;
    if (updated.has(key)) return move;
    updated.add(key);
    const update = qualities.find(
      (entry) => entry.moveNumber === move.moveNumber && entry.color === move.color,
    );
    return update ? { ...move, quality: update.quality } : move;
  });
}

/** One entry of a side's quality summary, for the compact line near the players. */
export interface QualityCount {
  glyph: QualityBadge['glyph'];
  tone: QualityTone;
  count: number;
  label: string;
}

/** The side's error counts, worst first: "??", "?", "?!". */
export function qualityCounts(side: SideQuality): QualityCount[] {
  return [
    { ...BADGES.BLUNDER, count: side.blunders, label: plural(side.blunders, 'blunder') },
    { ...BADGES.MISTAKE, count: side.mistakes, label: plural(side.mistakes, 'mistake') },
    {
      ...BADGES.INACCURACY,
      count: side.inaccuracies,
      label: plural(side.inaccuracies, 'inaccuracy', 'inaccuracies'),
    },
  ].map(({ glyph, tone, count, label }) => ({ glyph, tone, count, label }));
}

/** Tooltip for a side's summary, e.g. "Average centipawn loss 41 over 35 judged moves; 1 blunder, ...". */
export function sideQualityTooltip(side: SideQuality): string {
  const moves = plural(side.analyzedMoves, 'judged move');
  const counts = qualityCounts(side)
    .map((entry) => entry.label)
    .join(', ');
  return `Average centipawn loss ${side.averageCentipawnLoss ?? 'n/a'} over ${moves}; ${counts}`;
}

function plural(count: number, singular: string, pluralForm = `${singular}s`): string {
  return `${count} ${count === 1 ? singular : pluralForm}`;
}
