import { TestBed } from '@angular/core/testing';
import { Game } from '../../models/tournament.model';
import { GameInfoBar, RaiseLimitRequest, TruncationEffortChoice } from './game-info-bar';

function pausedGame(statusDetail: string | null = 'Cut off'): Game {
  return {
    id: 'g-1',
    tournamentId: 't-1',
    whitePlayerName: 'Alpha',
    whiteControlType: 'AI',
    whiteModelId: 'model-a',
    blackPlayerName: 'Beta',
    blackControlType: 'AI',
    blackModelId: 'model-b',
    status: 'PAUSED',
    statusDetail,
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
  };
}

describe('GameInfoBar raise-limit control', () => {
  it('is shown only for a pause caused by a cut-off answer', () => {
    const bar = new GameInfoBar();
    bar.game = pausedGame();

    expect(bar.showRaiseLimit()).toBe(false);

    bar.truncationLimit = 4096;
    expect(bar.showRaiseLimit()).toBe(true);

    bar.game = { ...pausedGame(), status: 'IN_PROGRESS' };
    expect(bar.showRaiseLimit()).toBe(false);
  });

  it('prefills double the current limit, capped at 262144', () => {
    const bar = new GameInfoBar();

    bar.truncationLimit = 4096;
    expect(bar.newMaxOutputTokens).toBe(8192);

    bar.truncationLimit = 16384;
    expect(bar.newMaxOutputTokens).toBe(32768);

    bar.truncationLimit = 200000;
    expect(bar.newMaxOutputTokens).toBe(262144);

    bar.truncationLimit = null;
    expect(bar.newMaxOutputTokens).toBeNull();
  });

  it('bounds suggestions by the model maximum without lowering the current tournament limit', () => {
    const bar = new GameInfoBar();
    bar.game = pausedGame();
    bar.truncationLimit = 32768;
    bar.truncationModelMaximum = 8192;
    expect(bar.newMaxOutputTokens).toBe(32768);
    expect(bar.allowedOutputMaximum()).toBe(32768);

    const emitted: RaiseLimitRequest[] = [];
    bar.raiseLimitAndResume.subscribe((value) => emitted.push(value));
    bar.newMaxOutputTokens = 32769;
    expect(bar.canSaveLimit()).toBe(false);
    bar.saveAndResume();
    bar.newMaxOutputTokens = 8192;
    bar.saveAndResume();
    expect(emitted).toEqual([{ maxOutputTokens: 8192 }]);

    bar.truncationLimit = 4096;
    expect(bar.newMaxOutputTokens).toBe(8192);
    bar.truncationModelMaximum = null;
    expect(bar.allowedOutputMaximum()).toBe(262144);
    bar.truncationModelMaximum = 6000;
    expect(bar.newMaxOutputTokens).toBe(6000);
    bar.truncationLimit = 8192;
    expect(bar.newMaxOutputTokens).toBe(8192);
  });

  it('keeps 32768 visible and explains the 8192 model cap during effort-only recovery', async () => {
    const detail =
      "Alpha's answer was cut off at the model's maximum of 8,192 output tokens. " +
      'Raising the tournament limit cannot help. Lower the reasoning effort and resume.';
    const fixture = TestBed.createComponent(GameInfoBar);
    fixture.componentInstance.game = pausedGame(detail);
    fixture.componentInstance.truncationModelMaximum = 8192;
    fixture.componentInstance.truncationLimit = 32768;
    fixture.componentInstance.truncationEffort = {
      participantId: 'p-1',
      playerName: 'Alpha',
      current: 'high',
      tournamentLevel: null,
      options: ['low', 'high'],
      fromCatalog: true,
    };
    const emitted: RaiseLimitRequest[] = [];
    fixture.componentInstance.raiseLimitAndResume.subscribe((value) => emitted.push(value));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const element: HTMLElement = fixture.nativeElement;
    const input = element.querySelector<HTMLInputElement>('input[type="number"]');
    expect(input?.max).toBe('32768');
    expect(input?.value).toBe('32768');
    expect(element.querySelector('.pause-detail')?.textContent).toBe(detail);
    expect(element.querySelector('.limit-field mat-hint')?.textContent).toContain(
      'This model is capped at 8192',
    );
    expect(element.querySelector<HTMLButtonElement>('.save-resume')?.disabled).toBe(false);

    element.querySelector<HTMLButtonElement>('.save-resume')!.click();
    expect(emitted).toEqual([{ reasoningEffort: 'low' }]);

    input!.value = '8192';
    input!.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    await fixture.whenStable();
    element.querySelector<HTMLButtonElement>('.save-resume')!.click();
    expect(emitted).toEqual([
      { reasoningEffort: 'low' },
      { maxOutputTokens: 8192, reasoningEffort: 'low' },
    ]);
  });

  it('emits a valid limit and refuses out-of-range values or a save in progress', () => {
    const bar = new GameInfoBar();
    bar.game = pausedGame();
    bar.truncationLimit = 4096;
    const emitted: RaiseLimitRequest[] = [];
    bar.raiseLimitAndResume.subscribe((value) => emitted.push(value));

    bar.saveAndResume();
    bar.newMaxOutputTokens = 262145;
    bar.saveAndResume();
    bar.newMaxOutputTokens = 100;
    bar.saveAndResume();
    bar.newMaxOutputTokens = 16384;
    bar.saving = true;
    bar.saveAndResume();

    bar.saving = false;
    bar.newMaxOutputTokens = 128;
    bar.saveAndResume();
    bar.newMaxOutputTokens = 262144;
    bar.saveAndResume();
    expect(emitted).toEqual([
      { maxOutputTokens: 8192 },
      { maxOutputTokens: 128 },
      { maxOutputTokens: 262144 },
    ]);
  });
});

