import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { EMPTY, Subject, of, throwError } from 'rxjs';
import { GameView } from './game-view';
import { GameInfoBar } from '../../components/game-info-bar/game-info-bar';
import { REASONING_EFFORTS } from '../../utils/reasoning-effort';
import { GameTurnStateService } from '../../services/game-turn-state.service';
import { ApiService } from '../../services/api.service';
import { WebSocketService } from '../../services/websocket.service';
import { Game, Move, MoveAttempt, MoveWsMessage, WsMessage } from '../../models/tournament.model';

const START_FEN = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1';
const AFTER_E4 = 'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1';
const AFTER_E5 = 'rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2';

function gameWith(overrides: Partial<Game> = {}): Game {
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
    currentFen: START_FEN,
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
    ...overrides,
  };
}

function storedMove(moveNumber: number, color: 'WHITE' | 'BLACK', san: string, fen: string): Move {
  return {
    id: `m-${moveNumber}-${color}`,
    moveNumber,
    color,
    san,
    fen,
    modelId: color === 'WHITE' ? 'model-a' : 'model-b',
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

function liveMove(
  moveNumber: number,
  color: 'WHITE' | 'BLACK',
  san: string,
  fen: string,
  extra: Partial<MoveWsMessage> = {},
): MoveWsMessage {
  return {
    type: 'move',
    gameId: 'g-1',
    moveNumber,
    color,
    san,
    fen,
    modelId: color === 'WHITE' ? 'model-a' : 'model-b',
    ...extra,
  };
}

/** A component wired for ngOnInit, whose game load stays in flight until `gameLoad` emits. */
function createSpectator() {
  const gameLoad = new Subject<Game>();
  const messages = new Subject<WsMessage>();
  const reconnected = new Subject<void>();
  const getGame = vi.fn(() => gameLoad);
  const api = {
    getGame,
    getSystemStatus: () => EMPTY,
    getGameAttempts: () => of([]),
    getModels: () => of({ data: [] }),
  };
  const ws = {
    connect: vi.fn(),
    subscribeToGame: vi.fn(),
    unsubscribeFromGame: vi.fn(),
    getMessages: () => messages,
    reconnected: () => reconnected,
  };
  const route = { snapshot: { paramMap: { get: () => 'g-1' } } };
  const turnState = new GameTurnStateService();
  turnState.startTicker = vi.fn();
  turnState.stopTicker = vi.fn();
  const component = new GameView(
    route as any,
    api as any,
    ws as any,
    { navigate: vi.fn() } as any,
    { open: vi.fn() } as any,
    turnState,
  );
  return { component, gameLoad, messages, reconnected, getGame };
}

function createComponent(overrides?: { api?: Partial<any>; snackbar?: Partial<any> }) {
  const api = {
    getGame: () => EMPTY,
    getTournament: () => EMPTY,
    pauseGame: () => of(void 0),
    startGame: () => of(void 0),
    getGameAttempts: () => of([]),
    getModels: () => of({ data: [] }),
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

  it('shows the stored evaluation of the viewed move and unknown, not 0.0, where there is none', () => {
    const { component } = createComponent();
    component.game.set(gameWith({ status: 'COMPLETED', resultReason: 'RESIGNATION' }));
    component.moves.set([
      { ...storedMove(1, 'WHITE', 'e4', AFTER_E4), evaluationCp: 35, evaluationMate: null },
      { ...storedMove(1, 'BLACK', 'e5', AFTER_E5), evaluationCp: null, evaluationMate: null },
    ]);

    component.goLast();
    expect(component.currentEvaluation()).toEqual({ cp: null, mate: null, checkmated: null });

    component.goPrev();
    expect(component.currentEvaluation()).toEqual({ cp: 35, mate: null, checkmated: null });

    component.goFirst();
    expect(component.currentEvaluation()).toEqual({ cp: null, mate: null, checkmated: null });
  });

  it('shows the final position of a checkmate as a win for the mating side', () => {
    const { component } = createComponent();
    const foolsMate = 'rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3';
    component.game.set(
      gameWith({ status: 'COMPLETED', result: 'BLACK_WINS', resultReason: 'CHECKMATE' }),
    );
    component.moves.set([
      storedMove(1, 'WHITE', 'f3', 'rnbqkbnr/pppppppp/8/8/8/5P2/PPPPP1PP/RNBQKBNR b KQkq - 0 1'),
      storedMove(1, 'BLACK', 'e5', 'rnbqkbnr/pppp1ppp/8/4p3/8/5P2/PPPPP1PP/RNBQKBNR w KQkq - 0 2'),
      storedMove(2, 'WHITE', 'g4', 'rnbqkbnr/pppp1ppp/8/4p3/6P1/5P2/PPPPP2P/RNBQKBNR b KQkq - 0 2'),
      storedMove(2, 'BLACK', 'Qh4#', foolsMate),
    ]);
    component.goLast();

    expect(component.currentEvaluation().checkmated).toBe('WHITE');
    expect(component.lastMove()).toEqual({ from: 'd8', to: 'h4' });
  });

  it('derives the last-move squares of the viewed move, and none before the first move', () => {
    const { component } = createComponent();
    component.game.set(gameWith({ currentFen: AFTER_E5 }));

    expect(component.lastMove()).toBeNull();

    component.moves.set([
      storedMove(1, 'WHITE', 'e4', AFTER_E4),
      storedMove(1, 'BLACK', 'e5', AFTER_E5),
    ]);
    component.goLast();
    expect(component.lastMove()).toEqual({ from: 'e7', to: 'e5' });

    component.goPrev();
    expect(component.lastMove()).toEqual({ from: 'e2', to: 'e4' });

    component.goFirst();
    expect(component.lastMove()).toBeNull();
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
    expect(defaultFeedEvents[1].detail).toBe('Alpha');
    expect(defaultFeedEvents[1].attemptSummary).toBe('1 retry');
    expect(defaultFeedEvents[0].attemptSummary).toBeNull();
    expect(defaultFeedEvents[2].attemptSummary).toBeNull();
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
      statusRevision: 1,
    });
    expect(component.game()?.statusDetail).toBe('OpenRouter rejected the API key (HTTP 401)');

    component.handleWsMessage({
      type: 'gameStatus',
      gameId: 'g-1',
      status: 'IN_PROGRESS',
      statusRevision: 2,
    });
    expect(component.game()?.statusDetail).toBeNull();
  });

  it('updates the chat mode from live turn updates', () => {
    const { component } = createComponent();
    component.tournamentChatMode.set('OPTIONAL');

    component.handleWsMessage({ type: 'gameStatus', gameId: 'g-1', status: 'PAUSED' });
    expect(component.tournamentChatMode()).toBe('OPTIONAL');

    component.handleWsMessage({
      type: 'gameStatus',
      gameId: 'g-1',
      status: 'IN_PROGRESS',
      activeColor: 'WHITE',
      tournamentChatMode: 'MANDATORY',
    });
    expect(component.tournamentChatMode()).toBe('MANDATORY');
  });

  it('keeps the configured chat mode over successive turns of a mixed-template game', () => {
    // MANDATORY tournament, White with a legacy template (effectively OPTIONAL): every turn update carries the
    // configured mode, so the label does not flip between the sides' turns.
    const { component } = createComponent();
    for (const activeColor of ['WHITE', 'BLACK', 'WHITE', 'BLACK'] as const) {
      component.handleWsMessage({
        type: 'gameStatus',
        gameId: 'g-1',
        status: 'IN_PROGRESS',
        activeColor,
        tournamentChatMode: 'MANDATORY',
      });
      expect(component.tournamentChatMode()).toBe('MANDATORY');
    }
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

    expect(move?.detail).toBe('Alpha');
    expect(move?.attemptSummary).toBe('2 failed attempts: illegal Qxh7, unparseable answer');
    expect(unplayed?.label).toBe('Black move 1 not played');
    expect(unplayed?.detail).toBe('Beta');
    expect(unplayed?.attemptSummary).toBe('2 failed attempts: illegal Ke7, empty answer');
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
    const unplayed = component.feedEvents().find((event) => event.type === 'attempts');
    expect(unplayed?.detail).toBe('Alpha');
    expect(unplayed?.attemptSummary).toBe('answer cut off at the token limit');
  });

  it('shows no move countdown when the tournament has no time limit per move', () => {
    const { component } = createComponent({
      api: {
        getGame: () =>
          of(gameFixture({ status: 'IN_PROGRESS', tournamentId: 't-1', currentFen: START_FEN })),
        getTournament: () => of({ id: 't-1', moveTimeoutSeconds: null, maxOutputTokens: 4096 }),
      },
    });
    (component as any).gameId = 'g-1';

    component.loadGame();

    expect(component.noMoveTimeLimit()).toBe(true);
    expect(component.moveTimerSeconds()).toBeNull();
    expect(component.activeColor()).toBe('WHITE');
  });

  it('counts down the move time when the tournament has a limit', () => {
    const { component } = createComponent({
      api: {
        getGame: () =>
          of(gameFixture({ status: 'IN_PROGRESS', tournamentId: 't-1', currentFen: START_FEN })),
        getTournament: () => of({ id: 't-1', moveTimeoutSeconds: 45, maxOutputTokens: 4096 }),
      },
    });
    (component as any).gameId = 'g-1';

    component.loadGame();

    expect(component.noMoveTimeLimit()).toBe(false);
    expect(component.moveTimerSeconds()).not.toBeNull();
  });

  it('ignores an outdated attempts response that arrives after a newer one', () => {
    const responses: Array<Subject<MoveAttempt[]>> = [];
    const { component } = createComponent({
      api: {
        getGame: () => of(gameFixture({ tournamentId: 't-1', status: 'PAUSED' })),
        getTournament: () => of({ maxOutputTokens: 4096 }),
        getGameAttempts: () => {
          const response = new Subject<MoveAttempt[]>();
          responses.push(response);
          return response;
        },
      },
    });
    (component as any).gameId = 'g-1';
    component.tournamentMaxOutputTokens.set(4096);
    component.game.set(gameFixture({ tournamentId: 't-1', status: 'IN_PROGRESS' }));

    // Initial load, then a pause event triggers a second request.
    component.loadAttempts();
    component.handleWsMessage({
      type: 'gameStatus',
      gameId: 'g-1',
      status: 'PAUSED',
      detail: 'Answer cut off',
      statusRevision: 1,
    });
    expect(responses).toHaveLength(2);

    // They complete in reverse order: the newer one (with the cut-off answer) first.
    responses[1].next([attemptFixture({ outcome: 'TRUNCATED' })]);
    responses[0].next([]);

    expect(component.attempts().map((attempt) => attempt.outcome)).toEqual(['TRUNCATED']);
    expect(component.truncationPauseLimit()).toBe(4096);
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

  it('offers the raise-limit control only when the latest attempt was cut off', () => {
    const { component } = createComponent();
    component.tournamentMaxOutputTokens.set(4096);
    component.game.set(
      gameFixture({ tournamentId: 't-1', status: 'PAUSED', statusDetail: 'Answer cut off' }),
    );
    component.moves.set([moveFixture()]);

    // Black's move 1 is next; its latest answer was cut off.
    component.attempts.set([
      attemptFixture({ color: 'BLACK', outcome: 'ILLEGAL_MOVE', proposedMove: 'Ke7' }),
      attemptFixture({
        color: 'BLACK',
        attemptNumber: 2,
        outcome: 'TRUNCATED',
        createdAt: '2026-03-06T10:02:00Z',
      }),
    ]);
    expect(component.truncationPauseLimit()).toBe(4096);

    // A later provider failure for the same ply caused this pause instead.
    component.attempts.update((list) => [
      ...list,
      attemptFixture({
        color: 'BLACK',
        attemptNumber: 3,
        outcome: 'PROVIDER_FATAL',
        createdAt: '2026-03-06T10:03:00Z',
      }),
    ]);
    expect(component.truncationPauseLimit()).toBeNull();

    // A cut-off answer for an earlier ply that was later played does not count.
    component.attempts.set([attemptFixture({ outcome: 'TRUNCATED' })]);
    expect(component.truncationPauseLimit()).toBeNull();

    // Not paused: no control.
    component.attempts.set([attemptFixture({ color: 'BLACK', outcome: 'TRUNCATED' })]);
    expect(component.truncationPauseLimit()).toBe(4096);
    component.game.update((g) => (g ? { ...g, status: 'IN_PROGRESS' } : g));
    expect(component.truncationPauseLimit()).toBeNull();
  });

  it('saves the raised limit, then resumes the game', () => {
    const calls: string[] = [];
    const updateTournament = vi.fn(() => {
      calls.push('update');
      return of({ id: 't-1', maxOutputTokens: 8192 });
    });
    const startGame = vi.fn(() => {
      calls.push('start');
      return of(void 0);
    });
    const { component, snackbar } = createComponent({ api: { updateTournament, startGame } });
    (component as any).gameId = 'g-1';
    component.game.set(
      gameFixture({ tournamentId: 't-1', status: 'PAUSED', statusDetail: 'Answer cut off' }),
    );

    component.saveLimitAndResume(8192);

    expect(updateTournament).toHaveBeenCalledWith('t-1', { maxOutputTokens: 8192 });
    expect(startGame).toHaveBeenCalledWith('g-1');
    expect(calls).toEqual(['update', 'start']);
    expect(component.tournamentMaxOutputTokens()).toBe(8192);
    expect(component.savingLimit()).toBe(false);
    expect(snackbar.open).not.toHaveBeenCalled();
  });

  it('shows the server error and does not resume when saving the limit fails', () => {
    const startGame = vi.fn(() => of(void 0));
    const { component, snackbar } = createComponent({
      api: {
        updateTournament: () =>
          throwError(() => ({
            status: 400,
            error: { error: 'maxOutputTokens must be between 128 and 32768' },
          })),
        startGame,
      },
    });
    (component as any).gameId = 'g-1';
    component.game.set(
      gameFixture({ tournamentId: 't-1', status: 'PAUSED', statusDetail: 'Answer cut off' }),
    );

    component.saveLimitAndResume(99999);

    expect(startGame).not.toHaveBeenCalled();
    expect(snackbar.open).toHaveBeenCalledWith(
      'maxOutputTokens must be between 128 and 32768',
      'Dismiss',
      expect.any(Object),
    );
    expect(component.savingLimit()).toBe(false);
  });

  it('offers the cut-off side’s reasoning effort with the levels its model supports', () => {
    const getModels = vi.fn(() =>
      of({
        data: [
          { id: 'meta/muse-ember-x', reasoningEfforts: ['low'] },
          {
            id: 'meta/muse-ember',
            name: 'Meta: Muse Ember',
            reasoningEfforts: ['max', 'xhigh', 'high', 'medium', 'low', 'minimal'],
          },
        ],
      }),
    );
    const { component } = createComponent({
      api: {
        getModels,
        getGameAttempts: () =>
          of([
            attemptFixture({
              color: 'BLACK',
              modelId: 'meta/muse-ember',
              outcome: 'TRUNCATED',
              reasoningEffort: null,
            }),
          ]),
      },
    });
    (component as any).gameId = 'g-1';
    component.tournamentMaxOutputTokens.set(30000);
    component.tournamentReasoningEffort.set('high');
    component.game.set(
      gameFixture({
        tournamentId: 't-1',
        status: 'PAUSED',
        statusDetail: 'Ember was cut off',
        blackPlayerName: 'Ember',
        blackModelId: 'meta/muse-ember',
        blackParticipantId: 'p-2',
        blackReasoningEffort: 'high',
      }),
    );
    component.moves.set([moveFixture()]);

    component.loadAttempts();

    expect(getModels).toHaveBeenCalledWith({
      featuredOnly: false,
      q: 'meta/muse-ember',
      limit: 0,
    });
    expect(component.truncationEffort()).toEqual({
      participantId: 'p-2',
      playerName: 'Ember',
      current: 'high',
      tournamentLevel: 'high',
      options: ['minimal', 'low', 'medium', 'high', 'xhigh', 'max'],
      fromCatalog: true,
    });

    // Looked up once per model.
    component.loadAttempts();
    expect(getModels).toHaveBeenCalledTimes(1);
  });

  it.each([false, true])(
    'offers normalized all-level models in the pause banner (mandatory: %s)',
    (mandatory) => {
      const { component } = createComponent({
        api: {
          getModels: () =>
            of({
              data: [
                {
                  id: 'example/all-levels',
                  reasoningEfforts: [...REASONING_EFFORTS],
                  reasoningMandatory: mandatory,
                },
              ],
            }),
          getGameAttempts: () => of([attemptFixture({ color: 'BLACK', outcome: 'TRUNCATED' })]),
        },
      });
      component.game.set(
        gameFixture({
          tournamentId: 't-1',
          status: 'PAUSED',
          statusDetail: 'Cut off',
          blackParticipantId: 'p-2',
          blackModelId: 'example/all-levels',
        }),
      );
      component.moves.set([moveFixture()]);
      component.loadAttempts();
      expect(component.truncationEffort()?.options).toEqual(
        mandatory ? REASONING_EFFORTS.slice(1) : REASONING_EFFORTS,
      );
      const bar = new GameInfoBar();
      bar.game = component.game()!;
      bar.truncationLimit = component.truncationPauseLimit();
      bar.truncationEffort = component.truncationEffort();
      expect(bar.showEffortSelect()).toBe(true);
      expect(bar.newReasoningEffort).toBe('low');
    },
  );

  it('refreshes effort settings on pause before offering a suggestion', () => {
    const refreshedGame = new Subject<Game>();
    const refreshedTournament = new Subject<any>();
    const getGame = vi.fn(() => refreshedGame);
    const getTournament = vi.fn(() => refreshedTournament);
    const updateTournament = vi.fn();
    const { component } = createComponent({
      api: {
        getGame,
        getTournament,
        updateTournament,
        getGameAttempts: () => of([attemptFixture({ color: 'BLACK', outcome: 'TRUNCATED' })]),
        getModels: () =>
          of({ data: [{ id: 'model-b', reasoningEfforts: [...REASONING_EFFORTS] }] }),
      },
    });
    (component as any).gameId = 'g-1';
    const original = gameFixture({
      tournamentId: 't-1',
      status: 'IN_PROGRESS',
      blackParticipantId: 'p-2',
      blackModelId: 'model-b',
      blackReasoningEffort: 'high',
    });
    component.game.set(original);
    component.moves.set([moveFixture()]);
    component.tournamentReasoningEffort.set('high');
    component.handleWsMessage({
      type: 'gameStatus',
      gameId: 'g-1',
      status: 'PAUSED',
      detail: 'Cut off',
      statusRevision: 1,
    });
    expect(getGame).toHaveBeenCalledWith('g-1');
    expect(getTournament).toHaveBeenCalledWith('t-1');
    expect(component.truncationEffort()).toBeNull();
    expect(component.truncationPauseLimit()).toBeNull();
    component.saveLimitAndResume(8192, 'low');
    expect(updateTournament).not.toHaveBeenCalled();

    // The participant changed elsewhere while the game view stayed open.
    refreshedGame.next({ ...original, status: 'PAUSED', blackReasoningEffort: 'minimal' });
    refreshedGame.complete();
    expect(component.truncationEffort()).toBeNull();
    refreshedTournament.next({ reasoningEffort: 'minimal', maxOutputTokens: 12000 });
    refreshedTournament.complete();
    expect(component.truncationEffort()?.current).toBe('minimal');
    expect(component.truncationEffort()?.tournamentLevel).toBe('minimal');
    expect(component.truncationPauseLimit()).toBe(12000);
    const bar = new GameInfoBar();
    bar.truncationEffort = component.truncationEffort();
    expect(bar.newReasoningEffort).toBe('minimal');
  });

  it.each(['game', 'tournament'])(
    'ignores an older %s reload response after refreshing pause settings',
    (delayed) => {
      const oldGame = new Subject<Game>();
      const oldTournament = new Subject<any>();
      const original = gameFixture({
        tournamentId: 't-1',
        status: 'IN_PROGRESS',
        statusRevision: 0,
        blackParticipantId: 'p-2',
        blackReasoningEffort: 'high',
        moves: [moveFixture()],
      });
      const refreshed = {
        ...original,
        status: 'PAUSED',
        statusRevision: 1,
        blackReasoningEffort: 'minimal',
      } as Game;
      const getGame = vi
        .fn()
        .mockReturnValueOnce(delayed === 'game' ? oldGame : of(original))
        .mockReturnValueOnce(of(refreshed));
      const getTournament = vi.fn();
      const currentTournament = { reasoningEffort: 'minimal', maxOutputTokens: 12000 };
      if (delayed === 'game') {
        getTournament.mockReturnValueOnce(of(currentTournament)).mockReturnValueOnce(oldTournament);
      } else {
        getTournament.mockReturnValueOnce(oldTournament).mockReturnValueOnce(of(currentTournament));
      }
      const { component } = createComponent({
        api: {
          getGame,
          getTournament,
          getGameAttempts: () => of([attemptFixture({ color: 'BLACK', outcome: 'TRUNCATED' })]),
        },
      });
      component.game.set(original);
      component.moves.set(original.moves!);
      component.loadGame();
      component.handleWsMessage({
        type: 'gameStatus',
        gameId: 'g-1',
        status: 'PAUSED',
        detail: 'Cut off',
        statusRevision: 1,
      });
      // Both the old game snapshot and its tournament response carry the previous effort.
      oldGame.next({ ...original, status: 'PAUSED', statusDetail: 'Cut off', statusRevision: 1 });
      oldGame.complete();
      oldTournament.next({ reasoningEffort: 'high', maxOutputTokens: 4096 });
      oldTournament.complete();
      expect(component.game()?.blackReasoningEffort).toBe('minimal');
      expect(component.tournamentReasoningEffort()).toBe('minimal');
      expect(component.truncationEffort()?.current).toBe('minimal');
      expect(component.truncationPauseLimit()).toBe(12000);
    },
  );

  it('keeps stale pause controls hidden if refreshing settings fails', () => {
    const { component, snackbar } = createComponent({
      api: {
        getGame: () => throwError(() => new Error('unavailable')),
        getTournament: () => of({ reasoningEffort: 'minimal' }),
      },
    });
    component.game.set(gameFixture({ tournamentId: 't-1', status: 'IN_PROGRESS' }));
    component.handleWsMessage({
      type: 'gameStatus',
      gameId: 'g-1',
      status: 'PAUSED',
      detail: 'Cut off',
      statusRevision: 1,
    });
    component.attempts.set([attemptFixture({ outcome: 'TRUNCATED' })]);
    expect(component.truncationPauseLimit()).toBeNull();
    expect(component.truncationEffort()).toBeNull();
    expect(snackbar.open).toHaveBeenCalledWith(
      'Failed to refresh the paused game settings.',
      'Dismiss',
      expect.any(Object),
    );
  });

  it('offers every level while the catalog cannot tell, and nothing for a side without participant', () => {
    const { component } = createComponent({
      api: { getModels: () => throwError(() => new Error('offline')) },
    });
    component.tournamentMaxOutputTokens.set(4096);
    component.game.set(
      gameFixture({
        tournamentId: 't-1',
        status: 'PAUSED',
        statusDetail: 'Cut off',
        blackParticipantId: 'p-2',
      }),
    );
    component.moves.set([moveFixture()]);
    component.attempts.set([attemptFixture({ color: 'BLACK', outcome: 'TRUNCATED' })]);

    expect(component.truncationEffort()?.options).toHaveLength(7);
    expect(component.truncationEffort()?.fromCatalog).toBe(false);

    component.game.update((g) => (g ? { ...g, blackParticipantId: null } : g));
    expect(component.truncationEffort()).toBeNull();
    expect(component.truncationPauseLimit()).toBe(4096);
  });

  it('saves the limit and the participant’s reasoning effort, then resumes the game', () => {
    const calls: string[] = [];
    const updateTournament = vi.fn(() => {
      calls.push('tournament');
      return of({ id: 't-1', maxOutputTokens: 32768 });
    });
    const updateParticipant = vi.fn(() => {
      calls.push('participant');
      return of({ id: 'p-2', reasoningEffort: 'low' });
    });
    const startGame = vi.fn(() => {
      calls.push('start');
      return of(void 0);
    });
    const { component, snackbar } = createComponent({
      api: { updateTournament, updateParticipant, startGame },
    });
    (component as any).gameId = 'g-1';
    component.game.set(
      gameFixture({
        tournamentId: 't-1',
        status: 'PAUSED',
        statusDetail: 'Cut off',
        blackParticipantId: 'p-2',
        blackReasoningEffort: null,
      }),
    );
    component.moves.set([moveFixture()]);
    component.attempts.set([attemptFixture({ color: 'BLACK', outcome: 'TRUNCATED' })]);

    component.saveTruncationFixAndResume({ maxOutputTokens: 32768, reasoningEffort: 'low' });

    expect(updateTournament).toHaveBeenCalledWith('t-1', { maxOutputTokens: 32768 });
    expect(updateParticipant).toHaveBeenCalledWith('t-1', 'p-2', { reasoningEffort: 'low' });
    expect(startGame).toHaveBeenCalledWith('g-1');
    expect(calls).toEqual(['tournament', 'participant', 'start']);
    expect(component.game()?.blackReasoningEffort).toBe('low');
    expect(component.savingLimit()).toBe(false);
    expect(snackbar.open).not.toHaveBeenCalled();
  });

  it('does not resume when saving the reasoning effort fails, and skips it when none was offered', () => {
    const startGame = vi.fn(() => of(void 0));
    const updateParticipant = vi.fn(() =>
      throwError(() => ({ status: 400, error: { error: 'Invalid reasoningEffort: x' } })),
    );
    const { component, snackbar } = createComponent({
      api: {
        updateTournament: () => of({ id: 't-1', maxOutputTokens: 8192 }),
        updateParticipant,
        startGame,
      },
    });
    (component as any).gameId = 'g-1';
    component.game.set(
      gameFixture({
        tournamentId: 't-1',
        status: 'PAUSED',
        statusDetail: 'Cut off',
        blackParticipantId: 'p-2',
      }),
    );
    component.moves.set([moveFixture()]);
    component.attempts.set([attemptFixture({ color: 'BLACK', outcome: 'TRUNCATED' })]);

    component.saveTruncationFixAndResume({ maxOutputTokens: 8192, reasoningEffort: 'minimal' });
    expect(startGame).not.toHaveBeenCalled();
    expect(snackbar.open).toHaveBeenCalledWith(
      'Invalid reasoningEffort: x',
      'Dismiss',
      expect.any(Object),
    );

    updateParticipant.mockClear();
    component.saveTruncationFixAndResume({ maxOutputTokens: 8192 });
    expect(updateParticipant).not.toHaveBeenCalled();
    expect(startGame).toHaveBeenCalledWith('g-1');
  });

  it('shows the start position when the history is rewound to before the first move', () => {
    const { component } = createComponent();
    component.game.set(gameWith({ currentFen: AFTER_E5 }));
    component.moves.set([
      storedMove(1, 'WHITE', 'e4', AFTER_E4),
      storedMove(1, 'BLACK', 'e5', AFTER_E5),
    ]);
    component.currentMoveIndex.set(1);
    expect(component.currentFen()).toBe(AFTER_E5);

    component.goFirst();

    expect(component.currentFen()).toBe(START_FEN);
    // The side to move still comes from the live position, not the one being reviewed.
    expect(component.activeColor()).toBe('WHITE');

    component.goToMove(0);
    expect(component.currentFen()).toBe(AFTER_E4);
    expect(component.activeColor()).toBe('WHITE');
  });

  it('shows the game position when no moves have been played', () => {
    const { component } = createComponent();
    component.game.set(gameWith({ currentFen: START_FEN }));

    expect(component.currentFen()).toBe(START_FEN);
  });

  it('merges live moves that arrive while the game is still loading', () => {
    const { component, gameLoad, messages } = createSpectator();
    component.ngOnInit();

    messages.next(liveMove(1, 'WHITE', 'e4', AFTER_E4));
    messages.next(liveMove(1, 'BLACK', 'e5', AFTER_E5, { pgn: '1. e4 e5' }));
    messages.next({
      type: 'evaluationUpdate',
      gameId: 'g-1',
      moveNumber: 1,
      color: 'BLACK',
      evaluationCp: 25,
    });
    expect(component.moves()).toEqual([]);

    // The load was answered after e4 was stored but before e5.
    gameLoad.next(
      gameWith({
        currentFen: AFTER_E4,
        pgn: '1. e4',
        moves: [storedMove(1, 'WHITE', 'e4', AFTER_E4)],
      }),
    );

    expect(component.moves().map((move) => `${move.moveNumber}${move.color}:${move.san}`)).toEqual([
      '1WHITE:e4',
      '1BLACK:e5',
    ]);
    expect(component.moves()[0].id).toBe('m-1-WHITE');
    expect(component.moves()[1].evaluationCp).toBe(25);
    expect(component.currentMoveIndex()).toBe(1);
    expect(component.game()?.currentFen).toBe(AFTER_E5);
    expect(component.game()?.pgn).toBe('1. e4 e5');
  });

  it('orders loaded moves White first within a move number', () => {
    const { component, gameLoad } = createSpectator();
    component.ngOnInit();

    gameLoad.next(
      gameWith({
        currentFen: AFTER_E5,
        moves: [storedMove(1, 'BLACK', 'e5', AFTER_E5), storedMove(1, 'WHITE', 'e4', AFTER_E4)],
      }),
    );

    expect(component.moves().map((move) => move.san)).toEqual(['e4', 'e5']);
    expect(component.currentMoveIndex()).toBe(1);
  });

  it('ignores a live move that is already in the move list', () => {
    const { component, gameLoad, messages } = createSpectator();
    component.ngOnInit();
    gameLoad.next(
      gameWith({ currentFen: AFTER_E4, moves: [storedMove(1, 'WHITE', 'e4', AFTER_E4)] }),
    );

    messages.next(liveMove(1, 'WHITE', 'e4', AFTER_E4));
    messages.next(liveMove(1, 'BLACK', 'e5', AFTER_E5));
    messages.next(liveMove(1, 'BLACK', 'e5', AFTER_E5));

    expect(component.moves().map((move) => move.san)).toEqual(['e4', 'e5']);
    expect(component.currentMoveIndex()).toBe(1);
  });

  it('reloads the game after the live connection reconnects', () => {
    const { component, gameLoad, reconnected, getGame } = createSpectator();
    component.ngOnInit();
    gameLoad.next(gameWith({ moves: [storedMove(1, 'WHITE', 'e4', AFTER_E4)] }));

    reconnected.next();
    gameLoad.next(
      gameWith({
        currentFen: AFTER_E5,
        moves: [storedMove(1, 'WHITE', 'e4', AFTER_E4), storedMove(1, 'BLACK', 'e5', AFTER_E5)],
      }),
    );

    expect(getGame).toHaveBeenCalledTimes(2);
    expect(component.moves().map((move) => move.san)).toEqual(['e4', 'e5']);
    component.ngOnDestroy();
  });

  it('applies live moves directly once the latest load succeeded, even if an older load never answers', () => {
    const { component, getGame, messages, reconnected } = createSpectator();
    const initialLoad = new Subject<Game>();
    const reconnectLoad = new Subject<Game>();
    getGame.mockReturnValueOnce(initialLoad).mockReturnValueOnce(reconnectLoad);
    component.ngOnInit();

    reconnected.next();
    reconnectLoad.next(
      gameWith({ currentFen: AFTER_E4, moves: [storedMove(1, 'WHITE', 'e4', AFTER_E4)] }),
    );
    messages.next(liveMove(1, 'BLACK', 'e5', AFTER_E5));
    messages.next({
      type: 'evaluationUpdate',
      gameId: 'g-1',
      moveNumber: 1,
      color: 'BLACK',
      evaluationCp: -10,
    });

    expect(component.moves().map((move) => move.san)).toEqual(['e4', 'e5']);
    expect(component.moves()[1].evaluationCp).toBe(-10);
    expect(component.game()?.currentFen).toBe(AFTER_E5);
    expect(component.currentMoveIndex()).toBe(1);
    expect(initialLoad.observed).toBe(false);
  });

  it('shows the quality an evaluation update decides, for a live move and the summary', () => {
    const { component, gameLoad, messages } = createSpectator();
    component.ngOnInit();
    gameLoad.next(
      gameWith({
        currentFen: AFTER_E5,
        moves: [storedMove(1, 'WHITE', 'e4', AFTER_E4)],
        qualitySummary: null,
      }),
    );
    messages.next(liveMove(1, 'BLACK', 'e5', AFTER_E5));
    expect(component.moves()[1].quality).toBeUndefined();

    messages.next({
      type: 'evaluationUpdate',
      gameId: 'g-1',
      moveNumber: 1,
      color: 'BLACK',
      evaluationCp: 338,
      qualities: [
        {
          moveNumber: 1,
          color: 'BLACK',
          quality: {
            judgement: 'BLUNDER',
            centipawnLoss: 618,
            winChanceBefore: 0.7372,
            winChanceAfter: 0.2236,
            excluded: null,
          },
        },
      ],
      qualitySummary: {
        white: {
          analyzedMoves: 1,
          averageCentipawnLoss: 0,
          inaccuracies: 0,
          mistakes: 0,
          blunders: 0,
        },
        black: {
          analyzedMoves: 1,
          averageCentipawnLoss: 618,
          inaccuracies: 0,
          mistakes: 0,
          blunders: 1,
        },
      },
    });

    expect(component.moves()[1].evaluationCp).toBe(338);
    expect(component.moves()[1].quality?.judgement).toBe('BLUNDER');
    expect(component.game()?.qualitySummary?.black.blunders).toBe(1);
    const feedMove = component.timelineEvents().find((event) => event.label === 'Black played e5');
    expect(feedMove?.quality?.badge.glyph).toBe('??');
    expect(feedMove?.quality?.tooltip).toBe('Blunder: winning chances 74% → 22% (−6.2)');
    const whiteMove = component.timelineEvents().find((event) => event.label === 'White played e4');
    expect(whiteMove?.quality).toBeNull();
    component.ngOnDestroy();
  });

  it('keeps the summary when an evaluation update carries none', () => {
    const { component, gameLoad, messages } = createSpectator();
    component.ngOnInit();
    const summary = {
      white: {
        analyzedMoves: 1,
        averageCentipawnLoss: 12,
        inaccuracies: 0,
        mistakes: 0,
        blunders: 0,
      },
      black: {
        analyzedMoves: 0,
        averageCentipawnLoss: null,
        inaccuracies: 0,
        mistakes: 0,
        blunders: 0,
      },
    };
    gameLoad.next(
      gameWith({ moves: [storedMove(1, 'WHITE', 'e4', AFTER_E4)], qualitySummary: summary }),
    );

    messages.next({ type: 'evaluationUpdate', gameId: 'g-1', moveNumber: 1, color: 'WHITE' });

    expect(component.game()?.qualitySummary).toEqual(summary);
    component.ngOnDestroy();
  });

  it('ignores an older load that answers after a newer one', () => {
    const { component, getGame, reconnected } = createSpectator();
    const olderLoad = new Subject<Game>();
    const newerLoad = new Subject<Game>();
    getGame.mockReturnValueOnce(olderLoad).mockReturnValueOnce(newerLoad);
    component.ngOnInit();
    reconnected.next();

    newerLoad.next(
      gameWith({
        currentFen: AFTER_E5,
        moves: [storedMove(1, 'WHITE', 'e4', AFTER_E4), storedMove(1, 'BLACK', 'e5', AFTER_E5)],
      }),
    );
    olderLoad.next(gameWith({ currentFen: START_FEN, moves: [] }));
    olderLoad.error(new Error('late failure'));

    expect(component.moves().map((move) => move.san)).toEqual(['e4', 'e5']);
    expect(component.game()?.currentFen).toBe(AFTER_E5);
  });

  it('marks live override moves from the flag, falling back to the override model id', () => {
    const { component } = createComponent();
    component.game.set(gameWith());

    component.handleWsMessage(liveMove(1, 'WHITE', 'e4', AFTER_E4, { isOverride: true }));
    component.handleWsMessage(liveMove(1, 'BLACK', 'e5', AFTER_E5, { modelId: 'admin-override' }));
    component.handleWsMessage(
      liveMove(2, 'WHITE', 'Nf3', START_FEN, { isOverride: false, modelId: 'model-a' }),
    );

    expect(component.moves().map((move) => move.isOverride)).toEqual([true, true, false]);
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

describe('GameView resignation', () => {
  const resigned = (overrides: Partial<Game> = {}) =>
    gameWith({
      status: 'COMPLETED',
      result: 'WHITE_WINS',
      resultReason: 'RESIGNATION',
      completedAt: '2026-03-06T10:05:00Z',
      moves: [storedMove(1, 'WHITE', 'e4', AFTER_E4)],
      resignation: {
        color: 'BLACK',
        moveNumber: 1,
        winChance: null,
        notLosing: false,
        lastWords: null,
        opponentMessage: null,
        opponentMessageMoveNumber: null,
      },
      ...overrides,
    });

  it('names who resigned in the feed', () => {
    const { component } = createComponent();
    component.game.set(resigned());

    const result = component.feedEvents().find((event) => event.type === 'result');

    expect(result?.label).toBe('Game Complete');
    expect(result?.detail).toBe('Beta resigned');
  });

  it('lists a resignation that was not allowed as a failed attempt of the move played instead', () => {
    const { component } = createComponent();
    component.game.set(gameWith());
    component.moves.set([storedMove(1, 'WHITE', 'e4', AFTER_E4)]);
    component.attempts.set([
      attemptFixture({ outcome: 'RESIGN_NOT_ALLOWED' }),
      attemptFixture({ attemptNumber: 2, outcome: 'ACCEPTED', proposedMove: 'e4' }),
    ]);

    const move = component.feedEvents().find((event) => event.type === 'move');

    expect(move?.attemptSummary).toBe('1 failed attempt: resignation not allowed');
  });

  it('reloads the game when it ends by resignation, to show its details', () => {
    const { component, gameLoad, messages, getGame } = createSpectator();
    component.ngOnInit();
    gameLoad.next(gameWith({ moves: [storedMove(1, 'WHITE', 'e4', AFTER_E4)] }));

    messages.next({
      type: 'gameStatus',
      gameId: 'g-1',
      status: 'COMPLETED',
      result: 'WHITE_WINS',
      resultReason: 'RESIGNATION',
      statusRevision: 3,
    });
    gameLoad.next(resigned({ chatMessages: [] }));

    expect(getGame).toHaveBeenCalledTimes(2);
    expect(component.game()?.resignation?.color).toBe('BLACK');
    component.ngOnDestroy();
  });

  it('keeps the announced result when the reload still reads the game as running, and asks again', () => {
    vi.useFakeTimers();
    try {
      const { component, gameLoad, messages, getGame } = createSpectator();
      component.ngOnInit();
      gameLoad.next(gameWith({ moves: [storedMove(1, 'WHITE', 'e4', AFTER_E4)] }));

      messages.next({
        type: 'gameStatus',
        gameId: 'g-1',
        status: 'COMPLETED',
        result: 'WHITE_WINS',
        resultReason: 'RESIGNATION',
        statusRevision: 3,
      });
      // The reload was read before the result was committed.
      gameLoad.next(gameWith({ moves: [storedMove(1, 'WHITE', 'e4', AFTER_E4)] }));

      expect(component.game()?.status).toBe('COMPLETED');
      expect(component.game()?.result).toBe('WHITE_WINS');
      expect(component.game()?.resultReason).toBe('RESIGNATION');
      expect(getGame).toHaveBeenCalledTimes(2);

      vi.advanceTimersByTime(GameView.STALE_RELOAD_DELAY_MS);
      expect(getGame).toHaveBeenCalledTimes(3);
      gameLoad.next(resigned());
      expect(component.game()?.resignation?.color).toBe('BLACK');

      // Once the stored game agrees, nothing is reloaded any more.
      vi.advanceTimersByTime(GameView.STALE_RELOAD_DELAY_MS * 3);
      expect(getGame).toHaveBeenCalledTimes(3);
      component.ngOnDestroy();
    } finally {
      vi.useRealTimers();
    }
  });

  it('gives up asking again after a few stale reloads but keeps the result', () => {
    vi.useFakeTimers();
    try {
      const { component, gameLoad, messages, getGame } = createSpectator();
      component.ngOnInit();
      gameLoad.next(gameWith());
      messages.next({
        type: 'gameStatus',
        gameId: 'g-1',
        status: 'FORFEIT',
        result: 'BLACK_FORFEIT',
        statusRevision: 3,
      });
      component.loadGame();
      for (let i = 0; i < GameView.MAX_STALE_RELOADS + 2; i++) {
        gameLoad.next(gameWith());
        vi.advanceTimersByTime(GameView.STALE_RELOAD_DELAY_MS);
      }

      expect(getGame).toHaveBeenCalledTimes(2 + GameView.MAX_STALE_RELOADS);
      expect(component.game()?.status).toBe('FORFEIT');
      expect(component.game()?.result).toBe('BLACK_FORFEIT');
      component.ngOnDestroy();
    } finally {
      vi.useRealTimers();
    }
  });

  it('ignores a pause update that arrives after the resume that overtook it', () => {
    vi.useFakeTimers();
    try {
      const { component, gameLoad, messages, getGame } = createSpectator();
      component.ngOnInit();
      gameLoad.next(gameWith({ status: 'IN_PROGRESS', statusRevision: 4 }));

      // The pause (revision 5) committed first, but its update is delivered after the resume (revision 6).
      messages.next({
        type: 'gameStatus',
        gameId: 'g-1',
        status: 'IN_PROGRESS',
        statusRevision: 6,
      });
      messages.next({ type: 'gameStatus', gameId: 'g-1', status: 'PAUSED', statusRevision: 5 });

      expect(component.game()?.status).toBe('IN_PROGRESS');
      expect(component.game()?.statusRevision).toBe(6);

      // A reload read between the two commits is older too: keep the status and ask again.
      component.loadGame();
      gameLoad.next(gameWith({ status: 'PAUSED', statusRevision: 5 }));
      expect(component.game()?.status).toBe('IN_PROGRESS');
      vi.advanceTimersByTime(GameView.STALE_RELOAD_DELAY_MS);
      expect(getGame).toHaveBeenCalledTimes(3);
      gameLoad.next(gameWith({ status: 'IN_PROGRESS', statusRevision: 6 }));
      expect(component.game()?.status).toBe('IN_PROGRESS');

      // A newer change is applied again.
      messages.next({ type: 'gameStatus', gameId: 'g-1', status: 'PAUSED', statusRevision: 7 });
      expect(component.game()?.status).toBe('PAUSED');
      component.ngOnDestroy();
    } finally {
      vi.useRealTimers();
    }
  });

  it('applies a pause announced before the first snapshot when the snapshot is older', () => {
    vi.useFakeTimers();
    try {
      const { component, gameLoad, messages, getGame } = createSpectator();
      component.ngOnInit();

      // The GET read revision 4; the pause (revision 5) is announced before its response arrives.
      messages.next({
        type: 'gameStatus',
        gameId: 'g-1',
        status: 'PAUSED',
        detail: 'Rate limited',
        statusRevision: 5,
      });
      gameLoad.next(gameWith({ status: 'IN_PROGRESS', statusRevision: 4 }));

      expect(component.game()?.status).toBe('PAUSED');
      expect(component.game()?.statusDetail).toBe('Rate limited');
      expect(component.game()?.statusRevision).toBe(5);

      // The stored game is read again; a newer snapshot is accepted, an older one would not be.
      vi.advanceTimersByTime(GameView.STALE_RELOAD_DELAY_MS);
      expect(getGame).toHaveBeenCalledTimes(2);
      gameLoad.next(
        gameWith({ status: 'PAUSED', statusDetail: 'Rate limited', statusRevision: 5 }),
      );
      expect(component.game()?.status).toBe('PAUSED');
      vi.advanceTimersByTime(GameView.STALE_RELOAD_DELAY_MS * 3);
      expect(getGame).toHaveBeenCalledTimes(2);
      component.ngOnDestroy();
    } finally {
      vi.useRealTimers();
    }
  });

  it('applies a forfeit announced before the first snapshot when the snapshot is older', () => {
    vi.useFakeTimers();
    try {
      const { component, gameLoad, messages, getGame } = createSpectator();
      component.ngOnInit();

      messages.next({
        type: 'gameStatus',
        gameId: 'g-1',
        status: 'FORFEIT',
        result: 'BLACK_FORFEIT',
        resultReason: 'TIMEOUT',
        statusRevision: 5,
      });
      messages.next({ type: 'forfeit', gameId: 'g-1' });
      gameLoad.next(gameWith({ status: 'IN_PROGRESS', statusRevision: 4 }));

      expect(component.game()?.status).toBe('FORFEIT');
      expect(component.game()?.result).toBe('BLACK_FORFEIT');
      expect(component.game()?.resultReason).toBe('TIMEOUT');

      // A retry that still reads the old snapshot keeps the forfeit.
      vi.advanceTimersByTime(GameView.STALE_RELOAD_DELAY_MS);
      expect(getGame).toHaveBeenCalledTimes(2);
      gameLoad.next(gameWith({ status: 'IN_PROGRESS', statusRevision: 4 }));
      expect(component.game()?.status).toBe('FORFEIT');
      component.ngOnDestroy();
    } finally {
      vi.useRealTimers();
    }
  });

  it('never lets a delayed turn update reopen a paused game, during the first load or live', () => {
    const { component, gameLoad, messages, getGame } = createSpectator();
    component.ngOnInit();

    // Before the first response: the pause, then a delayed per-turn update (no revision).
    messages.next({ type: 'gameStatus', gameId: 'g-1', status: 'PAUSED', statusRevision: 5 });
    messages.next({
      type: 'gameStatus',
      gameId: 'g-1',
      status: 'IN_PROGRESS',
      activeColor: 'WHITE',
      totalCostUsd: 0.5,
    });
    gameLoad.next(gameWith({ status: 'PAUSED', statusRevision: 5 }));

    expect(component.game()?.status).toBe('PAUSED');
    expect(component.game()?.statusRevision).toBe(5);
    expect(getGame).toHaveBeenCalledTimes(1);

    // Live, the same: a turn update never changes a paused game's status.
    messages.next({
      type: 'gameStatus',
      gameId: 'g-1',
      status: 'IN_PROGRESS',
      activeColor: 'BLACK',
    });
    expect(component.game()?.status).toBe('PAUSED');

    // Nor a finished one.
    messages.next({
      type: 'gameStatus',
      gameId: 'g-1',
      status: 'FORFEIT',
      result: 'WHITE_FORFEIT',
      statusRevision: 6,
    });
    messages.next({
      type: 'gameStatus',
      gameId: 'g-1',
      status: 'IN_PROGRESS',
      activeColor: 'WHITE',
    });
    expect(component.game()?.status).toBe('FORFEIT');
    component.ngOnDestroy();
  });

  it('applies a turn update buffered during the first load to a running game', () => {
    const { component, gameLoad, messages } = createSpectator();
    component.ngOnInit();

    messages.next({
      type: 'gameStatus',
      gameId: 'g-1',
      status: 'IN_PROGRESS',
      activeColor: 'BLACK',
      totalCostUsd: 0.25,
      tournamentChatMode: 'MANDATORY',
    });
    gameLoad.next(gameWith({ status: 'IN_PROGRESS', statusRevision: 4, totalCostUsd: 0.1 }));

    expect(component.game()?.status).toBe('IN_PROGRESS');
    expect(component.game()?.totalCostUsd).toBe(0.25);
    expect(component.activeColor()).toBe('BLACK');
    expect(component.tournamentChatMode()).toBe('MANDATORY');

    // Live as well.
    messages.next({
      type: 'gameStatus',
      gameId: 'g-1',
      status: 'IN_PROGRESS',
      activeColor: 'WHITE',
    });
    expect(component.activeColor()).toBe('WHITE');
    component.ngOnDestroy();
  });

  it('keeps a newer first snapshot over an older status announced before it', () => {
    const { component, gameLoad, messages, getGame } = createSpectator();
    component.ngOnInit();

    messages.next({ type: 'gameStatus', gameId: 'g-1', status: 'PAUSED', statusRevision: 5 });
    gameLoad.next(gameWith({ status: 'IN_PROGRESS', statusRevision: 6 }));

    expect(component.game()?.status).toBe('IN_PROGRESS');
    expect(getGame).toHaveBeenCalledTimes(1);
    component.ngOnDestroy();
  });

  it('keeps a live evaluation that a reload does not have yet', () => {
    const { component, gameLoad, messages, getGame } = createSpectator();
    component.ngOnInit();
    gameLoad.next(resigned());

    messages.next({
      type: 'evaluationUpdate',
      gameId: 'g-1',
      moveNumber: 1,
      color: 'WHITE',
      evaluationCp: 30,
      qualities: [],
      qualitySummary: {
        white: {
          analyzedMoves: 1,
          averageCentipawnLoss: 0,
          inaccuracies: 0,
          mistakes: 0,
          blunders: 0,
        },
        black: {
          analyzedMoves: 0,
          averageCentipawnLoss: null,
          inaccuracies: 0,
          mistakes: 0,
          blunders: 0,
        },
      },
    });
    expect(getGame).toHaveBeenCalledTimes(2);
    // The reload was read before the evaluation was stored.
    gameLoad.next(resigned());

    expect(component.moves()[0].evaluationCp).toBe(30);
    expect(component.game()?.qualitySummary?.white.analyzedMoves).toBe(1);
    component.ngOnDestroy();
  });

  it('does not reload for other results', () => {
    const { component, gameLoad, messages, getGame } = createSpectator();
    component.ngOnInit();
    gameLoad.next(gameWith());

    messages.next({
      type: 'gameStatus',
      gameId: 'g-1',
      status: 'COMPLETED',
      result: 'WHITE_WINS',
      resultReason: 'CHECKMATE',
      statusRevision: 3,
    });

    expect(getGame).toHaveBeenCalledTimes(1);
    component.ngOnDestroy();
  });

  it('reloads once the position a side resigned in is evaluated', () => {
    const { component, gameLoad, messages, getGame } = createSpectator();
    component.ngOnInit();
    gameLoad.next(resigned());

    // An evaluation of another move does not decide the resignation.
    messages.next({ type: 'evaluationUpdate', gameId: 'g-1', moveNumber: 5, color: 'WHITE' });
    expect(getGame).toHaveBeenCalledTimes(1);

    messages.next({
      type: 'evaluationUpdate',
      gameId: 'g-1',
      moveNumber: 1,
      color: 'WHITE',
      evaluationCp: 30,
    });
    expect(getGame).toHaveBeenCalledTimes(2);
    gameLoad.next(
      resigned({
        resignation: { ...resigned().resignation!, winChance: 0.46, notLosing: true },
      }),
    );

    // Known now: further evaluations do not reload again.
    messages.next({
      type: 'evaluationUpdate',
      gameId: 'g-1',
      moveNumber: 1,
      color: 'WHITE',
      evaluationCp: 31,
    });
    expect(getGame).toHaveBeenCalledTimes(2);
    component.ngOnDestroy();
  });
});

describe('GameView resume while saving', () => {
  it('blocks Admin Controls Resume until a delayed participant PUT finishes', () => {
    const participantSaved = new Subject<any>();
    const startGame = vi.fn(() => of(void 0));
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ id: 'g-1' }) } },
        },
        {
          provide: ApiService,
          useValue: {
            getGame: () =>
              of(
                gameFixture({
                  tournamentId: 't-1',
                  status: 'PAUSED',
                  statusDetail: 'Cut off',
                  blackParticipantId: 'p-2',
                  currentFen: AFTER_E4,
                  moves: [moveFixture({ fen: AFTER_E4 })],
                }),
              ),
            getTournament: () => of({ maxOutputTokens: 4096 }),
            getGameAttempts: () => of([attemptFixture({ color: 'BLACK', outcome: 'TRUNCATED' })]),
            getModels: () => of({ data: [] }),
            getSystemStatus: () => EMPTY,
            updateTournament: () => of({ maxOutputTokens: 8192 }),
            updateParticipant: () => participantSaved,
            startGame,
          },
        },
        {
          provide: WebSocketService,
          useValue: {
            connect: vi.fn(),
            subscribeToGame: vi.fn(),
            unsubscribeFromGame: vi.fn(),
            getMessages: () => EMPTY,
            reconnected: () => EMPTY,
          },
        },
      ],
    });
    const fixture = TestBed.createComponent(GameView);
    fixture.detectChanges();
    const component = fixture.componentInstance;
    const resume = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>(
        '.admin-controls button',
      ),
    ).find((button) => button.textContent?.includes('Resume'))!;
    expect(resume.disabled).toBe(false);
    component.saveTruncationFixAndResume({ maxOutputTokens: 8192, reasoningEffort: 'low' });
    fixture.detectChanges();
    expect(component.savingLimit()).toBe(true);
    expect(resume.disabled).toBe(true);
    resume.click();
    component.resumeGame();
    expect(startGame).not.toHaveBeenCalled();
    participantSaved.next({ reasoningEffort: 'low' });
    participantSaved.complete();
    fixture.detectChanges();
    expect(startGame).toHaveBeenCalledExactlyOnceWith('g-1');
    expect(component.game()?.blackReasoningEffort).toBe('low');
    expect(component.savingLimit()).toBe(false);
    expect(resume.disabled).toBe(false);
    fixture.destroy();
  });
});

