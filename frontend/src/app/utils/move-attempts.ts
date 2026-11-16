import { MoveAttempt, MoveAttemptOutcome } from '../models/tournament.model';

/**
 * Outcomes that count as a failed model attempt (the model's own fault). Cut-off answers (a too low
 * output-token limit), provider errors and discarded legal answers are not failures of the model and must
 * not feed anything that rates its reliability.
 */
const FAILED_OUTCOMES: ReadonlySet<MoveAttemptOutcome> = new Set([
  'ILLEGAL_MOVE',
  'PARSE_FAILURE',
  'EMPTY_RESPONSE',
  'TIMEOUT',
  'RESIGN_NOT_ALLOWED',
]);

/** Outcomes worth showing in the game feed: model failures plus cut-off and discarded answers. */
const NOTABLE_OUTCOMES: ReadonlySet<MoveAttemptOutcome> = new Set([
  ...FAILED_OUTCOMES,
  'TRUNCATED',
  'DISCARDED',
]);

export function isFailedAttempt(attempt: MoveAttempt): boolean {
  return FAILED_OUTCOMES.has(attempt.outcome);
}

export function plyKey(moveNumber: number, color: 'WHITE' | 'BLACK'): string {
  return `${moveNumber}-${color}`;
}

/** Attempts worth showing in the feed, grouped by ply ({@link plyKey}), in attempt order. */
export function notableAttemptsByPly(attempts: MoveAttempt[]): Map<string, MoveAttempt[]> {
  const byPly = new Map<string, MoveAttempt[]>();
  for (const attempt of attempts) {
    if (!NOTABLE_OUTCOMES.has(attempt.outcome)) {
      continue;
    }
    const key = plyKey(attempt.moveNumber, attempt.color);
    const list = byPly.get(key) ?? [];
    list.push(attempt);
    byPly.set(key, list);
  }
  return byPly;
}

export function describeAttempt(attempt: MoveAttempt): string {
  switch (attempt.outcome) {
    case 'ILLEGAL_MOVE':
      return attempt.proposedMove ? `illegal ${attempt.proposedMove}` : 'illegal move';
    case 'PARSE_FAILURE':
      return 'unparseable answer';
    case 'EMPTY_RESPONSE':
      return 'empty answer';
    case 'TRUNCATED':
      return 'answer cut off at the token limit';
    case 'TIMEOUT':
      return 'no answer in time';
    case 'PROVIDER_RETRYABLE':
    case 'PROVIDER_FATAL':
      return 'provider error';
    case 'DISCARDED':
      return attempt.proposedMove
        ? `${attempt.proposedMove} discarded (position changed)`
        : 'answer discarded (position changed)';
    case 'RESIGN_NOT_ALLOWED':
      return 'resignation not allowed';
    case 'RESIGNED':
      return 'resigned';
    case 'ACCEPTED':
      return attempt.proposedMove ? `played ${attempt.proposedMove}` : 'accepted';
  }
}

/**
 * E.g. "2 failed attempts: illegal Qxh7, unparseable answer; answer cut off at the token limit".
 * Cut-off and discarded answers are listed separately and never counted as failed attempts. Null when there
 * is nothing to show.
 */
export function summarizeAttempts(attempts: MoveAttempt[]): string | null {
  const parts: string[] = [];
  const failed = attempts.filter(isFailedAttempt);
  if (failed.length > 0) {
    const noun = failed.length === 1 ? 'failed attempt' : 'failed attempts';
    parts.push(`${failed.length} ${noun}: ${failed.map(describeAttempt).join(', ')}`);
  }
  const truncated = attempts.filter((attempt) => attempt.outcome === 'TRUNCATED').length;
  if (truncated === 1) {
    parts.push('answer cut off at the token limit');
  } else if (truncated > 1) {
    parts.push(`${truncated} answers cut off at the token limit`);
  }
  for (const attempt of attempts) {
    if (attempt.outcome === 'DISCARDED') {
      parts.push(describeAttempt(attempt));
    }
  }
  return parts.length > 0 ? parts.join('; ') : null;
}

/** The ply to be played after {@code moves}: White's first move when there are none. */
export function nextPly(moves: Array<{ moveNumber: number; color: 'WHITE' | 'BLACK' }>): {
  moveNumber: number;
  color: 'WHITE' | 'BLACK';
} {
  const last = moves[moves.length - 1];
  if (!last) {
    return { moveNumber: 1, color: 'WHITE' };
  }
  return last.color === 'WHITE'
    ? { moveNumber: last.moveNumber, color: 'BLACK' }
    : { moveNumber: last.moveNumber + 1, color: 'WHITE' };
}

/** The most recent attempt for the given ply, or null. */
export function latestAttemptFor(
  attempts: MoveAttempt[],
  moveNumber: number,
  color: 'WHITE' | 'BLACK',
): MoveAttempt | null {
  let latest: MoveAttempt | null = null;
  for (const attempt of attempts) {
    if (attempt.moveNumber !== moveNumber || attempt.color !== color) {
      continue;
    }
    if (!latest || Date.parse(attempt.createdAt) >= Date.parse(latest.createdAt)) {
      latest = attempt;
    }
  }
  return latest;
}
