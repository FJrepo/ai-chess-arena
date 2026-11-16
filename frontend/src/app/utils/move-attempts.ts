import { MoveAttempt, MoveAttemptOutcome } from '../models/tournament.model';

/**
 * Outcomes that count as a failed model attempt. Provider errors are excluded: they are retried without
 * using up the model's attempts and are not the model's fault.
 */
const FAILED_OUTCOMES: ReadonlySet<MoveAttemptOutcome> = new Set([
  'ILLEGAL_MOVE',
  'PARSE_FAILURE',
  'EMPTY_RESPONSE',
  'TRUNCATED',
  'TIMEOUT',
]);

export function isFailedAttempt(attempt: MoveAttempt): boolean {
  return FAILED_OUTCOMES.has(attempt.outcome);
}

export function plyKey(moveNumber: number, color: 'WHITE' | 'BLACK'): string {
  return `${moveNumber}-${color}`;
}

/** Failed attempts grouped by ply ({@link plyKey}), in attempt order. */
export function failedAttemptsByPly(attempts: MoveAttempt[]): Map<string, MoveAttempt[]> {
  const byPly = new Map<string, MoveAttempt[]>();
  for (const attempt of attempts) {
    if (!isFailedAttempt(attempt)) {
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
      return 'cut off at the token limit';
    case 'TIMEOUT':
      return 'no answer in time';
    case 'PROVIDER_RETRYABLE':
    case 'PROVIDER_FATAL':
      return 'provider error';
    case 'ACCEPTED':
      return attempt.proposedMove ? `played ${attempt.proposedMove}` : 'accepted';
  }
}

/** E.g. "2 failed attempts: illegal Qxh7, unparseable answer"; null when there are none. */
export function summarizeFailedAttempts(attempts: MoveAttempt[]): string | null {
  const failed = attempts.filter(isFailedAttempt);
  if (failed.length === 0) {
    return null;
  }
  const noun = failed.length === 1 ? 'failed attempt' : 'failed attempts';
  return `${failed.length} ${noun}: ${failed.map(describeAttempt).join(', ')}`;
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
