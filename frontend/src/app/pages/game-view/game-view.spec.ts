import { OpenRouterAccess } from '../../services/openrouter-access';
import { MatSnackBar } from '@angular/material/snack-bar';
import { BoardThemeService } from '../../services/board-theme.service';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { EMPTY, Observable, Subject, of, throwError } from 'rxjs';
import { GameView } from './game-view';
import { GameInfoBar } from '../../components/game-info-bar/game-info-bar';
import { REASONING_EFFORTS } from '../../utils/reasoning-effort';
import { GameTurnStateService } from '../../services/game-turn-state.service';
import { ApiService } from '../../services/api.service';
import { WebSocketService } from '../../services/websocket.service';
import {
  Game,
  GameChange,
  Move,
  MoveAttempt,
  MoveWsMessage,
  WsMessage,
} from '../../models/tournament.model';

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

function revision(
  revision: number,
  ...changes: GameChange[]
): Extract<WsMessage, { type: 'gameRevision' }> {
  return { type: 'gameRevision', gameId: 'g-1', revision, changes };
}

function gameState(fields: Partial<Extract<GameChange, { type: 'gameState' }>> = {}): GameChange {
  return {
    type: 'gameState',
    gameId: 'g-1',
    activeColor: null,
    turnStartedAt: null,
    turnDeadlineAt: null,
    turnChatMode: null,
    turnBanterLevel: null,
    totalCostUsd: 0,
    ...fields,
  };
}

/** A component wired for ngOnInit, whose game load stays in flight until `gameLoad` emits. */
function createSpectator(acknowledgeImmediately = true) {
  const gameLoad = new Subject<Game>();
  const messages = new Subject<WsMessage>();
  const reconnected = new Subject<void>();
  const getGame = vi.fn<() => Observable<Game>>(() => gameLoad);
  const api = {
    getGame,
    getSystemStatus: () => EMPTY,
    getGameAttempts: vi.fn(() => of([] as MoveAttempt[])),
    getModels: () => of({ data: [] }),
  };
  const ws = {
    connect: vi.fn(),
    subscribeToGame: vi.fn(() => {
      if (acknowledgeImmediately) messages.next({ type: 'subscribed', gameId: 'g-1', revision: 0 });
    }),
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
    new BoardThemeService(document),
  );
  return { component, gameLoad, messages, reconnected, getGame, api };
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
      new BoardThemeService(document),
    ),
    api,
    snackbar,
  };
}

