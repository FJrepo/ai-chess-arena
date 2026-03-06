import { GameView } from './game-view';

describe('GameView', () => {
  it('hides the evaluation bar when stockfish is unavailable', () => {
    const component = new GameView({} as any, {} as any, {} as any, {} as any, {} as any);

    component.stockfishAvailable.set(false);
    component.stockfishReason.set('Stockfish missing');

    expect(component.showEvaluationBar()).toBe(false);
    expect(component.evaluationStatusMessage()).toBe('Evaluation unavailable');
    expect(component.evaluationStatusLabel()).toBe('Eval unavailable');
  });

  it('builds a unified timeline from moves, chat, and result state', () => {
    const component = new GameView({} as any, {} as any, {} as any, {} as any, {} as any);
    component.game.set({
      id: 'g-1',
      tournamentId: 't-1',
      whitePlayerName: 'Alpha',
      whiteModelId: 'model-a',
      blackPlayerName: 'Beta',
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

    const events = component.timelineEvents();

    expect(events.map((event) => event.type)).toEqual(['result', 'chat', 'move', 'start']);
    expect(events[1].detail).toContain('Alpha: Good luck.');
    expect(events[2].detail).toContain('after 1 retry');
    expect(events[3].label).toBe('Game Started');
  });
});
