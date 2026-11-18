import { OpenRouterAccess } from '../../services/openrouter-access';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TestbedHarnessEnvironment } from '@angular/cdk/testing/testbed';
import { MatMenuHarness } from '@angular/material/menu/testing';
import { MatSelectHarness } from '@angular/material/select/testing';
import { ActivatedRoute, convertToParamMap, provideRouter, Router } from '@angular/router';
import { EMPTY, of, Subject, throwError } from 'rxjs';
import { TournamentBracket } from './tournament-bracket';
import { ApiService } from '../../services/api.service';
import { BracketDrawMode, Game, Tournament } from '../../models/tournament.model';
import { MatSnackBar } from '@angular/material/snack-bar';

describe('TournamentBracket', () => {
  beforeEach(() => {
    // jsdom has no ResizeObserver; used by bracket connectors and Material form labels.
    vi.stubGlobal(
      'ResizeObserver',
      class {
        observe(): void {}
        unobserve(): void {}
        disconnect(): void {}
      },
    );
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  function render(t: Tournament): HTMLElement {
    TestBed.configureTestingModule({
      providers: [
        { provide: OpenRouterAccess, useValue: { withKey: (action: () => void) => action() } },
        provideRouter([]),
        {
          provide: ApiService,
          useValue: { getTournament: () => of(t), getTournamentCostSummary: () => EMPTY },
        },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ id: t.id }) } },
        },
      ],
    });
    const fixture = TestBed.createComponent(TournamentBracket);
    fixture.detectChanges();
    return fixture.nativeElement;
  }

  it('opens a newly created ready bracket with play controls and no generation step', () => {
    const ready = tournament('IN_PROGRESS', null, finalGame({ status: 'WAITING' }));
    const element = render(ready);
    expect(element.querySelector('.generate-section')).toBeNull();
    expect(element.querySelector('.bracket-container')).not.toBeNull();
    expect(element.querySelector('.start-run')?.textContent).toContain('Run tournament');
  });

  it('opens rematch setup with only the tournament ID in the URL', () => {
    const original = {
      ...tournament('COMPLETED', 'Winner', finalGame({ status: 'COMPLETED' })),
      sharedCustomInstructions: 'Private instructions',
    };
    const element = render(original);
    const action = Array.from(element.querySelectorAll('a')).find(
      (link) => link.textContent.trim() === 'Run this tournament again',
    );
    expect(action?.getAttribute('href')).toBe('/tournaments/' + original.id + '/rematch');
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
    action?.click();
    expect(navigate.mock.calls[0][0].toString()).toBe('/tournaments/t-1/rematch');
  });

  function runPage(
    status: Tournament['status'] = 'IN_PROGRESS',
    runStatus: Tournament['autoRunStatus'] = 'OFF',
  ) {
    const current: Tournament = {
      ...tournament(status, null, finalGame({ status: 'WAITING' })),
      autoRunStatus: runStatus,
      maxConcurrentGames: 2,
      budgetUsd: 5,
    };
    const api = {
      getTournament: vi.fn().mockReturnValue(of(current)),
      getTournamentCostSummary: () => EMPTY,
      startGame: vi.fn(),
      cancelResumeRequest: vi.fn(),
      getTournamentExport: vi.fn().mockReturnValue(of(new Blob(['export']))),
      runTournament: vi.fn().mockReturnValue(of({ ...current, autoRunStatus: 'RUNNING' })),
      updateTournamentRun: vi.fn().mockReturnValue(of(current)),
      pauseTournamentRun: vi
        .fn()
        .mockReturnValue(
          of({ ...current, autoRunStatus: 'PAUSED', autoRunPauseReason: 'Paused by user' }),
        ),
    };
    TestBed.configureTestingModule({
      providers: [
        { provide: OpenRouterAccess, useValue: { withKey: (action: () => void) => action() } },
        provideRouter([]),
        { provide: ApiService, useValue: api },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ id: current.id }) } },
        },
      ],
    });
    const fixture = TestBed.createComponent(TournamentBracket);
    fixture.detectChanges();
    return { fixture, api, component: fixture.componentInstance, current };
  }

  const liveGames = [0, 1].map((bracketPosition) =>
    finalGame({
      id: `live-${bracketPosition + 1}`,
      seriesId: `live-${bracketPosition + 1}`,
      bracketRound: 'Quarterfinal',
      bracketPosition,
      status: 'IN_PROGRESS',
      whitePlayerName: 'Aster',
      blackPlayerName: 'Birch',
    }),
  );

  function keydown(element: Element, key: string, keyCode: number): void {
    element.dispatchEvent(
      new KeyboardEvent('keydown', { key, keyCode, bubbles: true, cancelable: true }),
    );
  }

  function activateButtonWithKey(button: HTMLButtonElement, key: 'Enter' | ' '): void {
    keydown(button, key, key === 'Enter' ? 13 : 32);
    // jsdom needs the click that the browser synthesizes for a native button.
    button.click();
  }

  it.each(['RUNNING', 'OFF'] as const)(
    'links directly to the only live game with auto-run %s',
    (runStatus) => {
      const { fixture, component, current } = runPage('IN_PROGRESS', runStatus);
      const game = finalGame({ status: 'IN_PROGRESS' });
      component.tournament.set({ ...current, games: [game] });
      fixture.detectChanges();

      const action = (fixture.nativeElement as HTMLElement).querySelector<HTMLAnchorElement>(
        '.run-summary a',
      )!;
      expect(action.textContent?.trim()).toBe('Watch live');
      expect(runBanner(fixture)).toContain('1 game running');
      const router = TestBed.inject(Router);
      const navigate = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
      action.click();
      expect(navigate.mock.calls[0][0].toString()).toBe('/games/' + game.id);
    },
  );

  it('lists each live matchup in Watch live and opens the selected game', async () => {
    const { fixture, component, current } = runPage('IN_PROGRESS', 'RUNNING');
    const games = [
      ...liveGames,
      finalGame({
        id: 'queued-1',
        seriesId: 'queued-1',
        bracketRound: 'Semifinal',
        bracketPosition: 1,
        status: 'PAUSED',
        resumeRequestedAt: '2026-11-17T20:00:00Z',
      }),
    ];
    component.tournament.set({ ...current, games });
    fixture.detectChanges();

    const summary = (fixture.nativeElement as HTMLElement).querySelector('.run-summary')!;
    const statuses = summary.querySelectorAll('[role="status"]');
    expect(statuses).toHaveLength(1);
    expect(statuses[0].nextElementSibling?.textContent?.trim()).toMatch(/^Watch live/);
    expect(runBanner(fixture)).toMatch(/2 games running .* spent .* need attention$/);
    const menu = await TestbedHarnessEnvironment.loader(fixture).getHarness(
      MatMenuHarness.with({ triggerText: /^Watch live/ }),
    );
    await menu.open();
    const items = await menu.getItems();
    expect(await Promise.all(items.map((item) => item.getText()))).toEqual([
      'Quarterfinal · Match 1: Aster vs Birch',
      'Quarterfinal · Match 2: Aster vs Birch',
    ]);
    expect(
      await Promise.all(items.map(async (item) => (await item.host()).getAttribute('href'))),
    ).toEqual(['/games/live-1', '/games/live-2']);
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
    await menu.clickItem({ text: 'Quarterfinal · Match 2: Aster vs Birch' });
    expect(navigate.mock.calls[0][0].toString()).toBe('/games/live-2');
  });

  it.each(['trigger', 'menu item', 'elsewhere'] as const)(
    'updates Watch live as polling goes from multiple games to one to zero with focus on %s',
    async (focus) => {
      const { fixture, api, component, current } = runPage('IN_PROGRESS', 'RUNNING');
      const element = fixture.nativeElement as HTMLElement;
      api.getTournament.mockReturnValue(of({ ...current, games: liveGames }));
      component.loadTournament();
      fixture.detectChanges();

      const watch = element.querySelector<HTMLButtonElement>('.run-summary button')!;
      const exportButton = element.querySelector<HTMLButtonElement>('.export-trigger')!;
      const status = element.querySelector<HTMLElement>('.run-summary [role="status"]')!;
      let menuId: string | null = null;
      if (focus === 'menu item') {
        watch.click();
        await fixture.whenStable();
        menuId = watch.getAttribute('aria-controls');
        expect(menuId).toBeTruthy();
        const menu = document.getElementById(menuId!)!;
        expect(document.querySelectorAll(`#${menuId}`)).toHaveLength(1);
        expect(document.activeElement).toBe(menu.querySelector('[role="menuitem"]'));
      } else {
        (focus === 'trigger' ? watch : exportButton).focus();
      }

      const finished = { ...liveGames[0], status: 'COMPLETED' as const };
      api.getTournament.mockReturnValue(of({ ...current, games: [finished, liveGames[1]] }));
      component.loadTournament();
      fixture.detectChanges();

      const link = element.querySelector<HTMLAnchorElement>('.run-summary a')!;
      expect(watch.isConnected).toBe(false);
      if (menuId) expect(document.getElementById(menuId)).toBeNull();
      expect(link.getAttribute('href')).toBe('/games/live-2');
      expect(runBanner(fixture)).toContain('1 game running');
      expect(document.activeElement).toBe(focus === 'elsewhere' ? exportButton : link);

      api.getTournament.mockReturnValue(
        of({ ...current, games: [finished, { ...liveGames[1], status: 'COMPLETED' }] }),
      );
      component.loadTournament();
      fixture.detectChanges();

      expect(element.querySelector('.run-summary a, .run-summary button')).toBeNull();
      expect(runBanner(fixture)).toContain('0 games running');
      expect(status.tabIndex).toBe(-1);
      expect(document.activeElement).toBe(focus === 'elsewhere' ? exportButton : status);
    },
  );

  it('focuses the run status when polling removes the open Watch live menu', async () => {
    const { fixture, api, component, current } = runPage('IN_PROGRESS', 'RUNNING');
    const element = fixture.nativeElement as HTMLElement;
    component.tournament.set({ ...current, games: liveGames });
    fixture.detectChanges();
    const watch = element.querySelector<HTMLButtonElement>('.run-summary button')!;
    watch.click();
    await fixture.whenStable();
    const menu = document.getElementById(watch.getAttribute('aria-controls')!)!;
    expect(document.activeElement).toBe(menu.querySelector('[role="menuitem"]'));

    api.getTournament.mockReturnValue(of(current));
    component.loadTournament();
    fixture.detectChanges();

    expect(menu.isConnected).toBe(false);
    expect(document.activeElement).toBe(element.querySelector('.run-summary [role="status"]'));
  });

  it.each(['.export-trigger', '.run-summary button'])(
    'opens %s with Enter and Space and restores focus on Escape',
    async (selector) => {
      const { fixture, component, current } = runPage('IN_PROGRESS', 'RUNNING');
      component.tournament.set({ ...current, games: liveGames });
      fixture.detectChanges();
      const trigger = (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>(
        selector,
      )!;
      trigger.focus();
      for (const key of ['Enter', ' '] as const) {
        activateButtonWithKey(trigger, key);
        await fixture.whenStable();
        const menu = document.getElementById(trigger.getAttribute('aria-controls')!)!;
        const firstItem = menu.querySelector('[role="menuitem"]')!;
        expect(document.activeElement).toBe(firstItem);

        keydown(firstItem, 'Escape', 27);
        await fixture.whenStable();
        expect(trigger.getAttribute('aria-expanded')).toBe('false');
        expect(document.activeElement).toBe(trigger);
      }
    },
  );

  it('hides Watch live when the tournament has no running games', () => {
    const element = render(tournament('COMPLETED', 'White', finalGame()));
    expect(element.querySelector('.run-summary')?.textContent).not.toContain('Watch live');
  });

  it('keeps Review focusable and opens the completed game', () => {
    const game = finalGame();
    const element = render(tournament('COMPLETED', 'White', game));
    const review = [...element.querySelectorAll<HTMLButtonElement>('.card-footer button')].find(
      (action) => action.textContent?.includes('Review'),
    )!;
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    review.focus();
    expect(document.activeElement).toBe(review);
    review.click();
    expect(navigate).toHaveBeenCalledExactlyOnceWith(['/games', game.id]);
  });

  it('starts with an unchanged large cap and never rounds an edited amount into a patch', () => {
    const { api, component, current } = runPage();
    const exact = '99999999999999999.99';
    api.getTournament.mockReturnValue(of({ ...current, budgetUsd: 1e17, budgetUsdExact: exact }));
    component.loadTournament();
    expect(component.runBudget).toBe(exact);
    component.runTournament();
    expect(api.runTournament).toHaveBeenCalledWith(current.id, {});
    api.getTournament.mockReturnValue(of({ ...current, budgetUsd: 1e17, budgetUsdExact: exact }));
    component.loadTournament();
    component.runBudget = '99999999999999999.98';
    expect(component.runSettingsChanged).toBe(true);
    expect(component.validRunBudget).toBe(false);
  });

  it('keeps a local concurrency edit while picking up a new remote budget', () => {
    const { api, component, current } = runPage('IN_PROGRESS', 'RUNNING');
    component.runConcurrency = 4;
    api.getTournament.mockReturnValue(of({ ...current, budgetUsd: 2 }));
    component.loadTournament();
    expect(component.runConcurrency).toBe(4);
    expect(component.runBudget).toBe('2');
    component.saveRunSettings();
    expect(api.updateTournamentRun).toHaveBeenCalledWith(current.id, { maxConcurrentGames: 4 });
  });

  it('keeps a local budget edit while picking up a new remote concurrency limit', () => {
    const { api, component, current } = runPage('IN_PROGRESS', 'PAUSED');
    component.runBudget = '2';
    api.getTournament.mockReturnValue(of({ ...current, maxConcurrentGames: 3 }));
    component.loadTournament();
    expect(component.runConcurrency).toBe(3);
    expect(component.runBudget).toBe('2');
    component.runTournament();
    expect(api.runTournament).toHaveBeenCalledWith(current.id, { budgetUsd: 2 });
  });

  function runBanner(fixture: ComponentFixture<TournamentBracket>): string {
    const status = (fixture.nativeElement as HTMLElement).querySelector('.run-status')!;
    return status.textContent.replace(/\s+/g, ' ').trim();
  }

  it('queues Resume with a full run, keeps the pause reason, and cancels from the card', () => {
    const { fixture, component, current, api } = runPage('IN_PROGRESS', 'RUNNING');
    const paused = finalGame({
      status: 'PAUSED',
      statusDetail: 'Answer cut off',
      statusRevision: 4,
    });
    const queued = {
      ...paused,
      statusRevision: 5,
      resumeRequestedAt: '2026-11-17T20:00:00Z',
    };
    component.tournament.set({
      ...current,
      maxConcurrentGames: 3,
      games: [
        paused,
        ...[0, 1, 2].map((bracketPosition) =>
          finalGame({
            id: `live-${bracketPosition}`,
            seriesId: `live-${bracketPosition}`,
            bracketRound: 'Semifinal',
            bracketPosition,
            status: 'IN_PROGRESS',
          }),
        ),
      ],
    });
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate');
    const snack = vi.spyOn(TestBed.inject(MatSnackBar), 'open');
    api.getTournament.mockReturnValue(EMPTY);
    api.startGame.mockReturnValue(of(queued));
    api.cancelResumeRequest.mockReturnValue(of({ ...paused, statusRevision: 6 }));
    fixture.detectChanges();
    const card = (fixture.nativeElement as HTMLElement).querySelector<HTMLElement>(
      '[data-game-id="final-1"]',
    )!;
    const resume = () =>
      [...card.querySelectorAll<HTMLButtonElement>('button')].find((button) =>
        button.textContent?.includes('Resume'),
      );
    expect(resume()?.disabled).toBe(false);
    resume()!.click();
    fixture.detectChanges();

    expect(api.startGame).toHaveBeenCalledExactlyOnceWith(paused.id);
    expect(card.querySelector('[role="status"]')?.textContent).toMatch(
      /Queued: resumes when a slot frees\s*\(3\/3 running\)/,
    );
    expect(card.querySelector('.pause-reason')?.textContent).toContain('Answer cut off');
    expect(runBanner(fixture)).toContain('0 need attention');
    expect(navigate).not.toHaveBeenCalled();
    expect(snack).not.toHaveBeenCalled();

    card.querySelector<HTMLButtonElement>('[aria-label="Cancel queued resume"]')!.click();
    fixture.detectChanges();
    expect(api.cancelResumeRequest).toHaveBeenCalledExactlyOnceWith(paused.id, 5);
    expect(card.querySelector('app-queued-resume')).toBeNull();
    expect(runBanner(fixture)).toContain('1 need attention');
    expect(resume()?.disabled).toBe(false);
  });

  it('keeps a cleared queue badge when an older Save limits response arrives', () => {
    const { fixture, component, current, api } = runPage('IN_PROGRESS', 'RUNNING');
    const queued = finalGame({
      status: 'PAUSED',
      statusRevision: 5,
      resumeRequestedAt: '2026-11-17T20:00:00Z',
    });
    const cleared = { ...queued, statusRevision: 6, resumeRequestedAt: null };
    const queuedTournament = { ...current, games: [queued] };
    component.tournament.set(queuedTournament);
    component.runConcurrency = 3;
    const pendingSave = new Subject<Tournament>();
    api.updateTournamentRun.mockReturnValue(pendingSave);
    api.cancelResumeRequest.mockReturnValue(of(cleared));
    api.getTournament.mockReturnValue(EMPTY);
    fixture.detectChanges();

    component.saveRunSettings();
    expect(component.runBusy()).toBe(true);
    const element: HTMLElement = fixture.nativeElement;
    element.querySelector<HTMLButtonElement>('[aria-label="Cancel queued resume"]')!.click();
    fixture.detectChanges();
    expect(element.querySelector('app-queued-resume')).toBeNull();
    expect(api.getTournament).toHaveBeenCalledTimes(1);

    pendingSave.next({ ...queuedTournament, maxConcurrentGames: 3 });
    pendingSave.complete();
    fixture.detectChanges();
    expect(element.querySelector('app-queued-resume')).toBeNull();
    expect(component.tournament()?.games[0].statusRevision).toBe(6);
    expect(component.tournament()?.maxConcurrentGames).toBe(3);
    expect(api.getTournament).toHaveBeenCalledTimes(2);
  });

  it('refreshes the queue if cancelling conflicts while saving run limits', () => {
    const { component, current, api } = runPage('IN_PROGRESS', 'RUNNING');
    const queued = finalGame({
      status: 'PAUSED',
      statusRevision: 5,
      resumeRequestedAt: '2026-11-17T20:00:00Z',
    });
    const replacement = { ...queued, statusRevision: 7, resumeRequestedAt: '2026-11-17T20:01:00Z' };
    const queuedTournament = { ...current, games: [queued] };
    component.tournament.set(queuedTournament);
    component.runConcurrency = 3;
    const pendingSave = new Subject<Tournament>();
    const message = 'The queued resume has changed; refresh and try again';
    const snack = vi.spyOn(TestBed.inject(MatSnackBar), 'open');
    api.updateTournamentRun.mockReturnValue(pendingSave);
    api.cancelResumeRequest.mockReturnValue(
      throwError(() => ({ status: 409, error: { error: message } })),
    );
    api.getTournament.mockReturnValue(
      of({ ...queuedTournament, maxConcurrentGames: 3, games: [replacement] }),
    );

    component.saveRunSettings();
    component.cancelResumeRequest(queued);
    expect(api.cancelResumeRequest).toHaveBeenCalledExactlyOnceWith(queued.id, 5);
    expect(snack).toHaveBeenCalledWith(message, expect.any(String), expect.any(Object));
    expect(api.getTournament).toHaveBeenCalledTimes(1);
    pendingSave.next({ ...queuedTournament, maxConcurrentGames: 3 });
    pendingSave.complete();

    expect(api.getTournament).toHaveBeenCalledTimes(2);
    expect(component.tournament()?.games[0]).toEqual(replacement);
  });

  it.each(['pgn', 'csv', 'json'] as const)('downloads %s from the Export menu', async (format) => {
    const { fixture, component, api } = runPage();
    const createObjectURL = vi.fn().mockReturnValue('blob:export');
    vi.stubGlobal(
      'URL',
      class extends URL {
        static override createObjectURL = createObjectURL;
        static override revokeObjectURL = vi.fn();
      },
    );
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    const exportTournament = vi.spyOn(component, 'exportTournament');
    const menu = await TestbedHarnessEnvironment.loader(fixture).getHarness(
      MatMenuHarness.with({ triggerText: /^Export/ }),
    );
    await menu.open();
    const [item] = await menu.getItems({ text: format.toUpperCase() });
    expect(await (await item.host()).getAttribute('aria-label')).toBe(
      'Download tournament ' + format.toUpperCase(),
    );
    await item.click();
    expect(exportTournament).toHaveBeenCalledExactlyOnceWith(format);
    expect(api.getTournamentExport).toHaveBeenCalledWith('t-1', format);
    expect(createObjectURL).toHaveBeenCalledWith(expect.any(Blob));
    expect((click.mock.instances[0] as HTMLAnchorElement).download).toBe(
      `tournament-t-1.${format}`,
    );
    expect(component.exporting()).toBe(false);
  });

  it('keeps Export focused and blocks reactivation during a pending download', async () => {
    const { fixture, component, api } = runPage();
    const pending = new Subject<Blob>();
    api.getTournamentExport.mockReturnValue(pending);
    const snackbar = vi.spyOn(TestBed.inject(MatSnackBar), 'open');
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click');
    const trigger = (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>(
      '.export-trigger',
    )!;
    trigger.focus();
    activateButtonWithKey(trigger, 'Enter');
    await fixture.whenStable();
    const firstItem = document.activeElement as HTMLButtonElement;
    expect(firstItem.getAttribute('aria-label')).toBe('Download tournament PGN');
    activateButtonWithKey(firstItem, 'Enter');
    await fixture.whenStable();

    expect(trigger.getAttribute('aria-disabled')).toBe('true');
    expect(trigger.disabled).toBe(false);
    expect(document.activeElement).toBe(trigger);
    activateButtonWithKey(trigger, ' ');
    await fixture.whenStable();
    expect(trigger.getAttribute('aria-expanded')).toBe('false');
    expect(document.activeElement).toBe(trigger);
    component.exportTournament('csv');
    expect(api.getTournamentExport).toHaveBeenCalledOnce();
    pending.error(new Error('offline'));
    await fixture.whenStable();
    expect(trigger.getAttribute('aria-disabled')).toBeNull();
    expect(document.activeElement).toBe(trigger);
    expect(click).not.toHaveBeenCalled();
    expect(snackbar).toHaveBeenCalledWith(
      'Could not export tournament. Please try again.',
      'OK',
      expect.any(Object),
    );
  });

  it('previews unsaved selections while off and applies them when starting without Save', async () => {
    const { fixture, component, api, current } = runPage();
    const select = await TestbedHarnessEnvironment.loader(fixture).getHarness(MatSelectHarness);
    const input = fixture.nativeElement.querySelector('input') as HTMLInputElement;
    expect(runBanner(fixture)).toBe('Auto-run off · limit 2 · $5.00 budget');

    await select.open();
    await select.clickOptions({ text: '4' });
    input.value = '2';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
    component.loadTournament();
    expect(await select.getValueText()).toBe('4');
    expect(runBanner(fixture)).toBe('Auto-run off · limit 4 · $2.00 budget');
    expect(component.tournament()?.maxConcurrentGames).toBe(2);
    expect(component.tournament()?.budgetUsd).toBe(5);
    expect(fixture.nativeElement.querySelector('.save-run')).toBeNull();
    expect(fixture.nativeElement.querySelector('.run-unsaved')).toBeNull();

    input.value = '';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
    expect(runBanner(fixture)).toBe('Auto-run off · limit 4 · no budget cap');
    api.runTournament.mockReturnValueOnce(
      of({ ...current, autoRunStatus: 'RUNNING', maxConcurrentGames: 4, budgetUsd: null }),
    );
    (fixture.nativeElement.querySelector('.start-run') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(api.runTournament).toHaveBeenCalledExactlyOnceWith('t-1', {
      maxConcurrentGames: 4,
      budgetUsd: null,
    });
    expect(api.updateTournamentRun).not.toHaveBeenCalled();
    expect(runBanner(fixture)).toBe(
      'Running · 0 games running (limit 4) · $0.00 spent · no budget cap · 0 need attention',
    );
    expect(fixture.nativeElement.querySelector('.save-run').disabled).toBe(true);
    expect(fixture.nativeElement.querySelector('.run-unsaved')).toBeNull();
  });

  it.each(['RUNNING', 'PAUSED'] as const)(
    'keeps the %s banner on applied limits until changed limits are saved',
    async (status) => {
      const { fixture, component, api, current } = runPage('IN_PROGRESS', status);
      const applied: Tournament = {
        ...current,
        maxConcurrentGames: 4,
        budgetUsd: 2,
        games: [
          finalGame({ status: 'IN_PROGRESS', totalCostUsd: 0.01 }),
          finalGame({ id: 'running-2', status: 'IN_PROGRESS', totalCostUsd: 0.01 }),
          finalGame({ id: 'paused', status: 'PAUSED' }),
        ],
      };
      api.getTournament.mockReturnValue(of(applied));
      component.loadTournament();
      const select = await TestbedHarnessEnvironment.loader(fixture).getHarness(MatSelectHarness);
      const input = fixture.nativeElement.querySelector('input') as HTMLInputElement;
      const save = fixture.nativeElement.querySelector('.save-run') as HTMLButtonElement;
      const label = status === 'RUNNING' ? 'Running' : 'Paused';
      const banner = `${label} · 2 games running (limit 4) · $0.02 of $2.00 spent · 1 need attention`;
      expect(runBanner(fixture)).toBe(banner);
      expect(save.disabled).toBe(true);
      expect(fixture.nativeElement.querySelector('.run-unsaved')).toBeNull();

      await select.open();
      await select.clickOptions({ text: '3' });
      input.value = '';
      input.dispatchEvent(new Event('input', { bubbles: true }));
      fixture.detectChanges();
      component.loadTournament();
      expect(await select.getValueText()).toBe('3');
      expect(input.value).toBe('');
      expect(runBanner(fixture)).toBe(banner);
      expect(save.disabled).toBe(false);
      expect(fixture.nativeElement.querySelector('.run-save-controls').textContent).toContain(
        'Unsaved changes',
      );

      api.updateTournamentRun.mockReturnValueOnce(
        of({ ...applied, maxConcurrentGames: 3, budgetUsd: null }),
      );
      save.click();
      fixture.detectChanges();
      expect(api.updateTournamentRun).toHaveBeenCalledExactlyOnceWith('t-1', {
        maxConcurrentGames: 3,
        budgetUsd: null,
      });
      expect(api.runTournament).not.toHaveBeenCalled();
      expect(component.tournament()?.autoRunStatus).toBe(status);
      expect(runBanner(fixture)).toBe(
        `${label} · 2 games running (limit 3) · $0.02 spent · no budget cap · 1 need attention`,
      );
      expect(save.disabled).toBe(true);
      expect(fixture.nativeElement.querySelector('.run-unsaved')).toBeNull();
    },
  );

  it.each(['RUNNING', 'PAUSED'] as const)(
    'disables Save for equivalent budgets and reverted edits while %s',
    async (status) => {
      const { fixture, component, api } = runPage('IN_PROGRESS', status);
      const select = await TestbedHarnessEnvironment.loader(fixture).getHarness(MatSelectHarness);
      const input = fixture.nativeElement.querySelector('input') as HTMLInputElement;
      const save = fixture.nativeElement.querySelector('.save-run') as HTMLButtonElement;
      input.value = '5.00';
      input.dispatchEvent(new Event('input', { bubbles: true }));
      fixture.detectChanges();
      expect(save.disabled).toBe(true);
      expect(fixture.nativeElement.querySelector('.run-unsaved')).toBeNull();

      await select.open();
      await select.clickOptions({ text: '4' });
      expect(save.disabled).toBe(false);
      expect(fixture.nativeElement.querySelector('.run-unsaved')).not.toBeNull();
      await select.open();
      await select.clickOptions({ text: '2' });
      expect(save.disabled).toBe(true);
      expect(fixture.nativeElement.querySelector('.run-unsaved')).toBeNull();

      input.value = '10';
      input.dispatchEvent(new Event('input', { bubbles: true }));
      fixture.detectChanges();
      expect(save.disabled).toBe(false);
      expect(fixture.nativeElement.querySelector('.run-unsaved')).not.toBeNull();
      input.value = '5';
      input.dispatchEvent(new Event('input', { bubbles: true }));
      fixture.detectChanges();
      expect(save.disabled).toBe(true);
      expect(fixture.nativeElement.querySelector('.run-unsaved')).toBeNull();
      component.saveRunSettings();
      expect(api.updateTournamentRun).not.toHaveBeenCalled();
    },
  );

  it.each(['OFF', 'FINISHED', undefined] as const)(
    'hides Save and ignores save requests when run status is %s',
    (status) => {
      const { fixture, component, api, current } = runPage();
      component.tournament.set({ ...current, autoRunStatus: status });
      component.runConcurrency = 4;
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelector('.save-run')).toBeNull();
      expect(fixture.nativeElement.querySelector('.run-unsaved')).toBeNull();
      component.saveRunSettings();
      expect(api.updateTournamentRun).not.toHaveBeenCalled();
    },
  );

  it('disables Save and prevents duplicate saves while applying limits', async () => {
    const { fixture, component, api, current } = runPage('IN_PROGRESS', 'RUNNING');
    const pending = new Subject<Tournament>();
    api.updateTournamentRun.mockReturnValue(pending);
    await fixture.whenStable();
    const input = fixture.nativeElement.querySelector('input') as HTMLInputElement;
    input.value = '10';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    fixture.detectChanges();
    const save = fixture.nativeElement.querySelector('.save-run') as HTMLButtonElement;
    save.click();
    component.saveRunSettings();
    fixture.detectChanges();
    expect(save.disabled).toBe(true);
    expect(api.updateTournamentRun).toHaveBeenCalledExactlyOnceWith('t-1', {
      budgetUsd: 10,
    });
    pending.next({ ...current, budgetUsd: 10 });
    pending.complete();
    fixture.detectChanges();
    expect(save.disabled).toBe(true);
    expect(fixture.nativeElement.querySelector('.run-unsaved')).toBeNull();
  });

  it('pauses, resumes and edits limits without implicitly resuming', () => {
    const { fixture, component, api } = runPage('IN_PROGRESS', 'RUNNING');
    (fixture.nativeElement.querySelector('.pause-run') as HTMLButtonElement).click();
    expect(api.pauseTournamentRun).toHaveBeenCalledWith('t-1');
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Paused by user');
    expect(fixture.nativeElement.querySelector('.start-run').textContent).toContain('Resume run');
    component.runBudget = '10';
    component.saveRunSettings();
    expect(api.updateTournamentRun).toHaveBeenCalledWith('t-1', {
      budgetUsd: 10,
    });
    expect(api.runTournament).not.toHaveBeenCalled();
    component.runTournament();
    expect(api.runTournament).toHaveBeenCalledOnce();
  });

  it.each(['COMPLETED', 'CANCELLED'] as const)('disables run actions for %s', (status) => {
    const { fixture, component, api } = runPage(status);
    expect((fixture.nativeElement.querySelector('.start-run') as HTMLButtonElement).disabled).toBe(
      true,
    );
    component.runTournament();
    component.pauseRun();
    component.saveRunSettings();
    expect(api.runTournament).not.toHaveBeenCalled();
    expect(api.pauseTournamentRun).not.toHaveBeenCalled();
    expect(api.updateTournamentRun).not.toHaveBeenCalled();
  });

  it.each(['0', '-1', '0.001', 'NaN', 'Infinity', '1e', 'abc', ' '])(
    'rejects invalid budget %s',
    (budget) => {
      const { component, api } = runPage();
      component.runBudget = budget;
      component.runTournament();
      expect(component.validRunSettings).toBe(false);
      expect(api.runTournament).not.toHaveBeenCalled();
    },
  );

  it.each(['OFF', 'PAUSED', 'RUNNING'] as const)(
    'preserves malformed 1e and blocks run/resume/save with a message when %s',
    async (status) => {
      const { fixture, component, api } = runPage('IN_PROGRESS', status);
      await fixture.whenStable();
      const input = fixture.nativeElement.querySelector('input') as HTMLInputElement;
      expect(input.value).toBe('5');
      input.value = '1e';
      input.dispatchEvent(new Event('input', { bubbles: true }));
      fixture.detectChanges();

      expect(component.runBudget).toBe('1e');
      expect(component.validRunSettings).toBe(false);
      expect(input.getAttribute('aria-invalid')).toBe('true');
      expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain(
        'Enter a positive budget with at most two decimal places',
      );
      const run = fixture.nativeElement.querySelector('.start-run') as HTMLButtonElement | null;
      if (run) expect(run.disabled).toBe(true);
      const save = fixture.nativeElement.querySelector('.save-run') as HTMLButtonElement | null;
      if (status === 'OFF') {
        expect(save).toBeNull();
        expect(runBanner(fixture)).toBe('Auto-run off · limit 2 · budget needs correction');
      } else {
        expect(save?.disabled).toBe(true);
        expect(runBanner(fixture)).toContain('of $5.00 spent');
        expect(fixture.nativeElement.querySelector('.run-unsaved')).not.toBeNull();
      }
      component.runTournament();
      component.saveRunSettings();
      component.loadTournament();
      expect(component.runBudget).toBe('1e');
      expect(api.runTournament).not.toHaveBeenCalled();
      expect(api.updateTournamentRun).not.toHaveBeenCalled();
      expect(component.tournament()?.budgetUsd).toBe(5);
    },
  );

  it.each(['OFF', 'PAUSED', 'RUNNING'] as const)(
    'allows deliberately clearing an existing cap after malformed input when %s',
    async (status) => {
      const { fixture, component, api } = runPage('IN_PROGRESS', status);
      await fixture.whenStable();
      const input = fixture.nativeElement.querySelector('input') as HTMLInputElement;
      input.value = '1e';
      input.dispatchEvent(new Event('input', { bubbles: true }));
      fixture.detectChanges();
      input.value = '';
      input.dispatchEvent(new Event('input', { bubbles: true }));
      fixture.detectChanges();

      expect(component.runBudget).toBe('');
      expect(component.validRunSettings).toBe(true);
      expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeNull();
      const action = status === 'RUNNING' ? '.save-run' : '.start-run';
      expect(fixture.nativeElement.querySelector(action).disabled).toBe(false);
      (fixture.nativeElement.querySelector(action) as HTMLButtonElement).click();
      const request = status === 'RUNNING' ? api.updateTournamentRun : api.runTournament;
      expect(request).toHaveBeenCalledExactlyOnceWith('t-1', {
        budgetUsd: null,
      });
    },
  );

  it.each([
    ['OFF', '5.0.1'],
    ['PAUSED', '5.000'],
    ['RUNNING', '000000000000000005'],
  ] as const)(
    'preserves malformed budget text across polls when %s (%s)',
    async (status, malformed) => {
      const { fixture, component, api } = runPage('IN_PROGRESS', status);
      await fixture.whenStable();
      const input = fixture.nativeElement.querySelector('input') as HTMLInputElement;
      input.value = malformed;
      input.dispatchEvent(new Event('input', { bubbles: true }));
      fixture.detectChanges();
      component.loadTournament();
      fixture.detectChanges();
      expect(component.runBudget).toBe(malformed);
      expect(fixture.nativeElement.querySelector('#run-budget-error')).not.toBeNull();
      const action = status === 'RUNNING' ? '.save-run' : '.start-run';
      expect(fixture.nativeElement.querySelector(action).disabled).toBe(true);
      component.runTournament();
      component.saveRunSettings();
      expect(api.runTournament).not.toHaveBeenCalled();
      expect(api.updateTournamentRun).not.toHaveBeenCalled();
    },
  );

  it('submits a decimal budget as a number after correcting invalid input', async () => {
    const { fixture, component, api } = runPage();
    await fixture.whenStable();
    const input = fixture.nativeElement.querySelector('input') as HTMLInputElement;
    for (const value of ['1e', '1.25']) {
      input.value = value;
      input.dispatchEvent(new Event('input', { bubbles: true }));
      fixture.detectChanges();
    }
    component.runTournament();
    expect(api.runTournament).toHaveBeenCalledExactlyOnceWith('t-1', {
      budgetUsd: 1.25,
    });
  });

  it('keeps drafts across polls and ignores a poll predating a run change', () => {
    const { component, api, current } = runPage();
    component.runBudget = '9';
    component.loadTournament();
    expect(component.runBudget).toBe('9');
    const oldPoll = new Subject<Tournament>();
    api.getTournament.mockReturnValueOnce(oldPoll);
    component.loadTournament();
    const pending = new Subject<Tournament>();
    api.runTournament.mockReturnValue(pending);
    component.runTournament();
    component.runTournament();
    expect(api.runTournament).toHaveBeenCalledOnce();
    oldPoll.next({ ...current, status: 'CANCELLED' });
    expect(component.tournament()?.status).toBe('IN_PROGRESS');
    pending.next({ ...current, autoRunStatus: 'RUNNING', budgetUsd: 9 });
    oldPoll.next(current);
    expect(component.tournament()?.autoRunStatus).toBe('RUNNING');
    expect(component.runBusy()).toBe(false);
    expect(component.runSettingsChanged).toBe(false);
  });

  it('surfaces run failures and releases the controls', () => {
    const { component, api } = runPage();
    api.runTournament.mockReturnValue(throwError(() => ({ error: { error: 'Budget rejected' } })));
    component.runTournament();
    expect(component.runBusy()).toBe(false);
    expect(document.body.textContent).toContain('Budget rejected');
  });

  it('is hidden while the tournament is still running', () => {
    const el = render(tournament('IN_PROGRESS', 'White', finalGame({ status: 'WAITING' })));

    expect(el.querySelector('.champion-card')).toBeNull();
  });

  it('is hidden for a completed tournament without a winner', () => {
    const el = render(tournament('COMPLETED', null, finalGame()));

    expect(el.querySelector('.champion-card')).toBeNull();
  });

  it('shows the winner of a completed tournament after the final', () => {
    const el = render(tournament('COMPLETED', 'White', finalGame({ advancedPlayerName: 'White' })));

    const bracket = el.querySelector<HTMLElement>(
      '[role="region"][aria-label="Tournament bracket"]',
    );
    expect(bracket?.tabIndex).toBe(0);
    expect([...bracket!.querySelectorAll('h3')].map((heading) => heading.textContent)).toEqual([
      'Final',
      'Champion',
    ]);
    expect(bracket?.querySelector('.champion-card')?.textContent).toContain('White');
  });

  it('keeps a 60-character champion name intact and lets the header wrap', () => {
    const name = 'W'.repeat(60);
    const element = render(tournament('COMPLETED', name, finalGame()));
    const header = element.querySelector('.header-left')!;
    const facts = element.querySelector('.meta-row')!;
    const champion = facts.querySelector('.champion-label')!;
    const count = facts.querySelector('.count-tag')!;
    expect(champion.querySelector('span')?.textContent).toBe(`Champion: ${name}`);
    expect(getComputedStyle(header).minWidth).toBe('0px');
    expect(getComputedStyle(facts).flexWrap).toBe('wrap');
    expect(getComputedStyle(champion).overflowWrap).toBe('anywhere');
    expect(getComputedStyle(count).whiteSpace).toBe('nowrap');
  });

  it('shows the winner of a final decided by the draw policy', () => {
    const el = render(
      tournament(
        'COMPLETED',
        'Black',
        finalGame({ result: 'DRAW', resultReason: 'STALEMATE', advancedPlayerName: 'Black' }),
      ),
    );

    expect(el.querySelector('.champion-card')?.textContent).toContain('Black');
    expect(el.querySelector('.series-context')?.textContent).toContain(
      'Black wins the tournament on the draw policy\u00a0(0\u20110)',
    );
  });

  function cancelPage(
    status: Tournament['status'],
    result = of(
      tournament(
        'CANCELLED',
        null,
        finalGame({ status: 'CANCELLED', result: null, resultReason: null }),
      ),
    ),
  ) {
    const current = tournament(status, null, finalGame({ status: 'PAUSED', result: null }));
    const api = {
      getTournament: vi.fn().mockReturnValue(of(current)),
      getTournamentCostSummary: () => EMPTY,
      cancelTournament: vi.fn().mockReturnValue(result),
    };
    TestBed.configureTestingModule({
      providers: [
        { provide: OpenRouterAccess, useValue: { withKey: (action: () => void) => action() } },
        provideRouter([]),
        { provide: ApiService, useValue: api },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ id: current.id }) } },
        },
      ],
    });
    const fixture = TestBed.createComponent(TournamentBracket);
    fixture.detectChanges();
    const button = () =>
      fixture.nativeElement.querySelector('.cancel-tournament') as HTMLButtonElement;
    return { fixture, api, button };
  }

  it.each(['CREATED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'] as Tournament['status'][])(
    'enables cancellation only for unfinished tournaments: %s',
    (status) => {
      const { button } = cancelPage(status);
      expect(button().disabled).toBe(status === 'COMPLETED' || status === 'CANCELLED');
    },
  );

  it('explains cancellation in the confirmation and preserves the tournament when declined', () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    const { button, api } = cancelPage('IN_PROGRESS');
    button().click();
    expect(confirm).toHaveBeenCalledOnce();
    expect(confirm.mock.calls[0][0]).toContain('Unfinished games end without a result');
    expect(confirm.mock.calls[0][0]).toContain('Finished games, moves, chat and costs stay');
    expect(api.cancelTournament).not.toHaveBeenCalled();
  });

  it('confirms, blocks duplicate requests and shows the cancelled tournament and game', () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const pending = new Subject<Tournament>();
    const { fixture, button, api } = cancelPage('IN_PROGRESS', pending);
    button().click();
    fixture.componentInstance.cancelTournament();
    fixture.detectChanges();
    expect(button().disabled).toBe(true);
    expect(api.cancelTournament).toHaveBeenCalledExactlyOnceWith('t-1');
    pending.next(
      tournament(
        'CANCELLED',
        null,
        finalGame({ status: 'CANCELLED', result: null, resultReason: null }),
      ),
    );
    pending.complete();
    fixture.detectChanges();
    expect(button().disabled).toBe(true);
    expect(fixture.nativeElement.querySelector('.status-badge').textContent).toContain('Cancelled');
    expect(fixture.nativeElement.querySelector('.state-pill').textContent).toContain('Cancelled');
    expect(fixture.nativeElement.querySelector('.series-context').textContent).toContain(
      'without a result',
    );
    expect(fixture.nativeElement.querySelector('.card-footer').textContent).not.toContain('Resume');
    expect(fixture.nativeElement.querySelector('.card-footer').textContent).toContain('Review');
  });

  it.each(['CREATED', 'IN_PROGRESS'] as Tournament['status'][])(
    'ignores a delayed pre-cancel GET for %s and keeps cancelled controls',
    (status) => {
      vi.spyOn(window, 'confirm').mockReturnValue(true);
      const cancellation = new Subject<Tournament>();
      const stale = new Subject<Tournament>();
      const { fixture, api, button } = cancelPage(status, cancellation);
      const component = fixture.componentInstance;
      api.getTournament.mockReturnValue(stale);
      component.loadTournament();
      component.cancelTournament();
      component.loadTournament();
      expect(api.getTournament).toHaveBeenCalledTimes(2); // no poll during cancellation
      cancellation.next(
        tournament('CANCELLED', null, finalGame({ status: 'CANCELLED', result: null })),
      );
      stale.next(tournament(status, null, finalGame({ status: 'PAUSED', result: null })));
      fixture.detectChanges();
      expect(component.tournament()?.status).toBe('CANCELLED');
      expect(component.tournament()?.games[0].status).toBe('CANCELLED');
      expect(button().disabled).toBe(true);
      expect(fixture.nativeElement.querySelector('.generate-section')).toBeNull();
      expect(fixture.nativeElement.querySelector('.card-footer').textContent).not.toContain(
        'Resume',
      );
      fixture.destroy();
    },
  );

  it('ignores an obsolete poll error after cancellation succeeds', () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const { fixture, api } = cancelPage('IN_PROGRESS');
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate');
    const stale = new Subject<Tournament>();
    api.getTournament.mockReturnValue(stale);
    fixture.componentInstance.loadTournament();
    fixture.componentInstance.cancelTournament();
    stale.error(new Error('obsolete request failed'));
    expect(navigate).not.toHaveBeenCalled();
    expect(fixture.componentInstance.tournament()?.status).toBe('CANCELLED');
    fixture.destroy();
  });

  it('clears pending cancellation and reloads after an API conflict', () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const { fixture, api } = cancelPage(
      'IN_PROGRESS',
      throwError(() => ({ status: 409 })),
    );
    fixture.componentInstance.cancelTournament();
    expect(fixture.componentInstance.cancelling()).toBe(false);
    expect(api.getTournament).toHaveBeenCalledTimes(2);
  });

  function drawPage(result = of([] as Game[])) {
    const created = tournament('CREATED', null, finalGame());
    created.games = [];
    created.participants = [
      {
        id: 'a',
        playerName: 'A',
        controlType: 'AI',
        modelId: 'm-a',
        customInstructions: null,
        seed: 0,
      },
      {
        id: 'b',
        playerName: 'B',
        controlType: 'AI',
        modelId: 'm-b',
        customInstructions: null,
        seed: 1,
      },
    ];
    const drawn: Tournament = {
      ...created,
      status: 'IN_PROGRESS',
      games: [finalGame({ status: 'WAITING' })],
      participants: [
        { ...created.participants[0], seed: 1 },
        { ...created.participants[1], seed: 0 },
      ],
    };
    const api = {
      getTournament: vi.fn().mockReturnValueOnce(of(created)).mockReturnValue(of(drawn)),
      getTournamentCostSummary: () => EMPTY,
      generateBracket: vi.fn().mockReturnValue(result),
    };
    TestBed.configureTestingModule({
      providers: [
        { provide: OpenRouterAccess, useValue: { withKey: (action: () => void) => action() } },
        provideRouter([]),
        { provide: ApiService, useValue: api },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ id: created.id }) } },
        },
      ],
    });
    const fixture = TestBed.createComponent(TournamentBracket);
    fixture.detectChanges();
    return { fixture, api };
  }

  it.each(['RANDOM', 'ORDER'] as BracketDrawMode[])(
    'keeps manual generation for existing drafts, defaults to Random, and submits %s',
    async (mode) => {
      const { fixture, api } = drawPage();
      const select = await TestbedHarnessEnvironment.loader(fixture).getHarness(MatSelectHarness);
      expect(await select.getValueText()).toBe('Random draw');
      if (mode === 'ORDER') {
        await select.open();
        await select.clickOptions({ text: 'In added order' });
      }

      const button = fixture.nativeElement.querySelector('.generate-section button') as HTMLElement;
      button.click();
      fixture.detectChanges();

      expect(api.generateBracket).toHaveBeenCalledExactlyOnceWith('t-1', mode);
      expect(fixture.nativeElement.querySelector('.generate-section')).toBeNull();
      const seeds = Array.from(
        fixture.nativeElement.querySelectorAll('.participant-seeds li') as NodeListOf<HTMLElement>,
        (row) => row.textContent?.trim(),
      );
      expect(seeds).toEqual(['Seed 0: B', 'Seed 1: A']);
    },
  );

  it('disables the draw selector and prevents duplicate requests while generating', async () => {
    const pending = new Subject<Game[]>();
    const { fixture, api } = drawPage(pending);
    const select = await TestbedHarnessEnvironment.loader(fixture).getHarness(MatSelectHarness);

    fixture.componentInstance.generateBracket();
    fixture.componentInstance.generateBracket();
    fixture.detectChanges();

    expect(await select.isDisabled()).toBe(true);
    expect(fixture.nativeElement.querySelector('.generate-section button').disabled).toBe(true);
    expect(api.generateBracket).toHaveBeenCalledOnce();
    pending.complete();
  });

  it('reloads the winning draw after a concurrent generation conflict', () => {
    const { fixture, api } = drawPage(
      throwError(() => ({
        status: 409,
        error: { error: 'The bracket has already been generated' },
      })),
    );

    fixture.componentInstance.generateBracket();
    fixture.detectChanges();

    expect(fixture.componentInstance.generating()).toBe(false);
    expect(api.getTournament).toHaveBeenCalledTimes(2);
    expect(fixture.nativeElement.querySelector('.generate-section')).toBeNull();
    expect(fixture.nativeElement.querySelector('.participant-seeds')?.textContent).toContain(
      'Seed 0: B',
    );
  });
});

function tournament(
  status: Tournament['status'],
  winnerName: string | null,
  final: Game,
): Tournament {
  return {
    id: 't-1',
    name: 'Cup',
    status,
    winnerName,
    format: 'SINGLE_ELIMINATION',
    drawPolicy: 'BLACK_ADVANCES',
    sharedCustomInstructions: null,
    moveTimeoutSeconds: 60,
    maxAttempts: 3,
    matchupBestOf: 1,
    finalsBestOf: null,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
    participants: [],
    games: [final],
  };
}

function finalGame(overrides: Partial<Game> = {}): Game {
  return {
    id: 'final-1',
    tournamentId: 't-1',
    whitePlayerName: 'White',
    whiteControlType: 'AI',
    whiteModelId: 'model-a',
    blackPlayerName: 'Black',
    blackControlType: 'AI',
    blackModelId: 'model-b',
    status: 'COMPLETED',
    result: 'WHITE_WINS',
    resultReason: 'CHECKMATE',
    pgn: null,
    currentFen: 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1',
    bracketRound: 'Final',
    bracketPosition: 0,
    seriesId: 'final-1',
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
