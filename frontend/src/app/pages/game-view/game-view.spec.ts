import { EMPTY, Subject, of, throwError } from 'rxjs';
import { GameView } from './game-view';
import { GameTurnStateService } from '../../services/game-turn-state.service';
import { Game, Move, MoveWsMessage, WsMessage } from '../../models/tournament.model';

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
    pauseGame: () => of(void 0),
    startGame: () => of(void 0),
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