describe('GameInfoBar reasoning effort for a cut-off answer', () => {
  const choice = (overrides: Partial<TruncationEffortChoice> = {}): TruncationEffortChoice => ({
    participantId: 'p-2',
    playerName: 'Ember',
    current: null,
    tournamentLevel: null,
    options: ['minimal', 'low', 'medium', 'high', 'xhigh', 'max'],
    fromCatalog: true,
    ...overrides,
  });

  it('pre-selects low and sends it with the limit', () => {
    const bar = new GameInfoBar();
    bar.game = pausedGame();
    bar.truncationLimit = 30000;
    bar.truncationEffort = choice();
    const emitted: RaiseLimitRequest[] = [];
    bar.raiseLimitAndResume.subscribe((value) => emitted.push(value));

    expect(bar.showEffortSelect()).toBe(true);
    expect(bar.newReasoningEffort).toBe('low');
    expect(bar.tournamentEffortOptionLabel()).toBe('Tournament setting (model default)');
    expect(bar.effortHint()).toBe('Currently model default; saved for Ember from the next turn');

    bar.saveAndResume();
    bar.newReasoningEffort = null;
    bar.saveAndResume();

    expect(emitted).toEqual([
      { maxOutputTokens: 60000, reasoningEffort: 'low' },
      { maxOutputTokens: 60000, reasoningEffort: null },
    ]);
  });

  it('pre-selects the nearest supported level and keeps a level that is already low', () => {
    const bar = new GameInfoBar();
    bar.truncationEffort = choice({ options: ['none', 'high', 'max'] });
    expect(bar.newReasoningEffort).toBe('high');

    bar.truncationEffort = choice({ current: 'minimal', tournamentLevel: 'medium' });
    expect(bar.newReasoningEffort).toBe('minimal');
    expect(bar.tournamentEffortOptionLabel()).toBe('Tournament setting (Medium)');

    // The same choice again (a re-render) keeps what the user picked.
    bar.newReasoningEffort = 'max';
    bar.truncationEffort = choice({ current: 'minimal', tournamentLevel: 'medium' });
    expect(bar.newReasoningEffort).toBe('max');
  });

  it('shows and saves a supported suggestion when the tournament level is not an option', async () => {
    const fixture = TestBed.createComponent(GameInfoBar);
    const bar = fixture.componentInstance;
    bar.game = pausedGame();
    bar.truncationLimit = 4096;
    bar.truncationEffort = choice({
      current: 'minimal',
      tournamentLevel: 'minimal',
      options: ['none', 'low', 'high', 'max'],
    });
    const emitted: RaiseLimitRequest[] = [];
    bar.raiseLimitAndResume.subscribe((value) => emitted.push(value));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(
      (fixture.nativeElement as HTMLElement)
        .querySelector('.effort-field .mat-mdc-select-value')
        ?.textContent?.trim(),
    ).toBe('Low');
    bar.saveAndResume();
    expect(emitted).toEqual([{ maxOutputTokens: 8192, reasoningEffort: 'low' }]);
  });

  it('offers no select for a model without effort levels and sends no effort then', () => {
    const bar = new GameInfoBar();
    bar.game = pausedGame();
    bar.truncationLimit = 4096;
    bar.truncationEffort = choice({ options: [] });
    const emitted: RaiseLimitRequest[] = [];
    bar.raiseLimitAndResume.subscribe((value) => emitted.push(value));

    expect(bar.showEffortSelect()).toBe(false);
    bar.saveAndResume();
    expect(emitted).toEqual([{ maxOutputTokens: 8192 }]);
  });

  it('explains that every level is offered when the catalog could not tell', () => {
    const bar = new GameInfoBar();
    bar.truncationEffort = choice({ current: 'max', fromCatalog: false });
    expect(bar.effortHint()).toBe('Currently Max; sent as the nearest level the model supports');
  });

  it('renders the select in the pause banner and the level each side asks for', async () => {
    const fixture = TestBed.createComponent(GameInfoBar);
    fixture.componentInstance.game = {
      ...pausedGame('Ember was cut off'),
      blackPlayerName: 'Ember',
      whiteReasoningEffort: 'high',
      blackReasoningEffort: null,
    };
    fixture.componentInstance.truncationLimit = 30000;
    fixture.componentInstance.truncationEffort = choice();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const element: HTMLElement = fixture.nativeElement;

    const tokenInput = element.querySelector<HTMLInputElement>('input[max="262144"]');
    expect(tokenInput?.min).toBe('128');
    expect(tokenInput?.value).toBe('60000');

    expect(element.querySelector('.effort-field mat-label')?.textContent?.trim()).toBe(
      'Reasoning effort for Ember',
    );
    expect(element.querySelector('.effort-field .mat-mdc-select-value')?.textContent?.trim()).toBe(
      'Low',
    );
    const tags = Array.from(element.querySelectorAll('.effort-tag')).map((tag) =>
      tag.textContent?.trim(),
    );
    expect(tags).toEqual(['· effort high']);
  });
});

