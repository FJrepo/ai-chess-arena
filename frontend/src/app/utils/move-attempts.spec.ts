import { MoveAttempt } from '../models/tournament.model';
import { failedAttemptsByPly, summarizeFailedAttempts } from './move-attempts';

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
      summarizeFailedAttempts([
        attempt({ outcome: 'ILLEGAL_MOVE', proposedMove: 'Qxh7' }),
        attempt({ outcome: 'PARSE_FAILURE', attemptNumber: 2 }),
        attempt({ outcome: 'ACCEPTED', attemptNumber: 3, proposedMove: 'e4' }),
      ]),
    ).toBe('2 failed attempts: illegal Qxh7, unparseable answer');
  });

  it('uses the singular and names truncated answers', () => {
    expect(summarizeFailedAttempts([attempt({ outcome: 'TRUNCATED' })])).toBe(
      '1 failed attempt: cut off at the token limit',
    );
  });

  it('ignores accepted answers and provider errors', () => {
    expect(
      summarizeFailedAttempts([
        attempt({ outcome: 'PROVIDER_RETRYABLE' }),
        attempt({ outcome: 'ACCEPTED', proposedMove: 'e4' }),
      ]),
    ).toBeNull();
  });

  it('groups failed attempts by ply', () => {
    const byPly = failedAttemptsByPly([
      attempt({ outcome: 'EMPTY_RESPONSE' }),
      attempt({ outcome: 'ILLEGAL_MOVE', color: 'BLACK', proposedMove: 'Ke7' }),
      attempt({ outcome: 'ACCEPTED', color: 'BLACK', attemptNumber: 2 }),
    ]);

    expect([...byPly.keys()]).toEqual(['1-WHITE', '1-BLACK']);
    expect(byPly.get('1-BLACK')).toHaveLength(1);
  });
});
