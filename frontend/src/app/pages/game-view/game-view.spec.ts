import { GameView } from './game-view';

describe('GameView', () => {
  it('hides the evaluation bar when stockfish is unavailable', () => {
    const component = new GameView({} as any, {} as any, {} as any, {} as any, {} as any);

    component.stockfishAvailable.set(false);
    component.stockfishReason.set('Stockfish missing');

    expect(component.showEvaluationBar()).toBe(false);
    expect(component.evaluationStatusMessage()).toBe('Evaluation unavailable');
  });
});