describe('GameInfoBar result text', () => {
  it('names the winner with a readable result reason', () => {
    const bar = new GameInfoBar();
    bar.game = {
      ...pausedGame(),
      status: 'COMPLETED',
      result: 'BLACK_WINS',
      resultReason: 'RESIGNATION',
    };

    expect(bar.getResultText()).toBe('Alpha resigned; Beta wins');
    expect(bar.statusLabel(bar.game.status)).toBe('Completed');

    bar.game = { ...bar.game, result: 'WHITE_WINS' };
    expect(bar.getResultText()).toBe('Beta resigned; Alpha wins');
    bar.game = { ...bar.game, resultReason: 'CHECKMATE' };
    expect(bar.getResultText()).toBe('Alpha wins by checkmate');
  });
});

describe('GameInfoBar resignation', () => {
  function resignedGame(resignation: Game['resignation']): Game {
    return {
      ...pausedGame(null),
      status: 'COMPLETED',
      result: 'WHITE_WINS',
      resultReason: 'RESIGNATION',
      resignation,
    };
  }

  it('shows the winning chance at resignation, last words and the last opponent message', () => {
    const fixture = TestBed.createComponent(GameInfoBar);
    fixture.componentRef.setInput(
      'game',
      resignedGame({
        color: 'BLACK',
        moveNumber: 23,
        winChance: 0.4612,
        notLosing: true,
        lastWords: 'Well played.',
        opponentMessage: 'Your position is hopeless.',
        opponentMessageMoveNumber: 23,
      }),
    );
    fixture.detectChanges();
    const panel = (fixture.nativeElement as HTMLElement).querySelector('.resignation-panel');

    expect(panel?.querySelector('.resignation-title')?.textContent?.trim()).toBe(
      'Beta resigned instead of playing move 23, at 46% winning chances (not losing)',
    );
    const lines = Array.from(panel?.querySelectorAll('.resignation-line') ?? []).map((line) =>
      line.textContent?.replace(/\s+/g, ' ').trim(),
    );
    expect(lines).toEqual([
      'Last words: “Well played.”',
      'Last message from Alpha (move 23): “Your position is hopeless.”',
    ]);
  });

  it('leaves out what is unknown or missing', () => {
    const fixture = TestBed.createComponent(GameInfoBar);
    fixture.componentRef.setInput(
      'game',
      resignedGame({
        color: 'BLACK',
        moveNumber: 12,
        winChance: null,
        notLosing: false,
        lastWords: null,
        opponentMessage: null,
        opponentMessageMoveNumber: null,
      }),
    );
    fixture.detectChanges();
    const panel = (fixture.nativeElement as HTMLElement).querySelector('.resignation-panel');

    expect(panel?.querySelector('.resignation-title')?.textContent?.trim()).toBe(
      'Beta resigned instead of playing move 12',
    );
    expect(panel?.querySelectorAll('.resignation-line').length).toBe(0);
  });

  it('says nothing about winning chances below 35% beyond the number, and nothing for other results', () => {
    const bar = new GameInfoBar();
    bar.game = resignedGame({
      color: 'WHITE',
      moveNumber: 30,
      winChance: 0.08,
      notLosing: false,
      lastWords: null,
      opponentMessage: null,
      opponentMessageMoveNumber: null,
    });
    expect(bar.resignationText(bar.resignation()!)).toBe(
      'Alpha resigned instead of playing move 30, at 8% winning chances',
    );

    bar.game = { ...bar.game, resultReason: 'CHECKMATE' };
    expect(bar.resignation()).toBeNull();
  });
});

