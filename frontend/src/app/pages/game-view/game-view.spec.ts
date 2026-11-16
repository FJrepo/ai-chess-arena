import { of, throwError } from 'rxjs';
import { GameView } from './game-view';
import { GameTurnStateService } from '../../services/game-turn-state.service';
import { Game, Move, MoveAttempt } from '../../models/tournament.model';

function createComponent(overrides?: { api?: Partial<any>; snackbar?: Partial<any> }) {
  const api = {
    pauseGame: () => of(void 0),
    startGame: () => of(void 0),
    getGameAttempts: () => of([]),
    ...overrides?.api,
  };
  const snackbar = {
    open: vi.fn(),
    ...overrides?.snackbar,
  };

  return {
    component: new GameView(
      {} as any,
      api as any,
      {} as any,
      { navigate: vi.fn() } as any,
      snackbar as any,
      new GameTurnStateService(),
    ),
    api,
    snackbar,
  };
}

describe('GameView', () => {
  it('hides the evaluation bar when stockfish is unavailable', () => {
    const { component } = createComponent();

    component.stockfishAvailable.set(false);
    component.stockfishReason.set('Stockfish missing');

    expect(component.showEvaluationBar()).toBe(false);
    expect(component.evaluationStatusMessage()).toBe('Evaluation unavailable');
    expect(component.evaluationStatusLabel()).toBe('Eval unavailable');
  });

  it('keeps chat out of the feed by default and can opt it back in', () => {
    const { component } = createComponent();
    component.game.set({
      id: 'g-1',
      tournamentId: 't-1',
      whitePlayerName: 'Alpha',
      whiteControlType: 'AI',
      whiteModelId: 'model-a',
      blackPlayerName: 'Beta',
      blackControlType: 'AI',
      blackModelId: 'model-b',
      status: 'COMPLETED',
      result: 'WHITE_WINS',
      resultReason: null,
      pgn: null,
      currentFen: 'fen',
      bracketRound: 'Final',
      bracketPosition: 0,
      seriesId: null,
      seriesGameNumber: 1,
      seriesBestOf: 1,
      totalCostUsd: 0,
      createdAt: '2026-03-06T10:00:00Z',
      startedAt: '2026-03-06T10:01:00Z',
      completedAt: '2026-03-06T10:05:00Z',
      moves: [],
      chatMessages: [],
    });
    component.moves.set([
      {
        id: 'm1',
        moveNumber: 1,
        color: 'WHITE',
        san: 'e4',
        fen: 'fen1',
        modelId: 'model-a',
        promptVersion: null,
        promptHash: null,
        promptTokens: null,
        completionTokens: null,
        costUsd: null,
        responseTimeMs: 1200,
        retryCount: 1,
        isOverride: false,
        evaluationCp: 12,
        evaluationMate: null,
        createdAt: '2026-03-06T10:01:10Z',
      },
    ]);
    component.chatMessages.set([
      {
        id: 'c1',
        moveNumber: 1,
        senderModel: 'model-a',
        senderColor: 'WHITE',
        message: 'Good luck.',
        createdAt: '2026-03-06T10:01:12Z',
      },
    ]);

    const timelineEvents = component.timelineEvents();
    const defaultFeedEvents = component.feedEvents();

    expect(timelineEvents.map((event) => event.type)).toEqual(['result', 'chat', 'move', 'start']);
    expect(defaultFeedEvents.map((event) => event.type)).toEqual(['result', 'move', 'start']);
    expect(defaultFeedEvents[1].detail).toContain('after 1 retry');
    expect(defaultFeedEvents[2].label).toBe('Game Started');

    component.includeChatInFeed.set(true);

    const chatEnabledFeedEvents = component.feedEvents();

    expect(chatEnabledFeedEvents.map((event) => event.type)).toEqual([
      'result',
      'chat',
      'move',
      'start',
    ]);
    expect(chatEnabledFeedEvents[1].detail).toContain('Alpha: Good luck.');
  });

  it('shows a snackbar when pausing the game fails', () => {
    const { component, snackbar } = createComponent({
      api: {
        pauseGame: () => throwError(() => new Error('pause failed')),
      },
    });

    component.pauseGame();

    expect(snackbar.open).toHaveBeenCalledWith('Failed to pause the game.', 'Dismiss', {
      duration: 4000,
      horizontalPosition: 'right',
      verticalPosition: 'top',
    });
  });

  it('shows the server reason when resuming fails', () => {
    const { component, snackbar } = createComponent({
      api: {
        startGame: () => throwError(() => ({ error: { error: 'Game has already finished' } })),
      },
    });

    component.resumeGame();

    expect(snackbar.open).toHaveBeenCalledWith(
      'Game has already finished',
      'Dismiss',
      expect.any(Object),
    );
  });

  it('keeps the pause reason from status updates and clears it on resume', () => {
    const { component } = createComponent();
    component.game.set({
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
      currentFen: 'fen',
      bracketRound: null,
      bracketPosition: null,
      seriesId: null,
      seriesGameNumber: 1,
      seriesBestOf: 1,
      totalCostUsd: 0,
      createdAt: '2026-03-06T10:00:00Z',
      startedAt: '2026-03-06T10:01:00Z',
      completedAt: null,
      moves: [],
      chatMessages: [],
    });

    component.handleWsMessage({
      type: 'gameStatus',
      gameId: 'g-1',
      status: 'PAUSED',
      detail: 'OpenRouter rejected the API key (HTTP 401)',
    });
    expect(component.game()?.statusDetail).toBe('OpenRouter rejected the API key (HTTP 401)');

    component.handleWsMessage({ type: 'gameStatus', gameId: 'g-1', status: 'IN_PROGRESS' });
    expect(component.game()?.statusDetail).toBeNull();
  });

  it('detects when the active side is human-controlled', () => {
    const { component } = createComponent();
    component.game.set({
      id: 'g-1',
      tournamentId: 't-1',
      whitePlayerName: 'Human',
      whiteControlType: 'HUMAN',
      whiteModelId: null,
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
      createdAt: '2026-03-06T10:00:00Z',
      startedAt: '2026-03-06T10:01:00Z',
      completedAt: null,
      moves: [],
      chatMessages: [],
    });

    expect(component.isAwaitingHumanMove()).toBe(true);
    expect(component.activeHumanPlayerLabel()).toBe('Human');
  });

  it('submits a human SAN move through the API', () => {
    const submitHumanMove = vi.fn(() => of(void 0));
    const { component } = createComponent({
      api: {
        submitHumanMove,
      },
    });

    component.game.set({
      id: 'g-1',
      tournamentId: 't-1',
      whitePlayerName: 'Human',
      whiteControlType: 'HUMAN',
      whiteModelId: null,
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
      createdAt: '2026-03-06T10:00:00Z',
      startedAt: '2026-03-06T10:01:00Z',
      completedAt: null,
      moves: [],
      chatMessages: [],
    });
    component.handleWsMessage({
      type: 'gameStatus',
      gameId: 'g-1',
      status: 'IN_PROGRESS',
      activeColor: 'WHITE',
    });
    (component as any).gameId = 'g-1';
    component.humanMove = 'e4';
    component.humanMessage = 'gl hf';

    component.submitHumanMove();

    expect(submitHumanMove).toHaveBeenCalledWith('g-1', 'e4', 'gl hf');
    expect(component.humanMove).toBe('');
    expect(component.humanMessage).toBe('');
  });

  it('summarizes failed attempts per ply in the feed', () => {
    const { component } = createComponent();
    component.game.set(gameFixture({ status: 'FORFEIT', result: 'BLACK_FORFEIT' }));
    component.moves.set([moveFixture({ retryCount: 2 })]);
    component.attempts.set([
      attemptFixture({ attemptNumber: 1, outcome: 'ILLEGAL_MOVE', proposedMove: 'Qxh7' }),
      attemptFixture({ attemptNumber: 2, outcome: 'PARSE_FAILURE' }),
      attemptFixture({ attemptNumber: 3, outcome: 'ACCEPTED', proposedMove: 'e4' }),
      attemptFixture({
        color: 'BLACK',
        outcome: 'ILLEGAL_MOVE',
        proposedMove: 'Ke7',
        createdAt: '2026-03-06T10:02:00Z',
      }),
      attemptFixture({
        color: 'BLACK',
        attemptNumber: 2,
        outcome: 'EMPTY_RESPONSE',
        createdAt: '2026-03-06T10:02:30Z',
      }),
    ]);

    const events = component.feedEvents();
    const move = events.find((event) => event.type === 'move');
    const unplayed = events.find((event) => event.type === 'attempts');

    expect(move?.detail).toBe('Alpha after 2 failed attempts: illegal Qxh7, unparseable answer');
    expect(unplayed?.label).toBe('Black move 1 not played');
    expect(unplayed?.detail).toBe('Beta: 2 failed attempts: illegal Ke7, empty answer');
  });

  it('loads the attempts together with the game', () => {
    const getGameAttempts = vi.fn(() =>
      of([attemptFixture({ outcome: 'TRUNCATED', proposedMove: null })]),
    );
    const { component } = createComponent({
      api: {
        getGame: () => of(gameFixture({ status: 'PAUSED' })),
        getGameAttempts,
      },
    });
    (component as any).gameId = 'g-1';

    component.loadGame();

    expect(getGameAttempts).toHaveBeenCalledWith('g-1');
    expect(component.attempts()).toHaveLength(1);
    expect(component.feedEvents().find((event) => event.type === 'attempts')?.detail).toBe(
      'Alpha: 1 failed attempt: cut off at the token limit',
    );
  });

  it('names the rejected move in the live retry text', () => {
    const { component } = createComponent();

    component.handleWsMessage({
      type: 'retry',
      gameId: 'g-1',
      color: 'WHITE',
      attemptNumber: 2,
      reason: 'Illegal move attempted: Qxh7',
      outcome: 'ILLEGAL_MOVE',
      proposedMove: 'Qxh7',
    });
    expect(component.retryInfo()).toBe('WHITE attempt 2: illegal move Qxh7');

    component.handleWsMessage({
      type: 'retry',
      gameId: 'g-1',
      color: 'BLACK',
      attemptNumber: 1,
      reason: 'Failed to parse response as JSON',
      outcome: 'PARSE_FAILURE',
    });
    expect(component.retryInfo()).toBe('BLACK attempt 1: Failed to parse response as JSON');
  });
});