describe('GameView', () => {
  it('forks the selected playable ply on the only side with an opposing model', () => {
    const createPractice = vi.fn(() => of(gameWith({ id: 'fork' })));
    const { component } = createComponent({ api: { createPractice } });
    component.gameActionsAvailable.set(true);
    component.game.set(
      gameWith({
        status: 'COMPLETED',
        resultReason: 'REPETITION',
        blackControlType: 'HUMAN',
        blackModelId: null,
      }),
    );
    component.moves.set([
      storedMove(1, 'WHITE', 'e4', AFTER_E4),
      storedMove(1, 'BLACK', 'e5', AFTER_E5),
    ]);
    component.currentMoveIndex.set(1);
    expect(component.practicePly()).toBeNull();
    component.playFromHere('BLACK');

    component.currentMoveIndex.set(0);
    expect(component.practicePly()).toBe(1);
    expect(component.practiceModelSides()).toEqual(['BLACK']);
    component.playFromHere('WHITE');
    expect(createPractice).not.toHaveBeenCalled();
    component.playFromHere('BLACK');
    expect(createPractice).toHaveBeenCalledWith('g-1', 1, 'BLACK', expect.any(String), 'm-1-WHITE');
  });

  it('reuses the same fork request id when a failed response is retried for the same position', () => {
    const createPractice = vi.fn(() => throwError(() => ({ status: 502 })));
    const { component } = createComponent({ api: { createPractice } });
    component.gameActionsAvailable.set(true);
    component.game.set(gameWith({ status: 'FORFEIT' }));
    component.currentMoveIndex.set(-1);
    component.playFromHere('WHITE');
    component.playFromHere('WHITE');
    expect(createPractice).toHaveBeenCalledTimes(2);
    expect(createPractice.mock.calls[0]).toEqual(createPractice.mock.calls[1]);
  });

  it('downloads improved PGN as UTF-8 through the existing action', () => {
    vi.useFakeTimers();
    const createObjectURL = vi.fn().mockReturnValue('blob:export');
    vi.stubGlobal('URL', { createObjectURL, revokeObjectURL: vi.fn() });
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    const pgn = '[Event "Arena Ω"]\n\n1. e4 {[%eval 0.35]}\n*\n';
    const getGamePgn = vi.fn().mockReturnValue(of(pgn));
    const { component } = createComponent({ api: { getGamePgn } });
    (component as any).gameId = 'g-1';
    component.exportPgn();
    expect(getGamePgn).toHaveBeenCalledWith('g-1');
    expect((click.mock.instances[0] as HTMLAnchorElement).download).toBe('game-g-1.pgn');
    const blob = createObjectURL.mock.lastCall![0];
    expect(blob.type).toBe('application/x-chess-pgn;charset=utf-8');
    expect(blob.size).toBe(new TextEncoder().encode(pgn).length);
    vi.runAllTimers();
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('reports PGN failures without downloading an empty file', () => {
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click');
    const { component, snackbar } = createComponent({
      api: { getGamePgn: () => throwError(() => new Error('offline')) },
    });
    component.exportPgn();
    expect(click).not.toHaveBeenCalled();
    expect(snackbar.open).toHaveBeenCalledWith(
      'Could not export game. Please try again.',
      'OK',
      expect.any(Object),
    );
    vi.restoreAllMocks();
  });
  it('labels book moves in the feed without attributing them to a player', () => {
    const { component } = createComponent();
    component.game.set(gameWith({ openingName: 'Ruy Lopez', openingEco: 'C60' }));
    component.moves.set([
      { ...storedMove(1, 'WHITE', 'e4', AFTER_E4), isBook: true, modelId: null },
    ]);
    const event = component.timelineEvents().find((entry) => entry.type === 'move');
    expect(event?.detail).toBe('Book move');
    expect(event?.quality).toBeNull();
    expect(event?.attemptSummary).toBeNull();
  });

  it('renders the stored opening name and ECO above the game controls', async () => {
    TestBed.configureTestingModule({
      imports: [GameView],
      providers: [
        { provide: OpenRouterAccess, useValue: { withKey: (action: () => void) => action() } },
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ id: 'g-1' }) } },
        },
        {
          provide: ApiService,
          useValue: {
            getGame: () =>
              of(gameWith({ openingName: 'Ruy Lopez', openingEco: 'C60', status: 'WAITING' })),
            getGameAttempts: () => of([]),
            getSystemStatus: () => EMPTY,
            getModels: () => of({ data: [] }),
          },
        },
        {
          provide: WebSocketService,
          useValue: {
            connect: vi.fn(),
            subscribeToGame: vi.fn(),
            unsubscribeFromGame: vi.fn(),
            getMessages: () => of({ type: 'subscribed', gameId: 'g-1', revision: 0 }),
            reconnected: () => EMPTY,
          },
        },
      ],
    });
    const fixture = TestBed.createComponent(GameView);
    fixture.detectChanges();
    await fixture.whenStable();
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('.opening-label')?.textContent,
    ).toContain('C60 · Ruy Lopez');
  });

  it('brings the final ply back into view on Last move even when it is still selected', () => {
    const moves = [storedMove(1, 'WHITE', 'e4', AFTER_E4), storedMove(1, 'BLACK', 'e5', AFTER_E5)];
    TestBed.configureTestingModule({
      providers: [
        { provide: OpenRouterAccess, useValue: { withKey: (action: () => void) => action() } },
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ id: 'g-1' }) } },
        },
        {
          provide: ApiService,
          useValue: {
            getGame: () =>
              of(gameWith({ status: 'COMPLETED', liveRevision: 0, moves, attempts: [] })),
            getGameAttempts: () => of([]),
            getSystemStatus: () => EMPTY,
          },
        },
        {
          provide: WebSocketService,
          useValue: {
            connect: vi.fn(),
            subscribeToGame: vi.fn(),
            unsubscribeFromGame: vi.fn(),
            getMessages: () => of({ type: 'subscribed', gameId: 'g-1', revision: 0 }),
            reconnected: () => EMPTY,
          },
        },
      ],
    });
    const fixture = TestBed.createComponent(GameView);
    fixture.detectChanges();
    const element: HTMLElement = fixture.nativeElement;
    const list = element.querySelector<HTMLElement>('.move-list-scroll')!;
    const selected = list.querySelector<HTMLElement>('.move.active')!;
    expect(selected.textContent).toContain('e5');
    vi.spyOn(list, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 0, 100, 40));
    vi.spyOn(selected, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 60, 100, 20));

    fixture.componentInstance.moves.set([...moves]);
    fixture.detectChanges();
    expect(list.scrollTop).toBe(0);

    element.querySelector<HTMLButtonElement>('button[aria-label="Last move"]')!.click();
    fixture.detectChanges();
    expect(list.scrollTop).toBe(40);
    expect(list.querySelector('.move.active')).toBe(selected);
  });

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

  it('updates and clears the tone from live turn metadata while preserving it when absent', () => {
    const { component } = createComponent();
    component.handleWsMessage({
      type: 'gameStatus',
      gameId: 'g-1',
      status: 'IN_PROGRESS',
      tournamentChatMode: 'OPTIONAL',
      tournamentBanterLevel: 'SPICY',
    });
    expect(component.tournamentBanterLevel()).toBe('SPICY');
    component.handleWsMessage({ type: 'gameStatus', gameId: 'g-1', status: 'IN_PROGRESS' });
    expect(component.tournamentBanterLevel()).toBe('SPICY');
    component.handleWsMessage({
      type: 'gameStatus',
      gameId: 'g-1',
      status: 'IN_PROGRESS',
      tournamentBanterLevel: null,
    });
    expect(component.tournamentBanterLevel()).toBeNull();
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

  it('shows no countdown for a game outside a tournament', () => {
    const { component } = createComponent({
      api: {
        getGame: () =>
          of(gameFixture({ status: 'IN_PROGRESS', tournamentId: null, currentFen: START_FEN })),
      },
    });
    (component as any).gameId = 'g-1';
    component.loadGame();
    expect(component.noMoveTimeLimit()).toBe(true);
    expect(component.moveTimerSeconds()).toBeNull();
  });

  it('counts down the move time when the tournament has a limit', () => {
    const { component } = createComponent({
      api: {
        getGame: () =>
          of(
            gameFixture({
              status: 'IN_PROGRESS',
              tournamentId: 't-1',
              currentFen: START_FEN,
              liveRevision: 1,
              activeColor: 'WHITE',
              turnDeadlineAt: new Date(Date.now() + 45000).toISOString(),
            }),
          ),
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
        getGame: () =>
          of(
            gameFixture({
              tournamentId: 't-1',
              status: 'PAUSED',
              statusDetail: 'Answer cut off',
              statusRevision: 1,
            }),
          ),
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

  it('passes the latest truncated request maximum to the pause banner', () => {
    const { component } = createComponent();
    component.tournamentMaxOutputTokens.set(16384);
    component.game.set(
      gameFixture({ tournamentId: 't-1', status: 'PAUSED', statusDetail: 'Model maximum reached' }),
    );
    component.attempts.set([
      attemptFixture({
        outcome: 'TRUNCATED',
        effectiveMaxOutputTokens: 8192,
        modelMaxCompletionTokens: 8192,
      }),
    ]);

    expect(component.truncationModelMaximum()).toBe(8192);
    const bar = new GameInfoBar();
    bar.game = component.game()!;
    bar.truncationLimit = component.truncationPauseLimit();
    bar.truncationModelMaximum = component.truncationModelMaximum();
    expect(bar.newMaxOutputTokens).toBe(16384);

    component.attempts.set([attemptFixture({ outcome: 'TRUNCATED' })]);
    expect(component.truncationModelMaximum()).toBeNull();
    component.attempts.set([
      attemptFixture({ outcome: 'PROVIDER_FATAL', modelMaxCompletionTokens: 8192 }),
    ]);
    expect(component.truncationModelMaximum()).toBeNull();
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
    expect(startGame).toHaveBeenCalledWith('g-1', true);
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
            error: { error: 'maxOutputTokens must be between 128 and 262144' },
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
      'maxOutputTokens must be between 128 and 262144',
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
      moves: [moveFixture()],
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
    expect(getTournament).not.toHaveBeenCalled();
    expect(component.truncationEffort()).toBeNull();
    expect(component.truncationPauseLimit()).toBeNull();
    component.saveLimitAndResume(8192, 'low');
    expect(updateTournament).not.toHaveBeenCalled();

    // The participant changed elsewhere while the game view stayed open.
    refreshedGame.next({
      ...original,
      status: 'PAUSED',
      statusRevision: 1,
      statusDetail: 'Cut off',
      blackReasoningEffort: 'minimal',
    });
    refreshedGame.complete();
    expect(getTournament).toHaveBeenCalledWith('t-1');
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

  it('keeps paused settings hidden while a stale snapshot is rejected and a new read starts', () => {
    vi.useFakeTimers();
    const { component, gameLoad, messages, getGame, api } = createSpectator();
    const oldTournament = new Subject<{ reasoningEffort: string; maxOutputTokens: number }>();
    const currentTournament = { reasoningEffort: 'minimal', maxOutputTokens: 12000 };
    Object.assign(api, {
      getTournament: vi
        .fn()
        .mockReturnValueOnce(oldTournament)
        .mockReturnValue(of(currentTournament)),
    });
    const original = gameFixture({
      liveRevision: 1,
      statusRevision: 0,
      status: 'IN_PROGRESS',
      tournamentId: 't-1',
      blackParticipantId: 'p-2',
      blackReasoningEffort: 'high',
      moves: [moveFixture()],
    });
    const paused = {
      ...original,
      liveRevision: 2,
      status: 'PAUSED' as const,
      statusRevision: 1,
      statusDetail: 'Cut off',
      blackReasoningEffort: 'minimal' as const,
      attempts: [attemptFixture({ color: 'BLACK', outcome: 'TRUNCATED' })],
    };
    try {
      component.ngOnInit();
      gameLoad.next(original);
      component.loadGame();
      messages.next(
        revision(
          2,
          { type: 'gameStatus', gameId: 'g-1', status: 'PAUSED', statusRevision: 1 },
          gameState(),
        ),
      );
      gameLoad.next(original);
      expect(getGame).toHaveBeenCalledTimes(2);
      vi.advanceTimersByTime(500);
      gameLoad.next(paused);
      oldTournament.next({ reasoningEffort: 'high', maxOutputTokens: 4096 });
      expect(component.game()?.blackReasoningEffort).toBe('minimal');
      expect(component.tournamentReasoningEffort()).toBe('minimal');
      expect(component.truncationEffort()?.current).toBe('minimal');
      expect(component.truncationPauseLimit()).toBe(12000);
    } finally {
      component.ngOnDestroy();
      vi.useRealTimers();
    }
  });

  it('keeps edited pause controls when a snapshot temporarily hides their settings', () => {
    const bar = new GameInfoBar();
    const paused = gameFixture({ status: 'PAUSED', statusDetail: 'Cut off' });
    const effort = {
      participantId: 'p-2',
      playerName: 'Beta',
      current: 'high' as const,
      tournamentLevel: null,
      options: [...REASONING_EFFORTS],
      fromCatalog: true,
    };
    bar.game = paused;
    bar.truncationLimit = 4096;
    bar.truncationEffort = effort;
    bar.editOutputLimit(9000);
    bar.editReasoningEffort('medium');

    bar.game = { ...paused, liveRevision: 4 };
    bar.truncationLimit = null;
    bar.truncationEffort = null;
    bar.truncationLimit = 5000;
    bar.truncationEffort = { ...effort, current: 'low' };
    expect(bar.newMaxOutputTokens).toBe(9000);
    expect(bar.newReasoningEffort).toBe('medium');
  });

  it('keeps stale pause controls hidden if refreshing settings fails', () => {
    const { component, snackbar } = createComponent({
      api: {
        getGame: () =>
          of(gameFixture({ tournamentId: 't-1', status: 'PAUSED', statusRevision: 1 })),
        getTournament: () => throwError(() => new Error('unavailable')),
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
    expect(startGame).toHaveBeenCalledWith('g-1', true);
    expect(calls).toEqual(['tournament', 'participant', 'start']);
    expect(component.game()?.blackReasoningEffort).toBe('low');
    expect(component.savingLimit()).toBe(false);
    expect(snackbar.open).not.toHaveBeenCalled();
  });

  it.each([undefined, 32768, 8192, 16384])(
    'preserves 32768 for effort-only recovery at an 8192 model cap, but saves an explicit edit to %s',
    (explicitLimit) => {
      const calls: string[] = [];
      const updateTournament = vi.fn(() => {
        calls.push('tournament');
        return of({ id: 't-1', maxOutputTokens: explicitLimit });
      });
      const participantSaved = new Subject<{ reasoningEffort: string }>();
      const updateParticipant = vi.fn(() => {
        calls.push('participant');
        return participantSaved;
      });
      const startGame = vi.fn(() => {
        calls.push('start');
        return of(void 0);
      });
      const { component } = createComponent({
        api: { updateTournament, updateParticipant, startGame },
      });
      (component as any).gameId = 'g-1';
      component.tournamentMaxOutputTokens.set(32768);
      component.game.set(
        gameFixture({
          tournamentId: 't-1',
          status: 'PAUSED',
          statusDetail: 'Model maximum reached',
          blackParticipantId: 'p-2',
          blackReasoningEffort: 'high',
        }),
      );
      component.moves.set([moveFixture()]);
      component.attempts.set([
        attemptFixture({
          color: 'BLACK',
          outcome: 'TRUNCATED',
          effectiveMaxOutputTokens: 8192,
          modelMaxCompletionTokens: 8192,
        }),
      ]);
      const bar = new GameInfoBar();
      bar.game = component.game()!;
      bar.truncationLimit = component.truncationPauseLimit();
      bar.truncationModelMaximum = component.truncationModelMaximum();
      bar.truncationEffort = component.truncationEffort();
      bar.raiseLimitAndResume.subscribe((request) => {
        if (explicitLimit === undefined || explicitLimit === 32768) {
          expect(request).toEqual({ reasoningEffort: 'low' });
        }
        component.saveTruncationFixAndResume(request);
      });

      expect(bar.newMaxOutputTokens).toBe(32768);
      if (explicitLimit !== undefined) {
        bar.newMaxOutputTokens = explicitLimit;
      }
      bar.saveAndResume();

      const changed = explicitLimit !== undefined && explicitLimit !== 32768;
      if (changed) {
        expect(updateTournament).toHaveBeenCalledExactlyOnceWith('t-1', {
          maxOutputTokens: explicitLimit,
        });
      } else {
        expect(updateTournament).not.toHaveBeenCalled();
      }
      expect(component.tournamentMaxOutputTokens()).toBe(changed ? explicitLimit : 32768);
      expect(updateParticipant).toHaveBeenCalledExactlyOnceWith('t-1', 'p-2', {
        reasoningEffort: 'low',
      });
      expect(component.savingLimit()).toBe(true);
      expect(startGame).not.toHaveBeenCalled();
      participantSaved.next({ reasoningEffort: 'low' });
      expect(startGame).toHaveBeenCalledExactlyOnceWith('g-1', true);
      expect(calls).toEqual(
        changed ? ['tournament', 'participant', 'start'] : ['participant', 'start'],
      );
      expect(component.game()?.blackReasoningEffort).toBe('low');
      expect(component.savingLimit()).toBe(false);
    },
  );

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
    expect(startGame).toHaveBeenCalledWith('g-1', true);
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

  it('waits for the subscription and reconciles a terminal update during startup', () => {
    vi.useFakeTimers();
    const { component, gameLoad, messages, getGame } = createSpectator(false);
    component.ngOnInit();
    try {
      messages.next({ type: 'gameRevision', gameId: 'g-1', revision: 2, changes: [] });
      expect(getGame).not.toHaveBeenCalled();
      messages.next({ type: 'subscribed', gameId: 'g-1', revision: 2 });
      expect(getGame).toHaveBeenCalledTimes(1);
      gameLoad.next(gameWith({ liveRevision: 1 }));
      expect(component.game()?.liveRevision).toBe(1);
      expect(getGame).toHaveBeenCalledTimes(2);
      gameLoad.next(
        gameWith({
          liveRevision: 2,
          status: 'FORFEIT',
          currentFen: AFTER_E4,
          moves: [storedMove(1, 'WHITE', 'e4', AFTER_E4)],
        }),
      );
      expect(component.game()?.status).toBe('FORFEIT');
      expect(component.currentFen()).toBe(AFTER_E4);
    } finally {
      component.ngOnDestroy();
      vi.useRealTimers();
    }
  });

  it('drops an evaluation gap while paused, uses one GET, and preserves the draft and reviewed ply', () => {
    vi.useFakeTimers();
    const { component, gameLoad, messages, getGame } = createSpectator();
    const e4 = storedMove(1, 'WHITE', 'e4', AFTER_E4);
    const e5 = { ...storedMove(1, 'BLACK', 'e5', AFTER_E5), evaluationCp: 20 };
    const latest = new Subject<Game>();
    component.ngOnInit();
    try {
      gameLoad.next(
        gameWith({ liveRevision: 1, status: 'PAUSED', currentFen: AFTER_E4, moves: [e4] }),
      );
      component.goToMove(-1);
      component.humanMove = 'Nf3';
      getGame.mockReturnValue(latest);
      messages.next({
        type: 'gameRevision',
        gameId: 'g-1',
        revision: 3,
        changes: [
          {
            type: 'evaluationUpdate',
            gameId: 'g-1',
            moveNumber: 1,
            color: 'BLACK',
            evaluationCp: 20,
          },
        ],
      });
      messages.next({
        type: 'gameRevision',
        gameId: 'g-1',
        revision: 2,
        changes: [
          {
            type: 'committedMove',
            gameId: 'g-1',
            move: e5,
            pgn: '1. e4 e5',
          },
        ],
      });
      messages.next({ type: 'heartbeat', gameId: 'g-1', revision: 4 });
      expect(getGame).toHaveBeenCalledTimes(2);
      expect(component.moves()).toEqual([e4]);
      latest.next(
        gameWith({ liveRevision: 3, status: 'PAUSED', currentFen: AFTER_E5, moves: [e4, e5] }),
      );
      expect(component.moves()).toEqual([e4, e5]);
      expect(getGame).toHaveBeenCalledTimes(3);
      latest.next(
        gameWith({ liveRevision: 4, status: 'PAUSED', currentFen: AFTER_E5, moves: [e4, e5] }),
      );
      expect(component.moves()[1].evaluationCp).toBe(20);
      expect(component.currentMoveIndex()).toBe(-1);
      expect(component.humanMove).toBe('Nf3');
    } finally {
      component.ngOnDestroy();
      vi.useRealTimers();
    }
  });

  it('keeps committed chat exactly once when the move revision overtakes a stale snapshot', () => {
    vi.useFakeTimers();
    const { component, gameLoad, messages, getGame } = createSpectator();
    const e4 = storedMove(1, 'WHITE', 'e4', AFTER_E4);
    const chat = {
      id: 'committed-chat',
      moveNumber: 1,
      senderColor: 'WHITE' as const,
      senderModel: 'model-a',
      message: 'hello',
      createdAt: '2026-01-01T00:00:00Z',
    };
    const before = gameWith({ liveRevision: 1 });
    component.ngOnInit();
    try {
      gameLoad.next(before);
      component.loadGame();
      messages.next(
        revision(
          2,
          { type: 'committedMove', gameId: 'g-1', move: e4, pgn: '1. e4' },
          { type: 'committedChat', gameId: 'g-1', chat },
          gameState({ activeColor: 'BLACK' }),
        ),
      );
      gameLoad.next(before);
      expect(component.game()?.liveRevision).toBe(1);
      expect(getGame).toHaveBeenCalledTimes(2);
      vi.advanceTimersByTime(500);
      gameLoad.next(
        gameWith({
          liveRevision: 2,
          currentFen: AFTER_E4,
          activeColor: 'BLACK',
          moves: [e4],
          chatMessages: [chat],
        }),
      );
      messages.next(revision(2, { type: 'committedChat', gameId: 'g-1', chat }, gameState()));
      expect(component.chatMessages()).toEqual([chat]);
      expect(component.moves()).toEqual([e4]);
    } finally {
      component.ngOnDestroy();
      vi.useRealTimers();
    }
  });

  it('uses the originating model turn for retry banners even after same-ply resume and late attempts', () => {
    const { component, gameLoad, messages } = createSpectator();
    const started = '2026-10-25T01:00:00Z';
    const prior = attemptFixture({
      turnStatusRevision: 4,
      createdAt: '2026-10-25T02:00:00',
      attemptNumber: 3,
      errorMessage: 'Previous turn',
      proposedMove: 'Qxh7',
    });
    const current = attemptFixture({
      turnStatusRevision: 6,
      createdAt: '2026-10-25T02:00:01',
      errorMessage: 'Bad move',
      proposedMove: 'Qxh8',
    });
    const state = gameState({ activeColor: 'WHITE', turnStartedAt: started });
    component.ngOnInit();
    gameLoad.next(
      gameWith({
        liveRevision: 10,
        statusRevision: 6,
        activeColor: 'WHITE',
        turnStartedAt: started,
        attempts: [prior],
      }),
    );
    expect(component.retryInfo()).toBeNull();
    messages.next(
      revision(11, { type: 'committedAttempt', gameId: 'g-1', attempt: current }, state),
    );
    expect(component.retryInfo()).toBe('WHITE attempt 1: illegal move Qxh8');
    messages.next(
      revision(
        12,
        {
          type: 'committedAttempt',
          gameId: 'g-1',
          attempt: { ...prior, id: 'late', createdAt: '2026-10-25T02:01:00' },
        },
        state,
      ),
    );
    expect(component.retryInfo()).toBe('WHITE attempt 1: illegal move Qxh8');
    messages.next(
      revision(
        13,
        {
          type: 'committedMove',
          gameId: 'g-1',
          move: storedMove(1, 'WHITE', 'e4', AFTER_E4),
          pgn: '1. e4',
        },
        gameState({ activeColor: 'BLACK' }),
      ),
    );
    expect(component.retryInfo()).toBeNull();
    component.ngOnDestroy();
  });

  it('keeps advancing through consistent snapshots even when each read is overtaken', () => {
    const { component, gameLoad, messages, getGame } = createSpectator();
    component.ngOnInit();
    gameLoad.next(gameWith({ liveRevision: 10 }));
    messages.next(revision(12, gameState()));
    for (let revisionId = 12; revisionId < 18; revisionId++) {
      messages.next(revision(revisionId + 1, gameState()));
      expect(getGame).toHaveBeenCalledTimes(revisionId - 10);
      gameLoad.next(gameWith({ liveRevision: revisionId }));
      expect(component.game()?.liveRevision).toBe(revisionId);
    }
    gameLoad.next(gameWith({ liveRevision: 18 }));
    expect(component.game()?.liveRevision).toBe(18);
    expect(getGame).toHaveBeenCalledTimes(8);
    component.ngOnDestroy();
  });

  it('installs a restored lower revision from the new backend and ignores the retired snapshot', () => {
    const { component, gameLoad, messages, getGame } = createSpectator(false);
    const retired = new Subject<Game>();
    const restored = new Subject<Game>();
    component.ngOnInit();
    messages.next({ type: 'subscribed', gameId: 'g-1', revision: 25, streamId: 'first' });
    gameLoad.next(gameWith({ liveRevision: 25, statusRevision: 9, streamId: 'first' }));
    component.humanMove = 'e4';
    getGame.mockReturnValueOnce(retired).mockReturnValueOnce(restored);
    component.loadGame();
    messages.next({ type: 'subscribed', gameId: 'g-1', revision: 3, streamId: 'restored' });
    restored.next(
      gameWith({ liveRevision: 3, statusRevision: 2, streamId: 'restored', status: 'PAUSED' }),
    );
    retired.next(gameWith({ liveRevision: 26, statusRevision: 10, streamId: 'first' }));
    messages.next({ ...revision(27, gameState()), streamId: 'first' });
    expect(component.game()?.liveRevision).toBe(3);
    expect(component.game()?.statusRevision).toBe(2);
    expect(component.humanMove).toBe('e4');
    component.ngOnDestroy();
  });

  it('ignores a delayed resume response from the retired backend after restore', () => {
    const { component, gameLoad, messages, getGame, api } = createSpectator(false);
    const retiredAction = new Subject<Game>();
    Object.assign(api, { startGame: () => retiredAction });
    component.ngOnInit();
    messages.next({ type: 'subscribed', gameId: 'g-1', revision: 25, streamId: 'first' });
    gameLoad.next(
      gameWith({ liveRevision: 25, statusRevision: 9, streamId: 'first', status: 'PAUSED' }),
    );
    component.resumeGame();
    messages.next({ type: 'subscribed', gameId: 'g-1', revision: 3, streamId: 'restored' });
    gameLoad.next(
      gameWith({ liveRevision: 3, statusRevision: 2, streamId: 'restored', status: 'PAUSED' }),
    );
    retiredAction.next(gameWith({ liveRevision: 26, statusRevision: 10, status: 'IN_PROGRESS' }));
    messages.next({ ...revision(4, gameState()), streamId: 'restored' });
    expect(getGame).toHaveBeenCalledTimes(2);
    expect(component.game()?.liveRevision).toBe(4);
    component.ngOnDestroy();
  });

  it('retires legacy actions when polling discovers the upgraded backend before the ack', () => {
    const { component, gameLoad, messages, api } = createSpectator(false);
    const retired = new Subject<Game>();
    Object.assign(api, { startGame: () => retired });
    component.ngOnInit();
    messages.next({ type: 'subscriptionUnavailable', gameId: 'g-1' });
    gameLoad.next(gameWith({ statusRevision: 7, status: 'PAUSED' }));
    component.resumeGame();
    component.loadGame();
    gameLoad.next(
      gameWith({ liveRevision: 0, statusRevision: 0, streamId: 'new', status: 'PAUSED' }),
    );
    expect(component.game()?.liveRevision).toBe(0);
    retired.next(gameWith({ statusRevision: 8, status: 'IN_PROGRESS' }));
    expect(component.game()?.status).toBe('PAUSED');
    expect(component.game()?.statusRevision).toBe(0);
    component.ngOnDestroy();
  });

  it('does not resume after a delayed key check from a retired or destroyed view', () => {
    const { component, gameLoad, messages, api } = createSpectator(false);
    const callbacks: Array<() => void> = [];
    const startGame = vi.fn(() => EMPTY);
    Object.assign(api, { startGame });
    Object.assign(component, {
      connection: {
        withKey: (action: () => void, isCurrent: () => boolean) =>
          callbacks.push(() => {
            if (isCurrent()) action();
          }),
      },
    });
    component.ngOnInit();
    messages.next({ type: 'subscribed', gameId: 'g-1', revision: 25, streamId: 'first' });
    gameLoad.next(gameWith({ liveRevision: 25, status: 'PAUSED', streamId: 'first' }));
    component.resumeGame();
    messages.next({ type: 'subscribed', gameId: 'g-1', revision: 3, streamId: 'restored' });
    gameLoad.next(gameWith({ liveRevision: 3, status: 'PAUSED', streamId: 'restored' }));
    callbacks[0]();
    expect(startGame).not.toHaveBeenCalled();
    component.resumeGame();
    component.ngOnDestroy();
    callbacks[1]();
    expect(startGame).not.toHaveBeenCalled();
  });

  it('retires game writes when polling disagrees with a still-subscribed socket', () => {
    const { component, gameLoad, messages, api } = createSpectator(false);
    const retired = new Subject<Game>();
    const startGame = vi.fn(() => retired);
    const cancelResumeRequest = vi.fn(() => EMPTY);
    Object.assign(api, { startGame, cancelResumeRequest });
    component.ngOnInit();
    messages.next({ type: 'subscribed', gameId: 'g-1', revision: 25, streamId: 'first' });
    const queued = gameWith({
      liveRevision: 25,
      statusRevision: 9,
      status: 'PAUSED',
      streamId: 'first',
      resumeRequestedAt: '2026-11-17T23:00:00',
    });
    gameLoad.next(queued);
    component.resumeGame();
    component.loadGame(true);
    gameLoad.next(gameWith({ liveRevision: 3, status: 'PAUSED', streamId: 'restored' }));

    expect(component.gameActionsAvailable()).toBe(false);
    expect(component.game()?.liveRevision).toBe(25);
    retired.next(gameWith({ liveRevision: 26, status: 'IN_PROGRESS', streamId: 'first' }));
    expect(component.game()?.status).toBe('PAUSED');
    component.resumeGame();
    expect(startGame).toHaveBeenCalledOnce();
    component.cancelResumeRequest(queued);
    expect(cancelResumeRequest).not.toHaveBeenCalled();

    messages.next({ type: 'subscribed', gameId: 'g-1', revision: 3, streamId: 'restored' });
    gameLoad.next(gameWith({ liveRevision: 3, status: 'PAUSED', streamId: 'restored' }));
    component.resumeGame();
    expect(startGame).toHaveBeenCalledTimes(2);
    component.ngOnDestroy();
  });

  it('retires a pending promotion when the replacement board is still on a human turn', () => {
    const { component, gameLoad, messages, api } = createSpectator(false);
    const submit = vi.fn(() => of(void 0));
    Object.assign(api, { submitHumanMoveCoordinates: submit });
    const humanTurn = { whiteControlType: 'HUMAN' as const, activeColor: 'WHITE' as const };
    component.ngOnInit();
    messages.next({ type: 'subscribed', gameId: 'g-1', revision: 25, streamId: 'first' });
    gameLoad.next(gameWith({ ...humanTurn, liveRevision: 25, streamId: 'first' }));
    component.onBoardMoveAttempt({ from: 'a7', to: 'a8', promotionRequired: true });
    expect(component.pendingPromotion()).not.toBeNull();

    messages.next({ type: 'subscribed', gameId: 'g-1', revision: 3, streamId: 'restored' });
    expect(component.pendingPromotion()).toBeNull();
    gameLoad.next(gameWith({ ...humanTurn, liveRevision: 3, streamId: 'restored' }));
    component.selectPromotion('Q');
    expect(submit).not.toHaveBeenCalled();
    component.onBoardMoveAttempt({ from: 'b7', to: 'b8', promotionRequired: true });
    component.selectPromotion('Q');
    expect(submit).toHaveBeenCalledExactlyOnceWith('g-1', 'b7', 'b8', 'Q', null);
    component.ngOnDestroy();
  });

  it('keeps the human move draft but blocks writes until a restored snapshot arrives', () => {
    const { component, gameLoad, messages, api } = createSpectator(false);
    const submit = vi.fn(() => of(void 0));
    Object.assign(api, { submitHumanMove: submit });
    const humanTurn = {
      whiteControlType: 'HUMAN' as const,
      whiteModelId: null,
      activeColor: 'WHITE' as const,
    };
    component.ngOnInit();
    messages.next({ type: 'subscribed', gameId: 'g-1', revision: 25, streamId: 'first' });
    gameLoad.next(gameWith({ ...humanTurn, liveRevision: 25, streamId: 'first' }));
    component.humanMove = 'e4';
    component.humanMessage = 'draft';

    messages.next({ type: 'subscribed', gameId: 'g-1', revision: 3, streamId: 'restored' });
    component.submitHumanMove();
    expect(submit).not.toHaveBeenCalled();
    expect(component.currentFen()).toBe(START_FEN);
    expect(component.humanMove).toBe('e4');
    expect(component.humanMessage).toBe('draft');

    gameLoad.next(gameWith({ ...humanTurn, liveRevision: 3, streamId: 'restored' }));
    component.submitHumanMove();
    expect(submit).toHaveBeenCalledExactlyOnceWith('g-1', 'e4', 'draft');
    component.ngOnDestroy();
  });

  it('does not infer an expired deadline from an older server after a fallback read', () => {
    const { component, gameLoad, messages, api } = createSpectator(false);
    Object.assign(api, { getTournament: () => of({ moveTimeoutSeconds: 30 }) });
    component.ngOnInit();
    messages.next({ type: 'subscriptionUnavailable', gameId: 'g-1' });
    gameLoad.next(gameWith({ tournamentId: 't-1', startedAt: '2026-01-01T00:00:00' }));
    expect(component.activeColor()).toBe('WHITE');
    expect(component.moveTimerSeconds()).toBeNull();
    expect(component.displayedChatMode()).toBeNull();
    component.ngOnDestroy();
  });

  it('uses a reconnect watermark to recover the human turn without reusing the expired model timer', () => {
    const { component, gameLoad, messages, reconnected, getGame } = createSpectator();
    component.ngOnInit();
    try {
      gameLoad.next(
        gameWith({
          liveRevision: 1,
          activeColor: 'WHITE',
          turnDeadlineAt: '2026-01-01T00:00:00Z',
          blackControlType: 'HUMAN',
          blackModelId: null,
        }),
      );
      expect(component.moveTimerSeconds()).toBe(0);
      reconnected.next();
      expect(getGame).toHaveBeenCalledTimes(1);
      messages.next({ type: 'subscribed', gameId: 'g-1', revision: 3 });
      expect(getGame).toHaveBeenCalledTimes(2);
      gameLoad.next(
        gameWith({
          liveRevision: 3,
          activeColor: 'BLACK',
          currentFen: AFTER_E4,
          moves: [storedMove(1, 'WHITE', 'e4', AFTER_E4)],
          blackControlType: 'HUMAN',
          blackModelId: null,
        }),
      );
      expect(component.isAwaitingHumanMove()).toBe(true);
      expect(component.moveTimerSeconds()).toBeNull();
    } finally {
      component.ngOnDestroy();
    }
  });

  it('retries a failed revision snapshot with capped backoff while watching for the newest revision', () => {
    vi.useFakeTimers();
    const { component, messages, getGame } = createSpectator(false);
    getGame.mockImplementation(() => throwError(() => ({ status: 503 })));
    component.ngOnInit();
    try {
      // A revisioned event may reach the live stream before the subscribe ack.
      messages.next({ type: 'gameRevision', gameId: 'g-1', revision: 1, changes: [] });
      messages.next({ type: 'subscribed', gameId: 'g-1', revision: 1 });
      expect(getGame).toHaveBeenCalledTimes(1);
      messages.next({ type: 'heartbeat', gameId: 'g-1', revision: 3 });
      vi.advanceTimersByTime(499);
      expect(getGame).toHaveBeenCalledTimes(1);
      vi.advanceTimersByTime(1);
      expect(getGame).toHaveBeenCalledTimes(2);
      vi.advanceTimersByTime(999);
      expect(getGame).toHaveBeenCalledTimes(2);
      getGame.mockImplementation(() => of(gameWith({ liveRevision: 3 })));
      vi.advanceTimersByTime(1);
      expect(getGame).toHaveBeenCalledTimes(3);
      expect(component.game()?.liveRevision).toBe(3);
    } finally {
      component.ngOnDestroy();
      vi.useRealTimers();
    }
  });

  it('refreshes a watched waiting game when bracket advancement assigns a player', () => {
    const { component, gameLoad, messages, getGame } = createSpectator();
    const waiting = gameWith({
      liveRevision: 0,
      statusRevision: 0,
      status: 'WAITING',
      whitePlayerName: '',
    });
    component.ngOnInit();
    gameLoad.next(waiting);
    component.humanMove = 'e4';
    messages.next(revision(1, { type: 'gameDetailsChanged', gameId: 'g-1' }, gameState()));
    expect(getGame).toHaveBeenCalledTimes(2);
    gameLoad.next({ ...waiting, liveRevision: 1, whitePlayerName: 'Winner' });
    expect(component.game()?.whitePlayerName).toBe('Winner');
    expect(component.game()?.statusRevision).toBe(0);
    expect(component.humanMove).toBe('e4');
    component.ngOnDestroy();
  });

  it('refreshes the configured chat settings after same-status recovery and skips unchanged heartbeats', () => {
    const { component, gameLoad, messages, getGame, api } = createSpectator();
    const getTournament = vi
      .fn()
      .mockReturnValueOnce(of({ chatMode: 'OPTIONAL', banterLevel: 'PLAYFUL' }))
      .mockReturnValueOnce(of({ chatMode: 'OFF', banterLevel: null }));
    Object.assign(api, { getTournament });
    const initial = gameWith({ liveRevision: 1, tournamentId: 't-1' });
    component.ngOnInit();
    gameLoad.next(initial);
    expect(component.tournamentBanterLevel()).toBe('PLAYFUL');

    messages.next({ type: 'heartbeat', gameId: 'g-1', revision: 1 });
    expect(getGame).toHaveBeenCalledTimes(1);
    expect(getTournament).toHaveBeenCalledTimes(1);

    messages.next({ type: 'heartbeat', gameId: 'g-1', revision: 2 });
    gameLoad.next({ ...initial, liveRevision: 2 });
    expect(getTournament).toHaveBeenCalledTimes(2);
    expect(component.tournamentChatMode()).toBe('OFF');
    expect(component.tournamentBanterLevel()).toBeNull();
    component.ngOnDestroy();
  });

  it('shows the active turn’s captured chat mode and tone through settings edits and recovery', () => {
    const { component, gameLoad, messages, api } = createSpectator();
    const tournament = new Subject<{ chatMode: string; banterLevel: string }>();
    const turnStartedAt = new Date().toISOString();
    Object.assign(api, { getTournament: () => tournament });
    component.ngOnInit();
    gameLoad.next(gameWith({ liveRevision: 1, tournamentId: 't-1' }));
    messages.next(
      revision(2, gameState({ activeColor: 'WHITE', turnStartedAt, turnChatMode: 'OFF' })),
    );
    tournament.next({ chatMode: 'MANDATORY', banterLevel: 'PLAYFUL' });
    expect(component.displayedChatMode()).toBe('OFF');
    expect(component.displayedBanterLevel()).toBeNull();

    messages.next({ type: 'heartbeat', gameId: 'g-1', revision: 3 });
    gameLoad.next(
      gameWith({
        liveRevision: 3,
        tournamentId: 't-1',
        activeColor: 'WHITE',
        turnStartedAt,
        turnChatMode: 'OPTIONAL',
        turnBanterLevel: 'POLITE',
      }),
    );
    tournament.next({ chatMode: 'MANDATORY', banterLevel: 'PLAYFUL' });
    expect(component.displayedChatMode()).toBe('OPTIONAL');
    expect(component.displayedBanterLevel()).toBe('POLITE');
    component.ngOnDestroy();
  });

  it('uses legacy snapshots without duplicating chat or reviving retry alerts', () => {
    const { component, gameLoad, messages, getGame } = createSpectator(false);
    const saved = {
      id: 'saved-chat',
      moveNumber: 1,
      senderColor: 'WHITE' as const,
      senderModel: 'model-a',
      message: 'hello',
      createdAt: '2026-01-01T00:00:00Z',
    };
    component.ngOnInit();
    try {
      messages.next({ type: 'subscriptionUnavailable', gameId: 'g-1' });
      messages.next({
        type: 'retry',
        gameId: 'g-1',
        color: 'WHITE',
        reason: 'try again',
        attemptNumber: 1,
      });
      messages.next({
        type: 'chat',
        gameId: 'g-1',
        moveNumber: 1,
        senderColor: 'WHITE',
        senderModel: 'model-a',
        message: 'hello',
      });
      messages.next(liveMove(1, 'WHITE', 'e4', AFTER_E4));
      expect(getGame).toHaveBeenCalledTimes(1);
      gameLoad.next(
        gameWith({ moves: [storedMove(1, 'WHITE', 'e4', AFTER_E4)], chatMessages: [saved] }),
      );
      expect(component.chatMessages()).toEqual([saved]);
      expect(component.retryInfo()).toBeNull();
    } finally {
      component.ngOnDestroy();
    }
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

  it('applies a consecutive move, chat and spend once and continues following live moves', () => {
    const { component, gameLoad, messages, getGame } = createSpectator();
    const e4 = storedMove(1, 'WHITE', 'e4', AFTER_E4);
    const e5 = storedMove(1, 'BLACK', 'e5', AFTER_E5);
    const chat = {
      id: 'saved-chat',
      moveNumber: 1,
      senderColor: 'BLACK' as const,
      senderModel: 'model-b',
      message: 'hello',
      createdAt: '2026-01-01T00:00:00Z',
    };
    component.ngOnInit();
    gameLoad.next(
      gameWith({ liveRevision: 1, currentFen: AFTER_E4, activeColor: 'BLACK', moves: [e4] }),
    );
    const saved = revision(
      2,
      { type: 'committedMove', gameId: 'g-1', move: e5, pgn: '1. e4 e5' },
      { type: 'committedChat', gameId: 'g-1', chat },
      gameState({ totalCostUsd: 0.02, activeColor: 'WHITE' }),
    );
    messages.next(saved);
    messages.next(saved);
    expect(component.moves()).toEqual([e4, e5]);
    expect(component.chatMessages()).toEqual([chat]);
    expect(component.currentMoveIndex()).toBe(1);
    expect(component.currentFen()).toBe(AFTER_E5);
    expect(component.game()?.totalCostUsd).toBe(0.02);
    expect(getGame).toHaveBeenCalledTimes(1);
    component.ngOnDestroy();
  });

  it('keeps the position selected while a snapshot is in flight and resumes following when at latest', () => {
    const { component, gameLoad, messages } = createSpectator();
    const first = storedMove(1, 'WHITE', 'e4', AFTER_E4);
    const second = storedMove(1, 'BLACK', 'e5', AFTER_E5);
    const third = storedMove(
      2,
      'WHITE',
      'Nf3',
      'rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2',
    );
    component.ngOnInit();
    const history = [first, second];
    gameLoad.next(gameWith({ liveRevision: 1, currentFen: AFTER_E5, moves: history }));
    messages.next({ type: 'heartbeat', gameId: 'g-1', revision: 2 });
    component.goToMove(0);
    gameLoad.next(gameWith({ liveRevision: 2, currentFen: third.fen, moves: [...history, third] }));
    expect(component.currentMoveIndex()).toBe(0);
    expect(component.currentFen()).toBe(AFTER_E4);

    messages.next({ type: 'subscribed', gameId: 'g-1', revision: 3 });
    component.goToMove(2);
    const fourth = storedMove(
      2,
      'BLACK',
      'Nc6',
      'r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3',
    );
    gameLoad.next(
      gameWith({ liveRevision: 3, currentFen: fourth.fen, moves: [...history, third, fourth] }),
    );
    expect(component.currentMoveIndex()).toBe(3);
    expect(component.currentFen()).toBe(fourth.fen);
    component.ngOnDestroy();
  });

  it('shows the quality an evaluation update decides, for a live move and the summary', () => {
    const { component, gameLoad, messages } = createSpectator();
    component.ngOnInit();
    gameLoad.next(
      gameWith({
        liveRevision: 1,
        currentFen: AFTER_E5,
        moves: [storedMove(1, 'WHITE', 'e4', AFTER_E4)],
        qualitySummary: null,
      }),
    );
    messages.next(
      revision(
        2,
        {
          type: 'committedMove',
          gameId: 'g-1',
          move: storedMove(1, 'BLACK', 'e5', AFTER_E5),
          pgn: '1. e4 e5',
        },
        gameState({ activeColor: 'WHITE' }),
      ),
    );
    expect(component.moves()[1].quality).toBeUndefined();

    messages.next(
      revision(
        3,
        {
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
        },
        gameState(),
      ),
    );

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

  it('applies an evaluation and quality patch to the exact canonical duplicate row', () => {
    const { component, gameLoad, messages } = createSpectator();
    const canonical = { ...storedMove(1, 'WHITE', 'e4', AFTER_E4), id: 'canonical' };
    const duplicate = { ...canonical, id: 'duplicate' };
    const quality = {
      judgement: null,
      centipawnLoss: 0,
      winChanceBefore: 0.5,
      winChanceAfter: 0.5,
      excluded: null,
    };
    component.ngOnInit();
    gameLoad.next(gameWith({ liveRevision: 1, moves: [duplicate, canonical] }));
    messages.next(
      revision(
        2,
        {
          type: 'evaluationUpdate',
          gameId: 'g-1',
          moveNumber: 1,
          color: 'WHITE',
          moveId: canonical.id,
          evaluationCp: 15,
          qualities: [{ moveNumber: 1, color: 'WHITE', moveId: canonical.id, quality }],
        },
        gameState(),
      ),
    );
    expect(component.moves().find((move) => move.id === 'canonical')?.quality).toEqual(quality);
    expect(component.moves().find((move) => move.id === 'canonical')?.evaluationCp).toBe(15);
    expect(component.moves().find((move) => move.id === 'duplicate')?.quality).toBeUndefined();
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

    messages.next(
      revision(
        2,
        { type: 'evaluationUpdate', gameId: 'g-1', moveNumber: 1, color: 'WHITE' },
        gameState(),
      ),
    );

    expect(component.game()?.qualitySummary).toEqual(summary);
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

  it('reconciles terminal details and ignores an older running revision', () => {
    const { component, gameLoad, messages, getGame } = createSpectator();
    component.ngOnInit();
    gameLoad.next(gameWith({ liveRevision: 3, statusRevision: 2 }));
    messages.next(
      revision(
        4,
        {
          type: 'gameStatus',
          gameId: 'g-1',
          status: 'COMPLETED',
          result: 'BLACK_WINS',
          resultReason: 'RESIGNATION',
          statusRevision: 3,
        },
        gameState(),
      ),
    );
    messages.next(
      revision(
        3,
        {
          type: 'gameStatus',
          gameId: 'g-1',
          status: 'IN_PROGRESS',
          statusRevision: 2,
        },
        gameState({ activeColor: 'WHITE' }),
      ),
    );
    expect(component.game()?.status).toBe('COMPLETED');
    expect(getGame).toHaveBeenCalledTimes(2);
    gameLoad.next(resigned({ liveRevision: 4, statusRevision: 3 }));
    expect(component.game()?.resignation).toEqual(resigned().resignation);
    component.ngOnDestroy();
  });

  it('loads the terminal board when a forfeit overtakes a queued capacity read', () => {
    vi.useFakeTimers();
    const { component, gameLoad, messages, getGame } = createSpectator();
    const queued = gameWith({
      liveRevision: 5,
      status: 'PAUSED',
      statusRevision: 3,
      resumeRequestedAt: '2026-01-01T00:00:00Z',
    });
    try {
      component.ngOnInit();
      gameLoad.next(queued);
      vi.advanceTimersByTime(15000);
      expect(getGame).toHaveBeenCalledTimes(2);
      messages.next(
        revision(
          7,
          { type: 'gameStatus', gameId: 'g-1', status: 'FORFEIT', statusRevision: 5 },
          gameState(),
        ),
      );
      gameLoad.next(queued);
      expect(component.game()?.status).toBe('PAUSED');
      vi.advanceTimersByTime(500);
      gameLoad.next(
        gameWith({
          liveRevision: 7,
          statusRevision: 5,
          status: 'FORFEIT',
          currentFen: AFTER_E4,
          moves: [storedMove(1, 'WHITE', 'e4', AFTER_E4)],
        }),
      );
      expect(component.currentFen()).toBe(AFTER_E4);
      expect(component.game()?.status).toBe('FORFEIT');
      vi.advanceTimersByTime(15000);
      expect(getGame).toHaveBeenCalledTimes(3);
    } finally {
      component.ngOnDestroy();
      vi.useRealTimers();
    }
  });

  it('keeps the snapshot deadline across a same-turn refresh and rejects a delayed prior turn on the same side', () => {
    const { component, gameLoad, messages } = createSpectator();
    const deadline = new Date(Date.now() + 100_000).toISOString();
    const current = gameWith({
      liveRevision: 6,
      activeColor: 'WHITE',
      currentFen: AFTER_E5,
      moves: [storedMove(1, 'WHITE', 'e4', AFTER_E4), storedMove(1, 'BLACK', 'e5', AFTER_E5)],
      turnDeadlineAt: deadline,
    });
    component.ngOnInit();
    gameLoad.next(current);
    component.loadGame();
    messages.next(
      revision(4, gameState({ activeColor: 'WHITE', turnDeadlineAt: '2026-01-01T00:00:00Z' })),
    );
    gameLoad.next(current);
    expect(component.moveTimerSeconds()).toBeGreaterThan(0);
    expect(component.game()?.turnDeadlineAt).toBe(deadline);
    component.ngOnDestroy();
  });

  it('reloads when the position a side resigned in is evaluated', () => {
    const { component, gameLoad, messages, getGame } = createSpectator();
    component.ngOnInit();
    gameLoad.next(resigned({ liveRevision: 1 }));
    messages.next(
      revision(
        2,
        {
          type: 'evaluationUpdate',
          gameId: 'g-1',
          moveNumber: 1,
          color: 'WHITE',
          evaluationCp: 30,
        },
        gameState(),
      ),
    );
    expect(getGame).toHaveBeenCalledTimes(2);
    gameLoad.next(
      resigned({
        liveRevision: 2,
        resignation: { ...resigned().resignation!, winChance: 0.46, notLosing: true },
      }),
    );
    expect(component.game()?.resignation?.winChance).toBe(0.46);
    component.ngOnDestroy();
  });
});

describe('GameView resume while saving', () => {
  it.each([false, true])(
    'loads the completed position and moves when a queue poll finds the result (history selected: %s)',
    (reviewingHistory) => {
      vi.useFakeTimers();
      const { component, gameLoad, getGame } = createSpectator();
      try {
        const first = storedMove(1, 'WHITE', 'e4', AFTER_E4);
        const second = storedMove(1, 'BLACK', 'e5', AFTER_E5);
        const queued = gameWith({
          status: 'PAUSED',
          statusRevision: 5,
          liveRevision: 3,
          resumeRequestedAt: '2026-11-17T20:00:00Z',
          runCapacity: { runningGames: 2, maxConcurrentGames: 2 },
          currentFen: AFTER_E4,
          moves: [first],
        });
        component.ngOnInit();
        gameLoad.next(queued);
        if (reviewingHistory) component.currentMoveIndex.set(-1);

        vi.advanceTimersByTime(15000);
        gameLoad.next({ ...queued, runCapacity: { runningGames: 1, maxConcurrentGames: 2 } });
        expect(component.game()?.runCapacity?.runningGames).toBe(1);
        vi.advanceTimersByTime(15000);
        gameLoad.next({
          ...queued,
          status: 'COMPLETED',
          statusRevision: 7,
          liveRevision: 5,
          resumeRequestedAt: null,
          runCapacity: null,
          result: 'DRAW',
          completedAt: '2026-11-17T20:00:05Z',
          currentFen: AFTER_E5,
          moves: [first, second],
        });

        expect(component.game()).toMatchObject({
          status: 'COMPLETED',
          result: 'DRAW',
          resumeRequestedAt: null,
          completedAt: '2026-11-17T20:00:05Z',
          currentFen: AFTER_E5,
        });
        expect(component.moves()).toEqual([first, second]);
        expect(component.currentFen()).toBe(reviewingHistory ? START_FEN : AFTER_E5);
        vi.advanceTimersByTime(15000);
        expect(getGame).toHaveBeenCalledTimes(3);
      } finally {
        component.ngOnDestroy();
        vi.useRealTimers();
      }
    },
  );

  it('reloads the game and shows the replacement queue when Cancel conflicts', () => {
    const message = 'The queued resume has changed; refresh and try again';
    const displayed = gameWith({
      status: 'PAUSED',
      statusRevision: 5,
      resumeRequestedAt: '2026-11-17T20:00:00Z',
    });
    const replacement = {
      ...displayed,
      statusRevision: 7,
      resumeRequestedAt: '2026-11-17T20:01:00Z',
    };
    const cancelResumeRequest = vi.fn(() =>
      throwError(() => ({ status: 409, error: { error: message } })),
    );
    const getGame = vi.fn(() => of(replacement));
    const { component, snackbar } = createComponent({ api: { cancelResumeRequest, getGame } });
    component['gameId'] = displayed.id;
    component.game.set(displayed);

    component.cancelResumeRequest(displayed);

    expect(cancelResumeRequest).toHaveBeenCalledExactlyOnceWith(displayed.id, 5);
    expect(snackbar.open).toHaveBeenCalledWith(message, 'Dismiss', expect.any(Object));
    expect(getGame).toHaveBeenCalledExactlyOnceWith(displayed.id);
    expect(component.game()).toEqual(replacement);
  });

  it('shows the queue and its pause reason and lets the operator cancel it', () => {
    const paused = gameFixture({
      status: 'PAUSED',
      statusDetail: 'Answer cut off',
      statusRevision: 4,
      currentFen: START_FEN,
    });
    const requested = '2026-11-17T20:00:00Z';
    const capacity = { runningGames: 3, maxConcurrentGames: 3 };
    const startGame = vi.fn(() =>
      of({ ...paused, statusRevision: 5, resumeRequestedAt: requested, runCapacity: capacity }),
    );
    const cancelResumeRequest = vi.fn(() => of({ ...paused, statusRevision: 6 }));
    TestBed.configureTestingModule({
      providers: [
        { provide: OpenRouterAccess, useValue: { withKey: (action: () => void) => action() } },
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ id: 'g-1' }) } },
        },
        {
          provide: ApiService,
          useValue: {
            getGame: () => of(paused),
            getTournament: () => of({ maxOutputTokens: 4096 }),
            getGameAttempts: () => of([]),
            getSystemStatus: () => EMPTY,
            startGame,
            cancelResumeRequest,
          },
        },
        {
          provide: WebSocketService,
          useValue: {
            connect: vi.fn(),
            subscribeToGame: vi.fn(),
            unsubscribeFromGame: vi.fn(),
            getMessages: () => of({ type: 'subscribed', gameId: 'g-1', revision: 0 }),
            reconnected: () => EMPTY,
          },
        },
      ],
    });
    const fixture = TestBed.createComponent(GameView);
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    const snack = vi.spyOn(TestBed.inject(MatSnackBar), 'open');
    const resume = [...element.querySelectorAll<HTMLButtonElement>('.admin-controls button')].find(
      (button) => button.textContent?.includes('Resume'),
    )!;
    expect(resume.disabled).toBe(false);
    resume.click();
    fixture.detectChanges();

    expect(startGame).toHaveBeenCalledExactlyOnceWith('g-1', true);
    expect(element.querySelector('app-queued-resume [role="status"]')?.textContent).toMatch(
      /Queued: resumes when a slot frees\s*\(3\/3 running\)/,
    );
    expect(element.textContent).toContain('Answer cut off');
    expect(snack).not.toHaveBeenCalled();
    element.querySelector<HTMLButtonElement>('[aria-label="Cancel queued resume"]')!.click();
    fixture.componentInstance.handleWsMessage({
      type: 'gameStatus',
      gameId: 'g-1',
      status: 'PAUSED',
      statusRevision: 5,
      resumeRequestedAt: requested,
      runCapacity: capacity,
    });
    fixture.detectChanges();
    expect(cancelResumeRequest).toHaveBeenCalledExactlyOnceWith('g-1', 5);
    expect(element.querySelector('app-queued-resume')).toBeNull();
    expect(element.textContent).toContain('Answer cut off');
    fixture.destroy();
  });

  it('blocks Admin Controls Resume until a delayed participant PUT finishes', () => {
    const participantSaved = new Subject<any>();
    const startGame = vi.fn(() => of(void 0));
    TestBed.configureTestingModule({
      providers: [
        { provide: OpenRouterAccess, useValue: { withKey: (action: () => void) => action() } },
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
            getMessages: () => of({ type: 'subscribed', gameId: 'g-1', revision: 0 }),
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
    expect(startGame).toHaveBeenCalledExactlyOnceWith('g-1', true);
    expect(component.game()?.blackReasoningEffort).toBe('low');
    expect(component.savingLimit()).toBe(false);
    expect(resume.disabled).toBe(false);
    fixture.destroy();
  });
});

describe('GameView piece images', () => {
  function renderGame(currentFen = START_FEN) {
    TestBed.configureTestingModule({
      providers: [
        { provide: OpenRouterAccess, useValue: { withKey: (action: () => void) => action() } },
        provideRouter([]),
        {
          provide: ApiService,
          useValue: {
            getGame: () => of(gameWith({ currentFen })),
            getSystemStatus: () => EMPTY,
            getGameAttempts: () => of([]),
            submitHumanMoveCoordinates: vi.fn(() => of({})),
          },
        },
        {
          provide: WebSocketService,
          useValue: {
            connect: vi.fn(),
            subscribeToGame: vi.fn(),
            unsubscribeFromGame: vi.fn(),
            getMessages: () => of({ type: 'subscribed', gameId: 'g-1', revision: 0 }),
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
    return fixture;
  }

  it('lists the three board colours and applies each selection to html', async () => {
    const previousBoard = document.documentElement.getAttribute('data-board');
    const previousSaved = localStorage.getItem('boardTheme');
    localStorage.setItem('boardTheme', 'green');
    const fixture = renderGame();
    try {
      const trigger: HTMLButtonElement = fixture.nativeElement.querySelector(
        '[aria-label="Board colours"]',
      );
      expect(trigger.textContent).toContain('Board');
      expect(trigger.querySelector('mat-icon')?.textContent).toBe('palette');
      for (const [value, label] of [
        ['blue-grey', 'Blue-grey'],
        ['wood', 'Wood'],
        ['green', 'Green'],
      ]) {
        trigger.click();
        fixture.detectChanges();
        await fixture.whenStable();
        const options = Array.from(
          document.querySelectorAll<HTMLButtonElement>('[role="menuitemradio"]'),
        );
        expect(options.map((option) => option.textContent?.replace('check', '').trim())).toEqual([
          'Green',
          'Blue-grey',
          'Wood',
        ]);
        expect(options.map((option) => option.getAttribute('aria-checked'))).toEqual(
          fixture.componentInstance.boardTheme.options.map((option) =>
            String(option.value === fixture.componentInstance.boardTheme.theme()),
          ),
        );
        options.find((option) => option.textContent?.includes(label))!.click();
        fixture.detectChanges();
        await fixture.whenStable();
        expect(document.documentElement.getAttribute('data-board')).toBe(value);
        expect(localStorage.getItem('boardTheme')).toBe(value);
      }
    } finally {
      fixture.destroy();
      if (previousBoard === null) document.documentElement.removeAttribute('data-board');
      else document.documentElement.setAttribute('data-board', previousBoard);
      if (previousSaved === null) localStorage.removeItem('boardTheme');
      else localStorage.setItem('boardTheme', previousSaved);
    }
  });

  function renderPicker(currentFen: string) {
    const fixture = renderGame(currentFen);
    fixture.componentInstance.pendingPromotion.set({ from: 'e7', to: 'e8' });
    fixture.detectChanges();
    return fixture;
  }

  it.each([
    ['WHITE', START_FEN, 'w'],
    ['BLACK', AFTER_E4, 'b'],
  ])('shows %s promotion images with decorative alt and named buttons', (color, fen, prefix) => {
    const fixture = renderPicker(fen);
    fixture.componentInstance.game.set(
      gameWith({
        currentFen: fen,
        [color === 'WHITE' ? 'whiteControlType' : 'blackControlType']: 'HUMAN',
      }),
    );
    fixture.detectChanges();
    const element: HTMLElement = fixture.nativeElement;
    const pieces = Array.from(element.querySelectorAll('.promotion-picker img.promotion-piece'));
    const buttons = Array.from(element.querySelectorAll('.promotion-btn'));

    expect(pieces.map((piece) => piece.getAttribute('src'))).toEqual(
      ['Q', 'R', 'B', 'N'].map((type) => `pieces/cburnett/${prefix}${type}.svg`),
    );
    expect(pieces.map((piece) => piece.getAttribute('alt'))).toEqual(['', '', '', '']);
    expect(buttons.map((button) => button.getAttribute('aria-label'))).toEqual([
      'Promote to queen',
      'Promote to rook',
      'Promote to bishop',
      'Promote to knight',
    ]);
    (buttons[2] as HTMLButtonElement).click();
    expect(TestBed.inject(ApiService).submitHumanMoveCoordinates).toHaveBeenCalledWith(
      'g-1',
      'e7',
      'e8',
      'B',
      null,
    );
    fixture.destroy();
  });

  it('renders a named image per captured piece with the captured side colour', () => {
    const fixture = renderGame();
    const component = fixture.componentInstance;
    component.moves.set([
      storedMove(5, 'WHITE', 'Qxe2', 'rnb1kbnr/ppp1pppp/8/8/8/2N5/PPPPQPPP/R1B1K1NR b KQkq - 0 5'),
    ]);
    component.goLast();
    fixture.detectChanges();
    const element: HTMLElement = fixture.nativeElement;
    const whiteCaptures = element.querySelectorAll('.capture-strip.top img.captured-piece');
    const blackCaptures = element.querySelectorAll('.capture-strip.bottom img.captured-piece');

    expect(whiteCaptures).toHaveLength(2);
    expect(whiteCaptures[0].getAttribute('src')).toBe('pieces/cburnett/bQ.svg');
    expect(whiteCaptures[0].getAttribute('alt')).toBe('black queen');
    expect(whiteCaptures[1].getAttribute('src')).toBe('pieces/cburnett/bP.svg');
    expect(whiteCaptures[1].getAttribute('alt')).toBe('black pawn');
    expect(blackCaptures).toHaveLength(2);
    expect(blackCaptures[0].getAttribute('src')).toBe('pieces/cburnett/wB.svg');
    expect(blackCaptures[0].getAttribute('alt')).toBe('white bishop');
    expect(blackCaptures[1].getAttribute('src')).toBe('pieces/cburnett/wP.svg');
    expect(blackCaptures[1].getAttribute('alt')).toBe('white pawn');
    expect(element.querySelector('.capture-empty')).toBeNull();

    component.goFirst();
    fixture.detectChanges();
    expect(element.querySelectorAll('img.captured-piece')).toHaveLength(0);
    expect(
      Array.from(element.querySelectorAll('.capture-empty'), (el) => el.textContent?.trim()),
    ).toEqual(['None', 'None']);
    fixture.destroy();
  });

  it.each(['CANCELLED', 'COMPLETED', 'FORFEIT'] as Game['status'][])(
    'closes the open picker on live %s and prevents a stale promotion click',
    (status) => {
      const fixture = renderPicker(START_FEN);
      const component = fixture.componentInstance;
      component.game.set(gameWith({ whiteControlType: 'HUMAN' }));
      component.onBoardMoveAttempt({ from: 'e7', to: 'e8', promotionRequired: true });
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelector('.promotion-picker')).not.toBeNull();
      component.handleWsMessage({ type: 'gameStatus', gameId: 'g-1', status, statusRevision: 1 });
      fixture.detectChanges();
      expect(component.pendingPromotion()).toBeNull();
      expect(fixture.nativeElement.querySelector('.promotion-picker')).toBeNull();
      component.selectPromotion('Q');
      component.onBoardMoveAttempt({ from: 'e2', to: 'e4', promotionRequired: false });
      expect(TestBed.inject(ApiService).submitHumanMoveCoordinates).not.toHaveBeenCalled();
      fixture.destroy();
    },
  );

  it('closes an open picker when a reload discovers cancellation', () => {
    const fixture = renderPicker(START_FEN);
    vi.spyOn(TestBed.inject(ApiService), 'getGame').mockReturnValue(
      of(gameWith({ status: 'CANCELLED', statusRevision: 1 })),
    );
    fixture.componentInstance.loadGame();
    fixture.detectChanges();
    expect(fixture.componentInstance.pendingPromotion()).toBeNull();
    expect(fixture.nativeElement.querySelector('.promotion-picker')).toBeNull();
    fixture.destroy();
  });

  it.each(['PAUSED', 'WAITING', 'IN_PROGRESS'] as Game['status'][])(
    'guards promotion and coordinates outside an active human turn: %s',
    (status) => {
      const fixture = renderPicker(START_FEN);
      const component = fixture.componentInstance;
      component.game.set(gameWith({ status })); // AI to move, even if IN_PROGRESS
      component.selectPromotion('Q');
      component.onBoardMoveAttempt({ from: 'e2', to: 'e4', promotionRequired: false });
      component.onBoardMoveAttempt({ from: 'e7', to: 'e8', promotionRequired: true });
      expect(component.pendingPromotion()).toBeNull();
      expect(TestBed.inject(ApiService).submitHumanMoveCoordinates).not.toHaveBeenCalled();
      fixture.destroy();
    },
  );

  it('submits a selected promotion during an active human turn', () => {
    const fixture = renderPicker(START_FEN);
    const component = fixture.componentInstance;
    component.game.set(gameWith({ whiteControlType: 'HUMAN' }));
    component.selectPromotion('Q');
    expect(TestBed.inject(ApiService).submitHumanMoveCoordinates).toHaveBeenCalledExactlyOnceWith(
      'g-1',
      'e7',
      'e8',
      'Q',
      null,
    );
    fixture.destroy();
  });
});

describe('GameView adjudication and missed mate updates', () => {
  it('reloads adjudication evidence when the game completes live', () => {
    const { component, gameLoad, messages, getGame } = createSpectator();
    component.ngOnInit();
    gameLoad.next(gameWith({ liveRevision: 1 }));
    messages.next(
      revision(
        2,
        {
          type: 'gameStatus',
          gameId: 'g-1',
          status: 'COMPLETED',
          result: 'WHITE_WINS',
          resultReason: 'ADJUDICATION',
          statusRevision: 3,
        },
        gameState(),
      ),
    );
    gameLoad.next(
      gameWith({
        status: 'COMPLETED',
        result: 'WHITE_WINS',
        resultReason: 'ADJUDICATION',
        statusRevision: 3,
        liveRevision: 2,
        adjudication: { color: 'WHITE', plies: 10, evaluationMate: 4, configurationRevision: 2 },
      }),
    );
    expect(getGame).toHaveBeenCalledTimes(2);
    expect(component.game()?.adjudication?.evaluationMate).toBe(4);
    expect(component.game()?.adjudication?.configurationRevision).toBe(2);
    component.ngOnDestroy();
  });

  it('keeps the missed-mate flag from live evaluation qualities for the move list', () => {
    const { component } = createComponent();
    component.game.set(gameWith());
    component.moves.set([storedMove(1, 'WHITE', 'e4', AFTER_E4)]);
    component.handleWsMessage({
      type: 'evaluationUpdate',
      gameId: 'g-1',
      moveNumber: 1,
      color: 'WHITE',
      evaluationCp: 1000,
      qualities: [
        {
          moveNumber: 1,
          color: 'WHITE',
          quality: {
            judgement: null,
            centipawnLoss: 0,
            winChanceBefore: 1,
            winChanceAfter: 0.976,
            excluded: null,
            missedMate: true,
            missedMateIn: 2,
          },
        },
      ],
    });
    expect(component.moves()[0].quality?.missedMate).toBe(true);
    expect(component.moves()[0].quality?.missedMateIn).toBe(2);
  });
});

describe('GameView cancellation', () => {
  it('applies a late discarded attempt and spend without changing the cancelled status revision', () => {
    const { component, gameLoad, messages, getGame } = createSpectator();
    const history = {
      currentFen: AFTER_E5,
      moves: [storedMove(1, 'WHITE', 'e4', AFTER_E4), storedMove(1, 'BLACK', 'e5', AFTER_E5)],
    };
    component.ngOnInit();
    gameLoad.next(
      gameWith({
        ...history,
        liveRevision: 3,
        statusRevision: 4,
        status: 'CANCELLED',
        totalCostUsd: 1.25,
      }),
    );
    component.goToMove(0);
    const discarded = attemptFixture({ outcome: 'DISCARDED', costUsd: 0.25 });
    messages.next(
      revision(
        4,
        { type: 'committedAttempt', gameId: 'g-1', attempt: discarded },
        gameState({ totalCostUsd: 1.5 }),
      ),
    );
    expect(getGame).toHaveBeenCalledTimes(1);
    expect(component.game()?.status).toBe('CANCELLED');
    expect(component.game()?.statusRevision).toBe(4);
    expect(component.game()?.totalCostUsd).toBe(1.5);
    expect(component.attempts()).toEqual([discarded]);
    expect(component.currentMoveIndex()).toBe(0);
    expect(component.currentFen()).toBe(AFTER_E4);
    expect(component.isAwaitingHumanMove()).toBe(false);
    expect(component.activeColor()).toBeNull();
    component.ngOnDestroy();
  });

  it('applies the cancelled revision, clears turn controls and ignores a delayed running status or reload', () => {
    const { component, gameLoad } = createSpectator();
    component.ngOnInit();
    gameLoad.next(gameWith({ statusRevision: 3 }));
    component.handleWsMessage({
      type: 'gameStatus',
      gameId: 'g-1',
      status: 'CANCELLED',
      statusRevision: 4,
    });
    expect(component.game()?.status).toBe('CANCELLED');
    expect(component.activeColor()).toBeNull();
    expect(component.feedEvents().find((event) => event.type === 'result')?.label).toBe(
      'Cancelled',
    );
    component.handleWsMessage({
      type: 'gameStatus',
      gameId: 'g-1',
      status: 'IN_PROGRESS',
      statusRevision: 3,
    });
    component.loadGame();
    gameLoad.next(gameWith({ statusRevision: 3 }));
    expect(component.game()?.status).toBe('CANCELLED');
    expect(component.game()?.result).toBeNull();
    component.ngOnDestroy();
  });
});
