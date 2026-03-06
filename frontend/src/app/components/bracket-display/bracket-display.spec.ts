import { BracketDisplay } from './bracket-display';
import { Game } from '../../models/tournament.model';

describe('BracketDisplay', () => {
  it('orders larger rounds before smaller rounds', () => {
    const component = new BracketDisplay();

    component.games = [
      game('final-1', 'Final', 0),
      game('r32-1', 'Round of 32', 0),
      game('semi-1', 'Semifinal', 0),
      game('qf-1', 'Quarterfinal', 0),
      game('r16-1', 'Round of 16', 0),
    ];

    expect(component.rounds().map((round) => round.name)).toEqual([
      'Round of 32',
      'Round of 16',
      'Quarterfinal',
      'Semifinal',
      'Final',
    ]);
  });

  it('groups multiple games in the same series into one bracket card', () => {
    const component = new BracketDisplay();

    component.games = [
      game('final-1', 'Final', 0, {
        seriesId: 'series-final',
        seriesBestOf: 3,
        status: 'COMPLETED',
        result: 'WHITE_WINS',
      }),
      game('final-2', 'Final', 0, {
        seriesId: 'series-final',
        seriesBestOf: 3,
        seriesGameNumber: 2,
        whitePlayerName: 'Black',
        blackPlayerName: 'White',
        whiteModelId: 'model-b',
        blackModelId: 'model-a',
      }),
    ];

    const card = component.rounds()[0].games[0] as any;
    expect(component.rounds()[0].games).toHaveLength(1);
    expect(card.whiteWins).toBe(1);
    expect(card.blackWins).toBe(0);
    expect(card.currentGame.seriesGameNumber).toBe(2);
  });

  it('derives live series state from the current game status and fen', () => {
    const component = new BracketDisplay();

    component.games = [
      game('semi-1', 'Semifinal', 0, {
        seriesId: 'series-semi',
        seriesBestOf: 3,
        status: 'IN_PROGRESS',
        currentFen: 'rnbqkbnr/pppp1ppp/8/4p3/8/8/PPPPPPPP/RNBQKBNR b KQkq - 0 1',
      }),
    ];

    const card = component.rounds()[0].games[0] as any;
    expect(card.stateLabel).toBe('Live');
    expect(card.stateTone).toBe('live');
    expect(card.detailLabel).toContain('Black to move');
  });
});

function game(
  id: string,
  bracketRound: string,
  bracketPosition: number,
  overrides: Partial<Game> = {},
): Game {
  return {
    id,
    tournamentId: null,
    whitePlayerName: 'White',
    whiteModelId: 'model-a',
    blackPlayerName: 'Black',
    blackModelId: 'model-b',
    status: 'WAITING',
    result: null,
    resultReason: null,
    pgn: null,
    currentFen: 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1',
    bracketRound,
    bracketPosition,
    seriesId: id,
    seriesGameNumber: 1,
    seriesBestOf: 1,
    totalCostUsd: 0,
    createdAt: new Date().toISOString(),
    startedAt: null,
    completedAt: null,
    moves: [],
    chatMessages: [],
    ...overrides,
  };
}
