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
});

function game(id: string, bracketRound: string, bracketPosition: number): Game {
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
    totalCostUsd: 0,
    createdAt: new Date().toISOString(),
    startedAt: null,
    completedAt: null,
    moves: [],
    chatMessages: [],
  };
}
