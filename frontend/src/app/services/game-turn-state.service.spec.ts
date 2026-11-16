import { GameTurnStateService } from './game-turn-state.service';
import { Game, Move } from '../models/tournament.model';

function createGame(overrides?: Partial<Game>): Game {
  return {
    id: 'g-1',
    tournamentId: 't-1',
    whitePlayerName: 'Alpha',
    whiteControlType: 'AI',
    whiteModelId: 'model-a',
    blackPlayerName: 'Beta',
    blackControlType: 'AI',
    blackModelId: 'model-b',
    status: 'IN_PROGRESS',
    result: null,
    resultReason: null,
    pgn: null,
    currentFen: '8/8/8/8/8/8/8/8 w - - 0 1',
    bracketRound: null,
    bracketPosition: null,
    seriesId: null,
    seriesGameNumber: 1,
    seriesBestOf: 1,
    totalCostUsd: 0,
    createdAt: '2026-03-07T10:00:00Z',
    startedAt: '2026-03-07T10:01:00Z',
    completedAt: null,
    moves: [],
    chatMessages: [],
    ...overrides,
  };
}

describe('GameTurnStateService', () => {
  it('derives active color from the current fen when no server color is known', () => {
    const service = new GameTurnStateService();

    service.bind(
      () => createGame(),
      () => [],
      () => '8/8/8/8/8/8/8/8 b - - 0 1',
    );

    expect(service.isLive()).toBe(true);
    expect(service.activeColor()).toBe('BLACK');
  });

  it('uses server deadline updates when available', () => {
    const service = new GameTurnStateService();
    const move: Move = {
      id: 'm-1',
      moveNumber: 1,
      color: 'WHITE',
      san: 'e4',
      fen: '8/8/8/8/8/8/8/8 b - - 0 1',
      modelId: 'model-a',
      promptVersion: null,
      promptHash: null,
      promptTokens: null,
      completionTokens: null,
      costUsd: null,
      responseTimeMs: 1000,
      retryCount: 0,
      isOverride: false,
      evaluationCp: null,
      evaluationMate: null,
      createdAt: '2026-03-07T10:01:05Z',
    };

    service.bind(
      () => createGame(),
      () => [move],
      () => move.fen,
    );
    service.timerNowMs.set(Date.parse('2026-03-07T10:01:10Z'));
    service.applyStatusUpdate(
      'IN_PROGRESS',
      'BLACK',
      '2026-03-07T10:01:05Z',
      '2026-03-07T10:01:18Z',
    );

    expect(service.activeColor()).toBe('BLACK');
    expect(service.moveTimerSeconds()).toBe(8);
    expect(service.moveTimerDisplay()).toBe('00:08');
    expect(service.moveTimerWarning()).toBe(true);
  });

  it('shows no countdown when the tournament has no time limit per move', () => {
    const service = new GameTurnStateService();
    service.bind(
      () => createGame(),
      () => [],
      () => '8/8/8/8/8/8/8/8 w - - 0 1',
    );
    service.setMoveTimeoutSeconds(null);
    service.timerNowMs.set(Date.parse('2026-03-07T10:01:10Z'));
    // The server announces the turn without a deadline.
    service.applyStatusUpdate('IN_PROGRESS', 'WHITE', '2026-03-07T10:01:05Z', undefined);

    expect(service.noMoveTimeLimit()).toBe(true);
    expect(service.moveTimerSeconds()).toBeNull();
    expect(service.moveTimerDisplay()).toBe('--:--');
    expect(service.moveTimerWarning()).toBe(false);
    expect(service.activeColor()).toBe('WHITE');
  });

  it('shows no countdown before the time limit is known', () => {
    const service = new GameTurnStateService();
    service.bind(
      () => createGame(),
      () => [],
      () => '8/8/8/8/8/8/8/8 w - - 0 1',
    );

    expect(service.noMoveTimeLimit()).toBe(false);
    expect(service.moveTimerSeconds()).toBeNull();
  });

  it('counts down from a known time limit until the server sends a deadline', () => {
    const service = new GameTurnStateService();
    service.bind(
      () => createGame(),
      () => [],
      () => '8/8/8/8/8/8/8/8 w - - 0 1',
    );
    service.setMoveTimeoutSeconds(30);
    service.timerNowMs.set(Date.parse('2026-03-07T10:01:10Z'));

    // 10 s after the game started at 10:01:00.
    expect(service.noMoveTimeLimit()).toBe(false);
    expect(service.moveTimerSeconds()).toBe(20);
  });
});
