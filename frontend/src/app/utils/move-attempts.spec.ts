import { MoveAttempt } from '../models/tournament.model';
import { notableAttemptsByPly, summarizeAttempts } from './move-attempts';

function attempt(overrides: Partial<MoveAttempt>): MoveAttempt {
  return {
    id: 'a',
    moveNumber: 1,
    color: 'WHITE',
    attemptNumber: 1,
    modelId: 'model-a',
    outcome: 'ACCEPTED',
    proposedMove: null,
    promptTokens: null,
    completionTokens: null,
    reasoningTokens: null,
    costUsd: null,
    responseTimeMs: null,
    finishReason: null,
    errorMessage: null,
    createdAt: '2026-03-06T10:00:00Z',
    ...overrides,
  };
}

describe('move attempts summary', () => {
  it('lists failed attempts compactly', () => {
    expect(
      summarizeAttempts([
        attempt({ outcome: 'ILLEGAL_MOVE', proposedMove: 'Qxh7' }),
        attempt({ outcome: 'PARSE_FAILURE', attemptNumber: 2 }),
        attempt({ outcome: 'ACCEPTED', attemptNumber: 3, proposedMove: 'e4' }),
      ]),
    ).toBe('2 failed attempts: illegal Qxh7, unparseable answer');
  });

  it('lists cut-off answers separately and never counts them as failed attempts', () => {
    expect(summarizeAttempts([attempt({ outcome: 'TRUNCATED' })])).toBe(
      'answer cut off at the token limit',
    );
    expect(
      summarizeAttempts([
        attempt({ outcome: 'EMPTY_RESPONSE' }),
        attempt({ outcome: 'TRUNCATED', attemptNumber: 2 }),
        attempt({ outcome: 'TRUNCATED', attemptNumber: 1 }),
      ]),
    ).toBe('1 failed attempt: empty answer; 2 answers cut off at the token limit');
  });

  it('names a discarded legal answer without counting it as a failure', () => {
    expect(summarizeAttempts([attempt({ outcome: 'DISCARDED', proposedMove: 'e4' })])).toBe(
      'e4 discarded (position changed)',
    );
  });

  it('ignores accepted answers and provider errors', () => {
    expect(
      summarizeAttempts([
        attempt({ outcome: 'PROVIDER_RETRYABLE' }),
        attempt({ outcome: 'ACCEPTED', proposedMove: 'e4' }),
      ]),
    ).toBeNull();
  });

  it('groups notable attempts by ply', () => {
    const byPly = notableAttemptsByPly([
      attempt({ outcome: 'EMPTY_RESPONSE' }),
      attempt({ outcome: 'ILLEGAL_MOVE', color: 'BLACK', proposedMove: 'Ke7' }),
      attempt({ outcome: 'ACCEPTED', color: 'BLACK', attemptNumber: 2 }),
    ]);

    expect([...byPly.keys()]).toEqual(['1-WHITE', '1-BLACK']);
    expect(byPly.get('1-BLACK')).toHaveLength(1);
  });
});
