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
        ...swapped(),
        seriesId: 'series-final',
        seriesBestOf: 3,
        seriesGameNumber: 2,
      }),
    ];

    const card = component.rounds()[0].games[0];
    expect(component.rounds()[0].games).toHaveLength(1);
    // Game 2 swaps colours: the card shows Game 2's sides, and Game 1's win stays with "White".
    expect(card.whitePlayerName).toBe('Black');
    expect(card.whiteModelId).toBe('model-b');
    expect(card.blackPlayerName).toBe('White');
    expect(card.blackModelId).toBe('model-a');
    expect(card.whiteWins).toBe(0);
    expect(card.blackWins).toBe(1);
    expect(card.currentGame.seriesGameNumber).toBe(2);
  });

  it('attributes series wins by participant across colour swaps', () => {
    const component = new BracketDisplay();

    component.games = [
      game('semi-1', 'Semifinal', 0, {
        seriesId: 'series-semi',
        seriesBestOf: 3,
        status: 'COMPLETED',
        result: 'WHITE_WINS',
      }),
      game('semi-2', 'Semifinal', 0, {
        ...swapped(),
        seriesId: 'series-semi',
        seriesBestOf: 3,
        seriesGameNumber: 2,
        status: 'COMPLETED',
        result: 'WHITE_WINS',
      }),
      game('semi-3', 'Semifinal', 0, {
        seriesId: 'series-semi',
        seriesBestOf: 3,
        seriesGameNumber: 3,
        status: 'IN_PROGRESS',
      }),
    ];

    const card = component.rounds()[0].games[0];
    expect(card.whitePlayerName).toBe('White');
    expect(card.blackPlayerName).toBe('Black');
    expect(card.whiteWins).toBe(1);
    expect(card.blackWins).toBe(1);
  });

  it('only offers to start a waiting series when both players are set', () => {
    const component = new BracketDisplay();

    component.games = [
      game('semi-1', 'Semifinal', 0),
      game('final-1', 'Final', 0, {
        blackPlayerName: null,
        blackModelId: null,
        blackParticipantId: null,
      }),
    ];

    const [semi] = component.rounds()[0].games;
    const [final] = component.rounds()[1].games;
    expect(semi.stateTone).toBe('ready');
    expect(semi.detailLabel).toBe("Game 1 can start when you're ready.");
    expect(final.stateTone).toBe('pending');
    expect(final.detailLabel).toBe('Waiting for the previous round.');
  });

  it('keeps the current game for start logic and the last finished game for review', () => {
    const component = new BracketDisplay();
    const first = game('semi-1', 'Semifinal', 0, {
      seriesId: 'series-semi',
      seriesBestOf: 3,
      status: 'COMPLETED',
      result: 'BLACK_WINS',
    });
    const second = game('semi-2', 'Semifinal', 0, {
      ...swapped(),
      seriesId: 'series-semi',
      seriesBestOf: 3,
      seriesGameNumber: 2,
    });

    component.games = [first, second];

    const card = component.rounds()[0].games[0];
    expect(card.currentGame).toBe(second);
    expect(card.lastFinishedGame).toBe(first);
    expect(card.stateTone).toBe('ready');
    expect(component.canBattle(card)).toBe(true);
    expect(component.hasReviewableGame(card)).toBe(true);
    expect(card.detailLabel).toBe('Game 2 is next. Review the previous result below.');
    expect(component.getResultText(card)).toBe('Black wins 1-0');
  });

  it('has nothing to review before the first game of a series is finished', () => {
    const component = new BracketDisplay();

    component.games = [game('semi-1', 'Semifinal', 0, { status: 'IN_PROGRESS' })];

    const card = component.rounds()[0].games[0];
    expect(card.lastFinishedGame).toBeNull();
    expect(component.hasReviewableGame(card)).toBe(false);
    expect(component.getResultText(card)).toBe('');
  });

  it('says the Final winner wins the tournament, also on the draw policy', () => {
    const component = new BracketDisplay();

    component.games = [
      game('final-1', 'Final', 0, {
        seriesId: 'series-final',
        seriesBestOf: 3,
        status: 'COMPLETED',
        result: 'WHITE_WINS',
      }),
      game('final-2', 'Final', 0, {
        ...swapped(),
        seriesId: 'series-final',
        seriesBestOf: 3,
        seriesGameNumber: 2,
        status: 'COMPLETED',
        result: 'BLACK_WINS',
        ...advancedTo('White'),
      }),
      game('semi-1', 'Semifinal', 0, {
        status: 'COMPLETED',
        result: 'DRAW',
        ...advancedTo('Black'),
      }),
      game('semi-2', 'Semifinal', 1, {
        status: 'COMPLETED',
        result: 'WHITE_WINS',
        ...advancedTo('White'),
      }),
    ];

    const [semiDraw, semiWin] = component.rounds()[0].games;
    const [final] = component.rounds()[1].games;
    expect(final.detailLabel).toBe('White wins the tournament 2\u20110');
    expect(component.getResultText(final)).toBe('White wins the tournament 2\u20110');
    expect(semiDraw.detailLabel).toBe('Black advances on the draw policy\u00a0(0\u20110)');
    expect(semiWin.detailLabel).toBe('White advances 1\u20110');

    component.games = [
      game('final-1', 'Final', 0, {
        seriesId: 'series-final',
        seriesBestOf: 2,
        status: 'COMPLETED',
        result: 'WHITE_WINS',
      }),
      game('final-2', 'Final', 0, {
        ...swapped(),
        seriesId: 'series-final',
        seriesBestOf: 2,
        seriesGameNumber: 2,
        status: 'COMPLETED',
        result: 'WHITE_WINS',
        ...advancedTo('Black'),
      }),
    ];

    const levelFinal = component.rounds()[0].games[0];
    expect(levelFinal.detailLabel).toBe(
      'Black wins the tournament on the draw policy\u00a0(1\u20111)',
    );
  });

  it('keeps the draw-policy score from breaking across lines', () => {
    const component = new BracketDisplay();

    component.games = [
      game('semi-1', 'Semifinal', 0, {
        status: 'COMPLETED',
        result: 'DRAW',
        ...advancedTo('White'),
      }),
    ];

    const label = component.rounds()[0].games[0].detailLabel;
    // No ordinary space before "(" and no ordinary hyphen inside the score.
    expect(label).not.toMatch(/ \(/);
    expect(label).toMatch(/\(\d+\u2011\d+\)$/);
  });

  it('keeps two participants with the same name apart after a colour swap', () => {
    const component = new BracketDisplay();
    const first = { whiteParticipantId: 'p-1', blackParticipantId: 'p-2' };
    const second = { whiteParticipantId: 'p-2', blackParticipantId: 'p-1' };
    const same = {
      whitePlayerName: 'Same',
      blackPlayerName: 'Same',
      seriesId: 'series-semi',
      seriesBestOf: 3,
    };

    component.games = [
      game('semi-1', 'Semifinal', 0, {
        ...same,
        ...first,
        status: 'COMPLETED',
        result: 'WHITE_WINS',
      }),
      game('semi-2', 'Semifinal', 0, { ...same, ...second, seriesGameNumber: 2 }),
    ];

    const ready = component.rounds()[0].games[0];
    // p-1 won Game 1 as white and plays black in Game 2.
    expect(ready.whiteParticipantId).toBe('p-2');
    expect(ready.whiteWins).toBe(0);
    expect(ready.blackWins).toBe(1);
    expect(component.getResultText(ready)).toBe('Same wins 1-0');

    component.games = [
      game('semi-1', 'Semifinal', 0, {
        ...same,
        ...first,
        status: 'COMPLETED',
        result: 'WHITE_WINS',
      }),
      game('semi-2', 'Semifinal', 0, {
        ...same,
        ...second,
        seriesGameNumber: 2,
        status: 'COMPLETED',
        result: 'BLACK_WINS',
        advancedPlayerName: 'Same',
        advancedParticipantId: 'p-1',
      }),
    ];

    const done = component.rounds()[0].games[0];
    expect(done.whiteWins).toBe(0);
    expect(done.blackWins).toBe(2);
    expect(component.isSeriesWinner(done, 'black')).toBe(true);
    expect(component.isSeriesWinner(done, 'white')).toBe(false);
    expect(done.detailLabel).toBe('Same advances 2\u20110');
  });

  it('takes the champion model from the Final side that advanced', () => {
    const component = new BracketDisplay();
    component.champion = 'Same';
    component.games = [
      game('final-1', 'Final', 0, {
        whitePlayerName: 'Same',
        whiteParticipantId: 'p-1',
        blackPlayerName: 'Same',
        blackParticipantId: 'p-2',
        status: 'COMPLETED',
        result: 'BLACK_WINS',
        advancedPlayerName: 'Same',
        advancedParticipantId: 'p-2',
      }),
    ];

    expect(component.championModelId()).toBe('model-b');
  });

  it('falls back to names when the data has no participant ids', () => {
    const component = new BracketDisplay();
    const noIds = { whiteParticipantId: undefined, blackParticipantId: undefined };

    component.games = [
      game('semi-1', 'Semifinal', 0, {
        ...noIds,
        seriesId: 'series-semi',
        seriesBestOf: 3,
        status: 'COMPLETED',
        result: 'WHITE_WINS',
      }),
      game('semi-2', 'Semifinal', 0, {
        ...swapped(),
        ...noIds,
        seriesId: 'series-semi',
        seriesBestOf: 3,
        seriesGameNumber: 2,
        status: 'COMPLETED',
        result: 'BLACK_WINS',
        advancedPlayerName: 'White',
      }),
    ];

    const card = component.rounds()[0].games[0];
    expect(card.whitePlayerName).toBe('Black');
    expect(card.blackWins).toBe(2);
    expect(component.isSeriesWinner(card, 'black')).toBe(true);
    expect(card.detailLabel).toBe('White advances 2\u20110');
  });

  it('does not count a drawn tiebreak game beyond best-of-N', () => {
    const component = new BracketDisplay();

    component.games = [
      game('semi-1', 'Semifinal', 0, { status: 'COMPLETED', result: 'DRAW' }),
      game('semi-2', 'Semifinal', 0, {
        ...swapped(),
        seriesId: 'semi-1',
        seriesGameNumber: 2,
        status: 'COMPLETED',
        result: 'DRAW',
      }),
      game('semi-3', 'Semifinal', 0, { seriesId: 'semi-1', seriesGameNumber: 3 }),
    ];

    const card = component.rounds()[0].games[0];
    expect(card.whiteWins).toBe(0);
    expect(card.blackWins).toBe(0);
    expect(card.tiebreakWinner).toBeNull();
    expect(card.isComplete).toBe(false);
    expect(card.stateTone).toBe('ready');
    expect(card.currentGame.seriesGameNumber).toBe(3);
  });

  it('describes a decisive tiebreak without counting it into the series score', () => {
    const component = new BracketDisplay();
    const series = (round: string) => [
      game(`${round}-1`, round, 0, {
        seriesId: round,
        seriesBestOf: 3,
        status: 'COMPLETED',
        result: 'WHITE_WINS',
      }),
      game(`${round}-2`, round, 0, {
        ...swapped(),
        seriesId: round,
        seriesBestOf: 3,
        seriesGameNumber: 2,
        status: 'COMPLETED',
        result: 'WHITE_WINS',
      }),
      game(`${round}-3`, round, 0, {
        seriesId: round,
        seriesBestOf: 3,
        seriesGameNumber: 3,
        status: 'COMPLETED',
        result: 'DRAW',
      }),
      game(`${round}-4`, round, 0, {
        ...swapped(),
        seriesId: round,
        seriesBestOf: 3,
        seriesGameNumber: 4,
        status: 'COMPLETED',
        result: 'WHITE_WINS',
        ...advancedTo('Black'),
      }),
    ];

    component.games = [...series('Semifinal'), ...series('Final')];

    const [semi] = component.rounds()[0].games;
    const [final] = component.rounds()[1].games;
    expect([semi.whiteWins, semi.blackWins]).toEqual([1, 1]);
    expect(semi.detailLabel).toBe('Black wins the tiebreak\u00a0(1\u20111 after 3 games)');
    expect(semi.detailLabel).not.toContain('draw policy');
    expect(component.isSeriesWinner(semi, 'white')).toBe(true);
    expect(final.detailLabel).toBe(
      'Black wins the tournament on the tiebreak\u00a0(1\u20111 after 3 games)',
    );
  });

  it('treats only a side without a participant as a bye', () => {
    const component = new BracketDisplay();

    component.games = [
      game('qf-1', 'Quarterfinal', 0, {
        blackPlayerName: 'BYE',
        blackParticipantId: null,
        blackModelId: null,
        status: 'COMPLETED',
        result: 'WHITE_WINS',
        ...advancedTo('White'),
      }),
      game('qf-2', 'Quarterfinal', 1, {
        blackPlayerName: 'BYE',
        blackParticipantId: 'p-bye',
      }),
    ];

    const [bye, realBye] = component.rounds()[0].games;
    expect(bye.stateTone).toBe('bye');
    expect(realBye.stateTone).toBe('ready');
    expect(component.canBattle(realBye)).toBe(true);
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

  function card(fixture: ComponentFixture<BracketDisplay>, seriesId: string): HTMLElement {
    return fixture.nativeElement.querySelector(`[data-game-id="${seriesId}"]`);
  }

  function names(fixture: ComponentFixture<BracketDisplay>, seriesId: string): string[] {
    return [...card(fixture, seriesId).querySelectorAll('.player-name')].map(
      (el) => el.textContent?.trim() ?? '',
    );
  }

  function context(fixture: ComponentFixture<BracketDisplay>, seriesId: string): string {
    return card(fixture, seriesId).querySelector('.series-context')?.textContent?.trim() ?? '';
  }

  function button(
    fixture: ComponentFixture<BracketDisplay>,
    seriesId: string,
    label: string,
  ): HTMLButtonElement {
    const match = [...card(fixture, seriesId).querySelectorAll('button')].find(
      (el) => el.textContent?.replace(/\s+/g, ' ').trim().endsWith(label) ?? false,
    );
    if (!match) throw new Error(`No "${label}" button on ${seriesId}`);
    return match;
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
        ...swapped(),
        seriesId: 'series-semi',
        seriesBestOf: 3,
        seriesGameNumber: 2,
      }),
    ]);

    // Game 2 swaps colours: "Black" now plays white, and "White" keeps the Game 1 win.
    expect(names(fixture, 'series-semi')).toEqual(['Black', 'White']);
    expect(scores(fixture, 'series-semi')).toEqual(['0', '1']);
  });

  it('lets a ready Game 2 start and review Game 1', () => {
    const first = game('semi-1', 'Semifinal', 0, {
      seriesId: 'series-semi',
      seriesBestOf: 3,
      status: 'COMPLETED',
      result: 'WHITE_WINS',
    });
    const second = game('semi-2', 'Semifinal', 0, {
      ...swapped(),
      seriesId: 'series-semi',
      seriesBestOf: 3,
      seriesGameNumber: 2,
    });
    const fixture = render([first, second]);
    const viewed: Game[] = [];
    const battled: Game[] = [];
    fixture.componentInstance.view.subscribe((g) => viewed.push(g));
    fixture.componentInstance.battle.subscribe((g) => battled.push(g));

    expect(context(fixture, 'series-semi')).toBe(
      'Game 2 is next. Review the previous result below.',
    );
    button(fixture, 'series-semi', 'Review Previous').click();
    button(fixture, 'series-semi', 'Battle').click();

    expect(viewed).toEqual([first]);
    expect(battled).toEqual([second]);
  });

  it('reviews the last finished game of a completed series', () => {
    const first = game('final-1', 'Final', 0, {
      seriesId: 'series-final',
      seriesBestOf: 3,
      status: 'COMPLETED',
      result: 'WHITE_WINS',
    });
    const second = game('final-2', 'Final', 0, {
      ...swapped(),
      seriesId: 'series-final',
      seriesBestOf: 3,
      seriesGameNumber: 2,
      status: 'COMPLETED',
      result: 'BLACK_WINS',
      ...advancedTo('White'),
    });
    const fixture = render([first, second]);
    const viewed: Game[] = [];
    fixture.componentInstance.view.subscribe((g) => viewed.push(g));

    button(fixture, 'series-final', 'Review').click();

    expect(viewed).toEqual([second]);
  });

  it('does not offer to start a pending Final', () => {
    const fixture = render([
      game('final-1', 'Final', 0, {
        blackPlayerName: null,
        blackModelId: null,
        blackParticipantId: null,
      }),
    ]);

    expect(context(fixture, 'final-1')).toBe('Waiting for the previous round.');
    expect(fixture.nativeElement.textContent).not.toContain('can start');
  });

  it('renders a draw-policy Final as a tournament win with an unbreakable score', () => {
    const fixture = render([
      game('final-1', 'Final', 0, {
        status: 'COMPLETED',
        result: 'DRAW',
        ...advancedTo('Black'),
      }),
    ]);

    expect(context(fixture, 'final-1')).toBe(
      'Black wins the tournament on the draw policy\u00a0(0\u20110)',
    );
  });

  it('renders the result of a decisive Final', () => {
    const fixture = render([
      game('final-1', 'Final', 0, {
        status: 'COMPLETED',
        result: 'WHITE_WINS',
        ...advancedTo('White'),
      }),
    ]);

    expect(context(fixture, 'final-1')).toBe('White wins the tournament 1\u20110');
    expect(scores(fixture, 'final-1')).toEqual(['1', '0']);
  });

  it('renders the result of a Final decided by forfeit', () => {
    const fixture = render([
      game('final-1', 'Final', 0, {
        status: 'FORFEIT',
        result: 'BLACK_FORFEIT',
        resultReason: 'MAX_RETRIES_EXCEEDED',
        ...advancedTo('White'),
      }),
    ]);

    expect(context(fixture, 'final-1')).toBe('White wins the tournament 1\u20110');
  });

  it('shows a genuine bye without Battle or scores', () => {
    const fixture = render([
      game('qf-1', 'Quarterfinal', 0, {
        blackPlayerName: 'BYE',
        blackParticipantId: null,
        blackModelId: null,
        status: 'COMPLETED',
        result: 'WHITE_WINS',
        ...advancedTo('White'),
      }),
    ]);

    expect(context(fixture, 'qf-1')).toBe('Auto-advanced without a played game.');
    expect(scores(fixture, 'qf-1')).toEqual([]);
    expect(() => button(fixture, 'qf-1', 'Battle')).toThrow();
  });

  it('lets a real participant named "BYE" play', () => {
    const fixture = render([
      game('qf-1', 'Quarterfinal', 0, { blackPlayerName: 'BYE', blackParticipantId: 'p-bye' }),
      game('qf-2', 'Quarterfinal', 1, {
        blackPlayerName: 'BYE',
        blackParticipantId: 'p-bye-2',
        status: 'COMPLETED',
        result: 'BLACK_WINS',
        advancedPlayerName: 'BYE',
        advancedParticipantId: 'p-bye-2',
      }),
    ]);

    expect(button(fixture, 'qf-1', 'Battle')).toBeTruthy();
    expect(scores(fixture, 'qf-2')).toEqual(['0', '1']);
    expect(button(fixture, 'qf-2', 'Review')).toBeTruthy();
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
      game('final-1', 'Final', 0, {
        whitePlayerName: null,
        blackPlayerName: null,
        whiteParticipantId: null,
        blackParticipantId: null,
      }),
    ]);

    expect(scores(fixture, 'final-1')).toEqual([]);
  });
});

/** Colour assignment of a later series game, where "White" and "Black" swap sides. */
function swapped(): Partial<Game> {
  return {
    whitePlayerName: 'Black',
    whiteModelId: 'model-b',
    whiteParticipantId: 'p-black',
    blackPlayerName: 'White',
    blackModelId: 'model-a',
    blackParticipantId: 'p-white',
  };
}

/** Records the backend's advancement of the participant "White" or "Black". */
function advancedTo(name: 'White' | 'Black'): Partial<Game> {
  return {
    advancedPlayerName: name,
    advancedParticipantId: name === 'White' ? 'p-white' : 'p-black',
  };
}

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
    whiteParticipantId: 'p-white',
    blackPlayerName: 'Black',
    blackControlType: 'AI',
    blackModelId: 'model-b',
    blackParticipantId: 'p-black',
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
