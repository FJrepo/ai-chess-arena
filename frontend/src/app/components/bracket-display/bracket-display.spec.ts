import { ComponentFixture, TestBed } from '@angular/core/testing';
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

  it('uses the shared game status labels for finished and paused series', () => {
    const component = new BracketDisplay();

    component.games = [
      game('semi-1', 'Semifinal', 0, { status: 'COMPLETED', result: 'WHITE_WINS' }),
      game('semi-2', 'Semifinal', 1, { status: 'PAUSED' }),
    ];

    const [completed, paused] = component.rounds()[0].games;
    expect(completed.stateLabel).toBe('Completed');
    expect(completed.stateTone).toBe('complete');
    expect(paused.stateLabel).toBe('Paused');
    expect(paused.stateTone).toBe('paused');
  });
});

describe('BracketDisplay rendering', () => {
  beforeEach(() => {
    // jsdom has no ResizeObserver; the connector layout only needs observe/disconnect.
    vi.stubGlobal(
      'ResizeObserver',
      class {
        observe(): void {}
        disconnect(): void {}
      },
    );
  });

  afterEach(() => vi.unstubAllGlobals());

  function render(games: Game[]): ComponentFixture<BracketDisplay> {
    const fixture = TestBed.createComponent(BracketDisplay);
    fixture.componentRef.setInput('games', games);
    fixture.detectChanges();
    return fixture;
  }

  function scores(fixture: ComponentFixture<BracketDisplay>, seriesId: string): string[] {
    const card = fixture.nativeElement.querySelector(`[data-game-id="${seriesId}"]`);
    return [...card.querySelectorAll('.player-score')].map((el) => el.textContent.trim());
  }

  it('hides the score of a series that has not been played yet', () => {
    const fixture = render([game('semi-1', 'Semifinal', 0, { seriesBestOf: 3 })]);

    expect(scores(fixture, 'semi-1')).toEqual([]);
  });

  it('keeps the series score when the next game of a series is ready', () => {
    const fixture = render([
      game('semi-1', 'Semifinal', 0, {
        seriesId: 'series-semi',
        seriesBestOf: 3,
        status: 'COMPLETED',
        result: 'WHITE_WINS',
      }),
      game('semi-2', 'Semifinal', 0, {
        seriesId: 'series-semi',
        seriesBestOf: 3,
        seriesGameNumber: 2,
        whitePlayerName: 'Black',
        blackPlayerName: 'White',
      }),
    ]);

    expect(scores(fixture, 'series-semi')).toEqual(['1', '0']);
  });

  it('keeps a level score once a game of the series has been played', () => {
    const fixture = render([
      game('semi-1', 'Semifinal', 0, {
        seriesId: 'series-semi',
        seriesBestOf: 3,
        status: 'COMPLETED',
        result: 'DRAW',
      }),
      game('semi-2', 'Semifinal', 0, {
        seriesId: 'series-semi',
        seriesBestOf: 3,
        seriesGameNumber: 2,
      }),
    ]);

    expect(scores(fixture, 'series-semi')).toEqual(['0', '0']);
  });

  it('hides the score of a pending series without players', () => {
    const fixture = render([
      game('final-1', 'Final', 0, { whitePlayerName: null, blackPlayerName: null }),
    ]);

    expect(scores(fixture, 'final-1')).toEqual([]);
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
    whiteControlType: 'AI',
    whiteModelId: 'model-a',
    blackPlayerName: 'Black',
    blackControlType: 'AI',
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