function gameFixture(overrides: Partial<Game> = {}): Game {
  return {
    id: 'g-1',
    tournamentId: null,
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
    currentFen: 'fen',
    bracketRound: null,
    bracketPosition: null,
    seriesId: null,
    seriesGameNumber: 1,
    seriesBestOf: 1,
    totalCostUsd: 0,
    createdAt: '2026-03-06T10:00:00Z',
    startedAt: '2026-03-06T10:01:00Z',
    completedAt: '2026-03-06T10:05:00Z',
    moves: [],
    chatMessages: [],
    ...overrides,
  };
}

function moveFixture(overrides: Partial<Move> = {}): Move {
  return {
    id: 'm1',
    moveNumber: 1,
    color: 'WHITE',
    san: 'e4',
    fen: 'fen1',
    modelId: 'model-a',
    promptVersion: null,
    promptHash: null,
    promptTokens: null,
    completionTokens: null,
    costUsd: null,
    responseTimeMs: 1200,
    retryCount: 0,
    isOverride: false,
    createdAt: '2026-03-06T10:01:10Z',
    ...overrides,
  };
}

function attemptFixture(overrides: Partial<MoveAttempt> = {}): MoveAttempt {
  return {
    id: `a-${overrides.color ?? 'WHITE'}-${overrides.attemptNumber ?? 1}`,
    moveNumber: 1,
    color: 'WHITE',
    attemptNumber: 1,
    modelId: 'model-a',
    outcome: 'ILLEGAL_MOVE',
    proposedMove: null,
    promptTokens: null,
    completionTokens: null,
    reasoningTokens: null,
    costUsd: null,
    responseTimeMs: null,
    finishReason: null,
    errorMessage: null,
    createdAt: '2026-03-06T10:01:05Z',
    ...overrides,
  };
}