describe('GameInfoBar move-quality summary', () => {
  const summary = {
    white: {
      analyzedMoves: 35,
      averageCentipawnLoss: 41,
      inaccuracies: 3,
      mistakes: 1,
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

  it('shows a side only once one of its moves has been judged', () => {
    const bar = new GameInfoBar();
    bar.game = { ...pausedGame(), status: 'IN_PROGRESS' };

    expect(bar.sideQuality('WHITE')).toBeNull();

    bar.game = { ...bar.game, qualitySummary: summary };

    expect(bar.sideQuality('WHITE')?.averageCentipawnLoss).toBe(41);
    expect(bar.sideQuality('BLACK')).toBeNull();
  });

  it('renders ACPL and the ?? / ? / ?! counts near the player', () => {
    const fixture = TestBed.createComponent(GameInfoBar);
    fixture.componentRef.setInput('game', {
      ...pausedGame(),
      status: 'IN_PROGRESS',
      qualitySummary: summary,
    });
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;

    const lines = element.querySelectorAll<HTMLElement>('.quality-summary');
    expect(lines.length).toBe(1);
    expect(element.querySelector('.white-player .quality-summary')).not.toBeNull();
    const line = lines[0];
    expect(line.querySelector('.acpl')?.textContent?.trim()).toBe('ACPL 41');
    const counts = Array.from(line.querySelectorAll<HTMLElement>('.quality-count'));
    expect(counts.map((count) => count.textContent?.trim())).toEqual(['?? 0', '? 1', '?! 3']);
    expect(counts[0].classList).toContain('none');
    expect(counts[1].classList).toContain('quality-mistake');
    expect(counts[2].classList).toContain('quality-inaccuracy');
    expect(line.getAttribute('title')).toBe(
      'Average centipawn loss 41 over 35 judged moves; 0 blunders, 1 mistake, 3 inaccuracies',
    );
  });
});
