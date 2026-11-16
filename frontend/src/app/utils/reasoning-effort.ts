import { OpenRouterModelOption } from '../models/openrouter.model';

/** OpenRouter's reasoning-effort levels, from least to most thinking (the backend's order too). */
export const REASONING_EFFORTS = [
  'none',
  'minimal',
  'low',
  'medium',
  'high',
  'xhigh',
  'max',
] as const;

export type ReasoningEffort = (typeof REASONING_EFFORTS)[number];

const LABELS: Record<ReasoningEffort, string> = {
  none: 'None (reasoning off)',
  minimal: 'Minimal',
  low: 'Low',
  medium: 'Medium',
  high: 'High',
  xhigh: 'Extra high',
  max: 'Max',
};

/** The level the pause banner pre-selects for a model whose answer was cut off while thinking. */
export const SUGGESTED_LOWER_EFFORT: ReasoningEffort = 'low';

/** The one-line hint under the tournament's reasoning-effort select. */
export const REASONING_EFFORT_HINT =
  'Lower effort makes heavy thinkers faster and cheaper; some models think without end at their default';

export function isReasoningEffort(value: unknown): value is ReasoningEffort {
  return typeof value === 'string' && (REASONING_EFFORTS as readonly string[]).includes(value);
}

/** A readable label, e.g. "Extra high" for xhigh; unknown values are shown as they are. */
export function reasoningEffortLabel(effort: string | null | undefined): string {
  if (!effort) {
    return '';
  }
  return isReasoningEffort(effort) ? LABELS[effort] : effort;
}

/**
 * The levels a model offers, in order from least to most; empty for a model without reasoning control (no
 * reasoning, or a catalog entry without effort levels). Unknown level names are left out.
 */
export function supportedEfforts(
  model: OpenRouterModelOption | null | undefined,
): ReasoningEffort[] {
  const listed = new Set(model?.reasoningEfforts ?? []);
  return REASONING_EFFORTS.filter(
    (effort) => listed.has(effort) && !(effort === 'none' && model?.reasoningMandatory),
  );
}

/**
 * The supported level closest to `requested`, with the backend's rule: the level itself when supported,
 * otherwise the nearest one, a tie going to the lower level; `none` (reasoning off) only when requested.
 * Null when nothing is left.
 */
export function nearestEffort(
  requested: ReasoningEffort,
  supported: readonly ReasoningEffort[],
): ReasoningEffort | null {
  const target = REASONING_EFFORTS.indexOf(requested);
  let best: ReasoningEffort | null = null;
  let bestDistance = Number.POSITIVE_INFINITY;
  for (const candidate of REASONING_EFFORTS) {
    if (!supported.includes(candidate) || (candidate === 'none' && requested !== 'none')) {
      continue;
    }
    const distance = Math.abs(REASONING_EFFORTS.indexOf(candidate) - target);
    if (distance < bestDistance) {
      best = candidate;
      bestDistance = distance;
    }
  }
  return best;
}

/**
 * The level to suggest when a model's answer was cut off: `low` (or the nearest supported level), unless the
 * side already asks for that little or less, which is kept.
 */
export function suggestedLowerEffort(
  current: ReasoningEffort | null,
  options: readonly ReasoningEffort[],
): ReasoningEffort | null {
  const mappedCurrent = current ? nearestEffort(current, options) : null;
  const suggestion = nearestEffort(SUGGESTED_LOWER_EFFORT, options);
  if (
    mappedCurrent &&
    (!suggestion ||
      REASONING_EFFORTS.indexOf(mappedCurrent) <= REASONING_EFFORTS.indexOf(suggestion))
  ) {
    return mappedCurrent;
  }
  return suggestion;
}