describe('GameView promotion picker', () => {
  function renderPicker(currentFen: string) {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        {
          provide: ApiService,
          useValue: {
            getGame: () => of(gameWith({ currentFen })),
            getSystemStatus: () => EMPTY,
            getGameAttempts: () => of([]),
          },
        },
        {
          provide: WebSocketService,
          useValue: {
            connect: vi.fn(),
            subscribeToGame: vi.fn(),
            unsubscribeFromGame: vi.fn(),
            getMessages: () => EMPTY,
            reconnected: () => EMPTY,
          },
        },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ id: 'g-1' }) } },
        },
      ],
    });
    const fixture = TestBed.createComponent(GameView);
    fixture.detectChanges();
    fixture.componentInstance.pendingPromotion.set({ from: 'e7', to: 'e8' });
    fixture.detectChanges();
    return fixture;
  }

  it.each([
    ['WHITE', START_FEN, 'white-piece'],
    ['BLACK', AFTER_E4, 'black-piece'],
  ])('shows the four piece glyphs in %s colours, not escape text', (_color, fen, pieceClass) => {
    const fixture = renderPicker(fen);
    const pieces = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.promotion-picker .promotion-piece'),
    );

    expect(pieces.map((piece) => piece.textContent?.trim())).toEqual(['♛', '♜', '♝', '♞']);
    expect(pieces.every((piece) => piece.classList.contains(pieceClass))).toBe(true);
    fixture.destroy();
  });
});
