import { By } from '@angular/platform-browser';
import { MatTooltip } from '@angular/material/tooltip';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';
import { TournamentSetup } from './tournament-setup';
import { ApiService } from '../../services/api.service';
import { OpenRouterModelOption } from '../../models/openrouter.model';
import { DEFAULT_MOVE_TIMEOUT_SECONDS } from '../../models/tournament.model';

describe('TournamentSetup', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('sticks only while the measured aside fits and cleans up observation on destroy', async () => {
    let measureAside!: () => void;
    const observe = vi.fn();
    const disconnect = vi.fn();
    vi.stubGlobal(
      'ResizeObserver',
      class {
        constructor(private callback: () => void) {}
        observe(element: Element) {
          if (element.classList.contains('setup-aside')) {
            measureAside = this.callback;
            observe(element);
          }
        }
        unobserve() {}
        disconnect = disconnect;
      },
    );
    vi.stubGlobal('innerWidth', 1200);
    vi.stubGlobal('innerHeight', 1000);
    const fixture = await renderSetup();
    const aside: HTMLElement = fixture.nativeElement.querySelector('.setup-aside');
    const height = vi.spyOn(aside, 'offsetHeight', 'get').mockReturnValue(888);
    expect(observe).toHaveBeenCalledWith(aside);
    expect(aside.classList.contains('aside-sticky')).toBe(false);

    measureAside();
    fixture.detectChanges();
    expect(aside.classList.contains('aside-sticky')).toBe(true);
    height.mockReturnValue(889);
    measureAside();
    fixture.detectChanges();
    expect(aside.classList.contains('aside-sticky')).toBe(false);
    height.mockReturnValue(888);
    measureAside();
    fixture.detectChanges();
    expect(aside.classList.contains('aside-sticky')).toBe(true);

    vi.stubGlobal('innerHeight', 999);
    window.dispatchEvent(new Event('resize'));
    fixture.detectChanges();
    expect(aside.classList.contains('aside-sticky')).toBe(false);
    vi.stubGlobal('innerHeight', 1000);
    window.dispatchEvent(new Event('resize'));
    fixture.detectChanges();
    expect(aside.classList.contains('aside-sticky')).toBe(true);
    vi.stubGlobal('innerWidth', 1199);
    window.dispatchEvent(new Event('resize'));
    fixture.detectChanges();
    expect(aside.classList.contains('aside-sticky')).toBe(false);

    fixture.destroy();
    expect(disconnect).toHaveBeenCalled();
    vi.stubGlobal('innerWidth', 1200);
    window.dispatchEvent(new Event('resize'));
    expect(fixture.componentInstance.asideSticky()).toBe(false);
  });

  it('keeps the aside in normal flow without ResizeObserver', async () => {
    vi.stubGlobal('ResizeObserver', undefined);
    const fixture = await renderSetup();
    window.dispatchEvent(new Event('resize'));
    fixture.detectChanges();
    expect(fixture.componentInstance.asideSticky()).toBe(false);
    expect(fixture.nativeElement.querySelector('.aside-sticky')).toBeNull();
    fixture.destroy();
  });

  it('defaults to Standard, sends fixed book selection, and omits stale ids in other modes', () => {
    const api = createApiService();
    const component = new TournamentSetup(api, createRouter(), createRoute());
    component.create();
    expect(api.createTournamentPayload?.['openingMode']).toBe('STANDARD');
    expect(api.createTournamentPayload?.['openingId']).toBeUndefined();
    component.loadOpenings();
    component.openingMode.set('FIXED');
    component.create();
    expect(api.createTournamentPayload?.['openingMode']).toBe('STANDARD');
    component.openingId.set('ruy-lopez');
    component.create();
    expect(api.createTournamentPayload?.['openingMode']).toBe('FIXED');
    expect(api.createTournamentPayload?.['openingId']).toBe('ruy-lopez');
    component.openingMode.set('RANDOM_BOOK');
    component.create();
    expect(api.createTournamentPayload?.['openingMode']).toBe('RANDOM_BOOK');
    expect(api.createTournamentPayload?.['openingId']).toBeUndefined();
  });

  it('keeps Standard available after catalog failure and supports retry', () => {
    const api = createApiService();
    const getOpenings = api.getOpenings;
    api.getOpenings = () => throwError(() => new Error('offline'));
    const component = new TournamentSetup(api, createRouter(), createRoute());
    component.loadOpenings();
    expect(component.openingsError()).toContain('Retry or use Standard');
    expect(component.openingValid()).toBe(true);
    component.openingMode.set('RANDOM_BOOK');
    expect(component.openingValid()).toBe(false);
    api.getOpenings = getOpenings;
    component.loadOpenings();
    expect(component.openingValid()).toBe(true);
    expect(component.openingsError()).toBeNull();
  });

  it('disables Create in the lineup for Fixed until a known book line is picked', async () => {
    TestBed.configureTestingModule({
      imports: [TournamentSetup],
      providers: [
        { provide: ApiService, useValue: createApiService() },
        { provide: Router, useValue: createRouter() },
        { provide: ActivatedRoute, useValue: createRoute() },
      ],
    });
    const fixture = TestBed.createComponent(TournamentSetup);
    fixture.detectChanges();
    await fixture.whenStable();
    const component = fixture.componentInstance;
    const element: HTMLElement = fixture.nativeElement;
    component.name = 'Opening Arena';
    component.participants.set([
      pricedParticipant('Alpha', 'example/alpha', 1, 2),
      pricedParticipant('Beta', 'example/beta', 1, 2),
    ]);
    fixture.detectChanges();
    const create = element.querySelector<HTMLButtonElement>('.lineup-card .create-button')!;
    expect(create.disabled).toBe(false);
    expect(element.querySelector('#opening-hint')?.textContent).toContain('standard position');
    element.querySelector<HTMLElement>('.opening-controls mat-select')!.click();
    fixture.detectChanges();
    await fixture.whenStable();
    Array.from(document.querySelectorAll<HTMLElement>('mat-option'))
      .find((option) => option.textContent?.includes('Fixed book line'))!
      .click();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(component.openingMode()).toBe('FIXED');
    expect(component.openingValid()).toBe(false);
    expect(create.disabled).toBe(true);
    expect(element.querySelector('#opening-hint')?.textContent).toContain('selected book line');
    element.querySelectorAll<HTMLElement>('.opening-controls mat-select')[1].click();
    fixture.detectChanges();
    await fixture.whenStable();
    Array.from(document.querySelectorAll<HTMLElement>('mat-option'))
      .find((option) => option.textContent?.includes('C60 · Ruy Lopez'))!
      .click();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(component.openingId()).toBe('ruy-lopez');
    expect(component.openingValid()).toBe(true);
    expect(create.disabled).toBe(false);
  });

  it('adds participant-specific custom instructions when provided', () => {
    const component = new TournamentSetup(createApiService(), createRouter(), createRoute());
    component.newPlayerName = 'Alpha';
    component.newControlType = 'AI';
    component.newModelId = 'model-alpha';
    component.newCustomInstructions = 'Play aggressively.';

    component.addParticipant();

    expect(component.participants()[0].customInstructions).toBe('Play aggressively.');
  });

  it('creates tournament with shared custom instructions and participant fallback behavior', () => {
    const api = createApiService();
    const component = new TournamentSetup(api, createRouter(), createRoute());

    component.name = 'Prompt Arena';
    component.sharedCustomInstructions = 'Prefer tactical complications.';
    component.participants.set([
      {
        playerName: 'Alpha',
        controlType: 'AI',
        modelId: 'model-alpha',
        seed: 0,
        customInstructions: null,
      },
      {
        playerName: 'Beta',
        controlType: 'AI',
        modelId: 'model-beta',
        seed: 1,
        customInstructions: 'Avoid trades.',
      },
    ]);

    component.create();

    expect(api.createTournamentPayload?.['name']).toBe('Prompt Arena');
    expect(api.createTournamentPayload?.['sharedCustomInstructions']).toBe(
      'Prefer tactical complications.',
    );
    const participants = api.createTournamentPayload?.['participants'] as Array<
      Record<string, unknown>
    >;
    expect(participants).toHaveLength(2);
    expect(participants[0]['customInstructions']).toBeNull();
    expect(participants[1]['customInstructions']).toBe('Avoid trades.');
    expect(api.addedParticipants).toHaveLength(0);
    expect(typeof api.createTournamentPayload?.['clientRequestId']).toBe('string');
    expect(api.createTournamentPayload?.['maxOutputTokens']).toBe(16384);
  });

  it('renders the token bounds and credit-balance hint and validates the boundaries', async () => {
    TestBed.configureTestingModule({
      imports: [TournamentSetup],
      providers: [
        { provide: ApiService, useValue: createApiService() },
        { provide: Router, useValue: createRouter() },
        { provide: ActivatedRoute, useValue: createRoute() },
      ],
    });
    const fixture = TestBed.createComponent(TournamentSetup);
    fixture.detectChanges();
    await fixture.whenStable();
    const element: HTMLElement = fixture.nativeElement;
    const input = element.querySelector<HTMLInputElement>('input[max="262144"]')!;
    expect(input.min).toBe('128');
    expect(input.value).toBe('16384');
    const hint = input.closest('mat-form-field')?.textContent?.replace(/\s+/g, ' ');
    expect(hint).toContain('Cut-off answers pause the game.');
    const help = fixture.debugElement.query(
      By.css('button[aria-label="About the output token limit"]'),
    );
    expect(help.nativeElement.querySelector('mat-icon').textContent).toBe('help_outline');
    expect(help.injector.get(MatTooltip).message).toContain('Includes reasoning tokens.');
    expect(help.injector.get(MatTooltip).message).toContain(
      'OpenRouter checks the credit balance against max_tokens × output price',
    );
    expect(help.injector.get(MatTooltip).message).toContain('even if far fewer tokens are used');
    for (const [value, valid] of [
      [127, false],
      [128, true],
      [262144, true],
      [262145, false],
    ] as const) {
      input.value = String(value);
      input.dispatchEvent(new Event('input'));
      fixture.detectChanges();
      await fixture.whenStable();
      expect(input.classList.contains('ng-valid')).toBe(valid);
    }
  });

  it('sends the chosen max output tokens per answer', () => {
    const api = createApiService();
    const component = new TournamentSetup(api, createRouter(), createRoute());
    component.name = 'Reasoning Arena';
    component.maxOutputTokens = 16384;

    component.create();

    expect(api.createTournamentPayload?.['maxOutputTokens']).toBe(16384);
  });

  it('starts without a time limit per move and sends no limit by default', () => {
    const api = createApiService();
    const component = new TournamentSetup(api, createRouter(), createRoute());
    component.name = 'Default Arena';

    expect(component.limitMoveTime).toBe(false);
    expect(component.moveTimeout).toBe(120);
    expect(DEFAULT_MOVE_TIMEOUT_SECONDS).toBeNull();

    component.create();

    expect(api.createTournamentPayload?.['moveTimeoutSeconds']).toBe(0);
  });

  it('sends the move time limit in seconds while the limit is on', () => {
    const api = createApiService();
    const component = new TournamentSetup(api, createRouter(), createRoute());
    component.name = 'Timed Arena';
    component.limitMoveTime = true;
    component.moveTimeout = 45;

    component.create();

    expect(component.limitMoveTime).toBe(true);
    expect(api.createTournamentPayload?.['moveTimeoutSeconds']).toBe(45);
  });

  it('sends 0 (no limit) when the move time limit is switched off', () => {
    const api = createApiService();
    const component = new TournamentSetup(api, createRouter(), createRoute());
    component.name = 'Untimed Arena';
    component.moveTimeout = 45;
    component.limitMoveTime = false;

    component.create();

    expect(api.createTournamentPayload?.['moveTimeoutSeconds']).toBe(0);
  });

  it('shows the server error, keeps the request id for a retry, and does not navigate', () => {
    const api = createApiService();
    const router = { navigate: vi.fn() } as any;
    const component = new TournamentSetup(api, router, createRoute());
    component.name = 'Failing Arena';
    component.participants.set([
      { playerName: 'Alpha', controlType: 'AI', modelId: 'm-a', seed: 0, customInstructions: null },
      {
        playerName: 'Beta',
        controlType: 'AI',
        modelId: 'bogus',
        seed: 1,
        customInstructions: null,
      },
    ]);
    api.createTournament = ((payload: Record<string, unknown>) => {
      api.createTournamentPayload = payload;
      return throwError(() => ({
        status: 400,
        error: { error: 'Model is not allowed by backend policy: bogus' },
      }));
    }) as any;

    component.create();
    const firstRequestId = api.createTournamentPayload?.['clientRequestId'];
    component.create();

    expect(component.createError()).toBe('Model is not allowed by backend policy: bogus');
    expect(component.creating()).toBe(false);
    expect(api.createTournamentPayload?.['clientRequestId']).toBe(firstRequestId);
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('uses a new request id when the details change after an unconfirmed attempt', () => {
    const api = createApiService();
    const requestIds: unknown[] = [];
    api.createTournament = ((payload: Record<string, unknown>) => {
      requestIds.push(payload['clientRequestId']);
      return throwError(() => ({ status: 0, error: null }));
    }) as any;
    const component = new TournamentSetup(api, createRouter(), createRoute());
    component.name = 'Arena';

    component.create();
    component.create();
    component.name = 'Arena (edited)';
    component.create();

    expect(requestIds[0]).toBe(requestIds[1]);
    expect(requestIds[2]).not.toBe(requestIds[0]);
    expect(component.createError()).toContain('did not confirm');
  });

  it('ignores a second click while the tournament is being created', () => {
    const api = createApiService();
    let calls = 0;
    api.createTournament = (() => {
      calls++;
      return new Subject();
    }) as any;
    const component = new TournamentSetup(api, createRouter(), createRoute());
    component.name = 'Busy Arena';

    component.create();
    component.create();

    expect(calls).toBe(1);
    expect(component.creating()).toBe(true);
  });

  it('applies onboarding presets from the query string', () => {
    const component = new TournamentSetup(
      createApiService(),
      createRouter(),
      createRoute({ preset: 'showcase' }),
    );

    component.ngOnInit();

    expect(component.name).toBe('Series Showcase');
    expect(component.matchupBestOf).toBe(3);
    expect(component.finalsBestOf).toBe(5);
    expect(component.drawPolicy).toBe('REPLAY_GAME');
    expect(component.sharedCustomInstructions).toContain('Play your strongest chess');
    expect(component.maxOutputTokens).toBe(32768);
    expect(component.limitMoveTime).toBe(false);
    expect(component.moveTimeout).toBe(120);
  });

  it('all presets switch the move time limit off and provide at least 16k output tokens', () => {
    for (const preset of ['quickstart', 'balanced', 'showcase']) {
      const api = createApiService();
      const component = new TournamentSetup(api, createRouter(), createRoute({ preset }));
      component.limitMoveTime = true;
      component.moveTimeout = 45;
      component.ngOnInit();
      expect(component.limitMoveTime).toBe(false);
      expect(component.moveTimeout).toBe(120);
      expect(component.maxOutputTokens).toBe(preset === 'showcase' ? 32768 : 16384);
      component.create();
      expect(api.createTournamentPayload?.['moveTimeoutSeconds']).toBe(0);
    }
  });

  it('warns about forfeits on time only while the limit is on and below 90 seconds', () => {
    const component = new TournamentSetup(createApiService(), createRouter(), createRoute());

    expect(component.showShortMoveTimeoutWarning()).toBe(false);

    component.limitMoveTime = true;
    component.moveTimeout = 89;
    expect(component.showShortMoveTimeoutWarning()).toBe(true);
    component.moveTimeout = 90;
    expect(component.showShortMoveTimeoutWarning()).toBe(false);

    component.moveTimeout = 30;
    component.limitMoveTime = false;
    expect(component.showShortMoveTimeoutWarning()).toBe(false);

    // A cleared number field is no reason to warn.
    component.limitMoveTime = true;
    component.moveTimeout = null as unknown as number;
    expect(component.showShortMoveTimeoutWarning()).toBe(false);
  });

  it('renders the short-limit warning under the timeout field instead of the range hint', async () => {
    TestBed.configureTestingModule({
      imports: [TournamentSetup],
      providers: [
        { provide: ApiService, useValue: createApiService() },
        { provide: Router, useValue: createRouter() },
        { provide: ActivatedRoute, useValue: createRoute() },
      ],
    });
    const fixture = TestBed.createComponent(TournamentSetup);
    fixture.detectChanges();
    await fixture.whenStable();
    const element: HTMLElement = fixture.nativeElement;
    const movesSection = () => element.querySelector('[aria-labelledby="setup-moves-heading"]');

    expect(movesSection()?.querySelector('.timeout-warning')).toBeNull();
    expect(movesSection()?.querySelector('input[max="3600"]')).toBeNull();
    const toggle = movesSection()?.querySelector<HTMLButtonElement>('mat-slide-toggle button');
    expect(toggle?.getAttribute('aria-checked')).toBe('false');
    toggle?.click();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(fixture.componentInstance.limitMoveTime).toBe(true);
    expect(movesSection()?.querySelector<HTMLInputElement>('input[max="3600"]')?.value).toBe('120');
    expect(movesSection()?.textContent).toContain('10–3600s; timeout forfeits.');

    fixture.componentInstance.moveTimeout = 60;
    fixture.changeDetectorRef.markForCheck();
    fixture.detectChanges();
    await fixture.whenStable();

    expect(movesSection()?.querySelector('.timeout-warning')?.textContent).toContain(
      'Short limits risk forfeits.',
    );
    expect(
      fixture.debugElement
        .query(By.css('[aria-label="About move timeouts"]'))
        .injector.get(MatTooltip).message,
    ).toContain('running out of time forfeits the game');
    expect(movesSection()?.textContent).not.toContain('10–3600');
  });

  it('labels the move pace Tight below 90 s, Balanced up to 180 s and Generous above', () => {
    const component = new TournamentSetup(createApiService(), createRouter(), createRoute());
    component.participants.set([
      pricedParticipant('Alpha', 'model-alpha', 1, 1),
      pricedParticipant('Beta', 'model-beta', 1, 1),
    ]);
    component.limitMoveTime = true;
    const paceAt = (seconds: number) => {
      component.moveTimeout = seconds;
      return component.confidenceSummary()?.paceLabel;
    };

    expect(paceAt(30)).toBe('Tight');
    expect(paceAt(89)).toBe('Tight');
    expect(paceAt(90)).toBe('Balanced');
    expect(paceAt(120)).toBe('Balanced');
    expect(paceAt(180)).toBe('Balanced');
    expect(paceAt(181)).toBe('Generous');
    expect(paceAt(300)).toBe('Generous');
    component.limitMoveTime = false;
    expect(component.confidenceSummary()?.paceLabel).toBe('No limit');
  });

  it('keeps the likely runtime at the typical answer time for generous limits and scales the slow end', () => {
    const component = new TournamentSetup(createApiService(), createRouter(), createRoute());
    component.matchupBestOf = 1;
    component.finalsBestOf = null;
    component.participants.set([
      pricedParticipant('Alpha', 'model-alpha', 1, 1),
      pricedParticipant('Beta', 'model-beta', 1, 1),
    ]);
    component.limitMoveTime = true;
    const runtimeAt = (seconds: number) => {
      component.moveTimeout = seconds;
      const summary = component.confidenceSummary();
      return [summary?.likelyRuntimeMinutes, summary?.maxRuntimeMinutes];
    };

    // One game of 70 answers: likely at min(30 s, a quarter of the limit), slow at half the limit.
    expect(runtimeAt(10)).toEqual([6, 10]);
    expect(runtimeAt(30)).toEqual([9, 18]);
    expect(runtimeAt(60)).toEqual([18, 35]);
    expect(runtimeAt(120)).toEqual([35, 70]);
    expect(runtimeAt(180)).toEqual([35, 105]);
    expect(runtimeAt(300)).toEqual([35, 175]);

    // A limit never makes the likely runtime longer than no limit at all.
    component.limitMoveTime = false;
    expect(component.confidenceSummary()?.likelyRuntimeMinutes).toBe(35);
  });

  it('rates the presets Safe or Moderate for a four-player field, not Expensive', () => {
    const bandOf = (preset: string) => {
      const component = new TournamentSetup(
        createApiService(),
        createRouter(),
        createRoute({ preset }),
      );
      component.ngOnInit();
      component.participants.set([
        pricedParticipant('Alpha', 'model-alpha', 1, 1),
        pricedParticipant('Beta', 'model-beta', 1, 1),
        pricedParticipant('Gamma', 'model-gamma', 1, 1),
        pricedParticipant('Delta', 'model-delta', 1, 1),
      ]);
      return component.confidenceSummary()?.riskBand;
    };

    expect(bandOf('quickstart')).toBe('Safe');
    expect(bandOf('balanced')).toBe('Safe');
    expect(bandOf('showcase')).toBe('Moderate');
  });

  it('estimates runtime without a move time limit as open-ended', () => {
    const component = new TournamentSetup(createApiService(), createRouter(), createRoute());
    component.matchupBestOf = 1;
    component.finalsBestOf = null;
    component.limitMoveTime = false;
    component.participants.set([
      pricedParticipant('Alpha', 'model-alpha', 1, 1),
      pricedParticipant('Beta', 'model-beta', 1, 1),
    ]);

    const summary = component.confidenceSummary();

    // One game of about 70 answers at the typical 30 s each.
    expect(summary?.likelyGames).toBe(1);
    expect(summary?.likelyRuntimeMinutes).toBe(35);
    expect(summary?.maxRuntimeMinutes).toBeNull();
    expect(summary?.paceLabel).toBe('No limit');
    expect(summary?.riskBand).toBe('Safe');

    component.limitMoveTime = true;
    component.moveTimeout = 120;
    const timed = component.confidenceSummary();
    expect(timed?.likelyRuntimeMinutes).toBe(35);
    expect(timed?.maxRuntimeMinutes).toBe(70);
    expect(timed?.paceLabel).toBe('Balanced');
  });

  it('builds a confidence summary with finals override and pricing data', () => {
    const component = new TournamentSetup(createApiService(), createRouter(), createRoute());
    component.moveTimeout = 45;
    component.matchupBestOf = 3;
    component.finalsBestOf = 5;
    component.drawPolicy = 'HIGHER_SEED_ADVANCES';
    component.participants.set([
      pricedParticipant('Alpha', 'model-alpha', 1.2, 4),
      pricedParticipant('Beta', 'model-beta', 0.8, 2.5),
      pricedParticipant('Gamma', 'model-gamma', 0.9, 3),
      pricedParticipant('Delta', 'model-delta', 1.1, 3.5),
    ]);

    const summary = component.confidenceSummary();

    expect(summary).not.toBeNull();
    expect(summary?.totalSeries).toBe(3);
    expect(summary?.minGames).toBe(7);
    expect(summary?.maxGames).toBe(11);
    expect(summary?.likelyGames).toBe(9);
    expect(summary?.pricingCoverage).toBe(4);
    expect(summary?.likelyCostUsd).not.toBeNull();
  });

  it('counts the final once when no finals override is set', () => {
    const component = new TournamentSetup(createApiService(), createRouter(), createRoute());
    component.matchupBestOf = 3;
    component.finalsBestOf = null;
    component.drawPolicy = 'HIGHER_SEED_ADVANCES';
    component.participants.set([
      pricedParticipant('Alpha', 'model-alpha', 1, 1),
      pricedParticipant('Beta', 'model-beta', 1, 1),
      pricedParticipant('Gamma', 'model-gamma', 1, 1),
      pricedParticipant('Delta', 'model-delta', 1, 1),
    ]);

    const summary = component.confidenceSummary();

    expect(summary?.totalSeries).toBe(3);
    expect(summary?.minGames).toBe(6);
    expect(summary?.maxGames).toBe(9);
    expect(summary?.likelyGames).toBe(7);
  });

  it('prices a move with the bounded prompt size', () => {
    const component = new TournamentSetup(createApiService(), createRouter(), createRoute());
    component.matchupBestOf = 1;
    component.finalsBestOf = null;
    component.drawPolicy = 'HIGHER_SEED_ADVANCES';
    component.participants.set([
      pricedParticipant('Alpha', 'model-alpha', 1, 10),
      pricedParticipant('Beta', 'model-beta', 1, 10),
    ]);

    const summary = component.confidenceSummary();

    expect(summary?.totalSeries).toBe(1);
    expect(summary?.minGames).toBe(1);
    expect(summary?.maxGames).toBe(1);
    // 70 moves x (1,400 prompt tokens at $1/M + 140 output tokens at $10/M) = 70 x $0.0028.
    expect(summary?.likelyCostUsd).toBeCloseTo(0.196, 6);
    expect(summary?.maxCostUsd).toBeCloseTo(0.196, 6);
  });

  it('loads the whole model catalog once, without a cap or the featured-only filter', () => {
    const api = createApiService();
    const getModels = vi.fn(() =>
      of({
        data: [catalogModel('openai/gpt-6', 'GPT-6'), catalogModel('acme/tiny', 'Tiny')],
        totalMatched: 2,
        featuredCount: 1,
        error: null,
        featuredProviders: ['openai', 'anthropic'],
      }),
    );
    api.getModels = getModels as any;
    const component = new TournamentSetup(api, createRouter(), createRoute());

    component.ngOnInit();

    expect(getModels).toHaveBeenCalledTimes(1);
    expect(getModels).toHaveBeenCalledWith({ featuredOnly: false, limit: 0 });
    expect(component.models().map((model) => model.id)).toEqual(['openai/gpt-6', 'acme/tiny']);
    expect(component.featuredProviders()).toEqual(['openai', 'anthropic']);
    expect(component.modelsLoading()).toBe(false);
    expect(component.modelsError()).toBeNull();
  });

  it('reports a catalog that failed to load and can retry', () => {
    const api = createApiService();
    api.getModels = (() => throwError(() => ({ status: 503 }))) as any;
    const component = new TournamentSetup(api, createRouter(), createRoute());

    component.ngOnInit();

    expect(component.models()).toEqual([]);
    expect(component.modelsError()).toBe('Failed to fetch models from OpenRouter.');
    expect(component.modelsLoading()).toBe(false);

    api.getModels = (() =>
      of({ data: [catalogModel('openai/gpt-6', 'GPT-6')], error: null })) as any;
    component.loadModels();
    expect(component.modelsError()).toBeNull();
    expect(component.models()).toHaveLength(1);
  });

  it('shows the error the backend reports for an unavailable catalog', () => {
    const api = createApiService();
    api.getModels = (() =>
      of({
        data: [],
        totalMatched: 0,
        featuredCount: 0,
        error: 'OPENROUTER_API_KEY is not configured',
      })) as any;
    const component = new TournamentSetup(api, createRouter(), createRoute());

    component.ngOnInit();

    expect(component.modelsError()).toBe('OPENROUTER_API_KEY is not configured');
  });

  it('fills an empty display name from the selected model and keeps a typed one', () => {
    const api = createApiService();
    api.getModels = (() =>
      of({
        data: [catalogModel('openai/gpt-6', 'OpenAI: GPT-6'), catalogModel('acme/tiny', 'Tiny')],
      })) as any;
    const component = new TournamentSetup(api, createRouter(), createRoute());
    component.ngOnInit();

    component.onModelChanged('openai/gpt-6');
    expect(component.newModelId).toBe('openai/gpt-6');
    expect(component.newPlayerName).toBe('OpenAI: GPT-6');
    expect(component.selectedModel()?.id).toBe('openai/gpt-6');

    component.onModelChanged('acme/tiny');
    expect(component.newPlayerName).toBe('OpenAI: GPT-6');

    component.onModelChanged('');
    expect(component.newModelId).toBe('');
    expect(component.selectedModel()).toBeNull();
    expect(component.canAddParticipant()).toBe(false);
  });

  it('counts a free model as priced in the estimate', () => {
    const api = createApiService();
    api.getModels = (() =>
      of({
        data: [
          catalogModel('acme/free', 'Free One', 0, 0),
          catalogModel('acme/paid', 'Paid One', 1, 10),
        ],
      })) as any;
    const component = new TournamentSetup(api, createRouter(), createRoute());
    component.ngOnInit();

    for (const id of ['acme/free', 'acme/paid']) {
      component.onModelChanged(id);
      component.addParticipant();
    }

    expect(component.participants()[0].promptPricePerMillion).toBe(0);
    expect(component.newModelId).toBe('');
    expect(component.confidenceSummary()?.pricingCoverage).toBe(2);
  });

  it('rejects a second human and allows one again after removing the first', () => {
    const component = new TournamentSetup(createApiService(), createRouter(), createRoute());
    component.newPlayerName = 'Human Challenger';
    component.newControlType = 'HUMAN';

    component.addParticipant();

    expect(component.participants()[0].controlType).toBe('HUMAN');
    expect(component.participants()[0].modelId).toBeNull();

    component.newPlayerName = 'Second Human';
    component.newControlType = 'HUMAN';
    expect(component.canAddParticipant()).toBe(false);
    component.addParticipant();
    expect(component.participants()).toHaveLength(1);
    expect(component.participants()[0].playerName).toBe('Human Challenger');

    component.removeParticipant(0);
    expect(component.canAddParticipant()).toBe(true);
    component.addParticipant();
    expect(component.participants()).toHaveLength(1);
    expect(component.participants()[0]).toMatchObject({
      playerName: 'Second Human',
      controlType: 'HUMAN',
      modelId: null,
    });
  });

  it('derives launch readiness from the name, the participant count and the submitting state', () => {
    const api = createApiService();
    api.createTournament = (() => new Subject()) as any;
    const component = new TournamentSetup(api, createRouter(), createRoute());

    expect(component.readiness()).toEqual({
      named: false,
      count: 0,
      participantsOk: false,
      creating: false,
      canCreate: false,
    });

    component.participants.set([pricedParticipant('Alpha', 'model-alpha', 1, 2)]);
    component.name = 'Ready Arena';
    expect(component.readiness()).toMatchObject({
      named: true,
      count: 1,
      participantsOk: false,
      canCreate: false,
    });

    component.participants.update((list) => [
      ...list,
      pricedParticipant('Beta', 'model-beta', 1, 2),
    ]);
    expect(component.readiness()).toMatchObject({
      count: 2,
      participantsOk: true,
      creating: false,
      canCreate: true,
    });

    component.name = '';
    expect(component.readiness()).toMatchObject({ named: false, canCreate: false });

    component.name = 'Ready Arena';
    component.create();
    expect(component.readiness()).toMatchObject({ creating: true, canCreate: false });
  });

  it('reveals and focuses instructions, submits a collapsed draft and resets after adding', async () => {
    const fixture = await renderSetup();
    const component = fixture.componentInstance;
    const element: HTMLElement = fixture.nativeElement;
    const toggle = element.querySelector<HTMLButtonElement>('.instructions-toggle')!;
    const refresh = async () => {
      fixture.changeDetectorRef.markForCheck();
      fixture.detectChanges();
      await fixture.whenStable();
    };
    expect(element.querySelector('.participants-card h2')?.textContent).toBe('Add participant');
    expect(toggle.textContent?.trim()).toBe('+ Custom instructions');
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(element.querySelector('#participant-instructions')).toBeNull();
    toggle.click();
    await refresh();
    const textarea = element.querySelector<HTMLTextAreaElement>('#participant-instructions')!;
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(document.activeElement).toBe(textarea);
    expect(textarea.rows).toBe(3);
    expect(textarea.closest('mat-form-field')?.textContent).toContain(
      'Overrides shared instructions.',
    );
    textarea.value = 'Play actively.';
    textarea.dispatchEvent(new Event('input'));
    await refresh();
    toggle.click();
    await refresh();
    expect(element.querySelector('#participant-instructions')).toBeNull();
    expect(component.newCustomInstructions).toBe('Play actively.');
    toggle.click();
    await refresh();
    expect(element.querySelector<HTMLTextAreaElement>('#participant-instructions')?.value).toBe(
      'Play actively.',
    );
    component.addParticipant();
    await refresh();
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    component.newPlayerName = 'Alpha';
    component.newModelId = 'example/alpha';
    await refresh();
    toggle.click();
    await refresh();
    expect(element.querySelector('#participant-instructions')).toBeNull();
    element.querySelector<HTMLButtonElement>('.add-button')!.click();
    await refresh();
    expect(component.participants()[0].customInstructions).toBe('Play actively.');
    expect(component.newCustomInstructions).toBe('');
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(element.querySelector('#participant-instructions')).toBeNull();
    toggle.click();
    await refresh();
    component.newPlayerName = 'Beta';
    component.newModelId = 'example/beta';
    await refresh();
    element.querySelector<HTMLButtonElement>('.add-button')!.click();
    await refresh();
    expect(component.participants()[1].customInstructions).toBeNull();
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    component.name = 'Instruction Arena';
    await refresh();
    element.querySelector<HTMLButtonElement>('.lineup-card .create-button')!.click();
    await refresh();
    const api = TestBed.inject(ApiService) as FakeTournamentSetupApi;
    expect(api.createTournamentPayload?.['participants']).toEqual([
      expect.objectContaining({ playerName: 'Alpha', customInstructions: 'Play actively.' }),
      expect.objectContaining({ playerName: 'Beta', customInstructions: null }),
    ]);
  });

  it('starts advanced prompt collapsed and marks shared instructions even while closed', async () => {
    const fixture = await renderSetup();
    const component = fixture.componentInstance;
    const element: HTMLElement = fixture.nativeElement;
    const details = element.querySelector<HTMLDetailsElement>('.advanced-prompt')!;
    const summary = details.querySelector<HTMLElement>('summary')!;
    expect(details.open).toBe(false);
    expect(summary.textContent).toContain('Prompt rules and shared instructions');
    expect(summary.querySelector('.custom-badge')).toBeNull();
    expect(element.querySelector('.settings-card mat-card-content')?.lastElementChild).toBe(
      details,
    );
    summary.click();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(details.open).toBe(true);
    expect(details.querySelector('#setup-rules-heading')?.textContent).toBe('System Rules');
    expect(details.querySelector('#setup-shared-heading')?.textContent).toBe(
      'Shared Custom Instructions',
    );
    const fullRules = details.querySelector<HTMLDetailsElement>('.rules-details')!;
    expect(fullRules.open).toBe(false);
    fullRules.querySelector<HTMLElement>('summary')!.click();
    expect(fullRules.open).toBe(true);
    const shared = details.querySelector<HTMLTextAreaElement>('textarea:not([readonly])')!;
    expect(shared.rows).toBe(3);
    shared.value = 'Prefer active play.';
    shared.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(component.sharedCustomInstructions).toBe('Prefer active play.');
    summary.click();
    expect(details.open).toBe(false);
    expect(summary.querySelector('.custom-badge')?.textContent?.trim()).toBe('custom');
    component.sharedCustomInstructions = '   ';
    fixture.changeDetectorRef.markForCheck();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(summary.querySelector('.custom-badge')).toBeNull();
  });

  it('keeps the seeded roster, labelled removal and launch action in the aside', async () => {
    const fixture = await renderSetup();
    const component = fixture.componentInstance;
    const element: HTMLElement = fixture.nativeElement;
    const aside = element.querySelector('aside')!;
    const refresh = async () => {
      fixture.changeDetectorRef.markForCheck();
      fixture.detectChanges();
      await fixture.whenStable();
    };
    expect(element.querySelector('.setup-main > mat-card')?.classList).toContain(
      'participants-card',
    );
    expect(element.querySelector('.participants-card .participant-list')).toBeNull();
    expect(aside.querySelector('.participant-empty')?.textContent).toContain('No participants');
    expect(aside.getAttribute('aria-label')).toBe('Tournament preview and launch');
    expect(aside.getAttribute('tabindex')).toBe('0');
    expect(Array.from(aside.children).map((card) => card.querySelector('h2')?.textContent)).toEqual(
      ['Tournament lineup', 'Tournament Estimate'],
    );
    expect(aside.querySelector('mat-card-content[tabindex]')).toBeNull();
    const nameInput = aside.querySelector<HTMLInputElement>('.lineup-card input')!;
    expect(nameInput.placeholder).toBe('e.g. AI Chess Championship 2026');
    expect(nameInput.closest('mat-form-field')?.querySelector('mat-label')?.textContent).toBe(
      'Tournament Name',
    );
    expect(nameInput.closest('mat-card-content')?.firstElementChild).toBe(
      nameInput.closest('mat-form-field'),
    );
    expect(
      element.querySelectorAll('input[placeholder="e.g. AI Chess Championship 2026"]'),
    ).toHaveLength(1);
    expect(
      element.querySelector('.settings-card input[placeholder="e.g. AI Chess Championship 2026"]'),
    ).toBeNull();
    expect(aside.querySelector('.lineup-card .create-button')).not.toBeNull();
    expect(aside.querySelector('.confidence-panel .create-button')).toBeNull();
    const create = aside.querySelector<HTMLButtonElement>('.create-button')!;
    expect(create.disabled).toBe(true);
    nameInput.value = 'Test bracket';
    nameInput.dispatchEvent(new Event('input'));
    await refresh();
    expect(component.name).toBe('Test bracket');
    expect(create.disabled).toBe(true);
    component.participants.set([
      pricedParticipant('Alpha', 'example/alpha', 1, 2),
      { ...pricedParticipant('Beta', 'example/beta', 1, 2), customInstructions: 'Play actively.' },
    ]);
    await refresh();
    expect(create.disabled).toBe(false);
    expect(aside.querySelector('.launch-checklist')?.textContent).toContain('Tournament named');
    expect(aside.querySelector('.participants-list-head')?.textContent).toContain(
      '2 of at least 2',
    );
    expect(
      Array.from(aside.querySelectorAll('.participant-seed')).map((seed) =>
        seed.textContent?.trim(),
      ),
    ).toEqual(['1', '2']);
    expect(aside.querySelectorAll('.participant-row app-provider-logo')).toHaveLength(2);
    expect(aside.querySelector('.participant-row')?.textContent).toContain('example/alpha');
    const rows = aside.querySelectorAll('.participant-row');
    expect(rows[0].querySelector('.participant-name')?.getAttribute('title')).toBe('Alpha');
    expect(rows[0].querySelector('.participant-meta')?.getAttribute('title')).toBe('example/alpha');
    expect(rows[0].querySelector('.participant-text')?.getAttribute('title')).toBe(
      'Uses default instructions only',
    );
    expect(rows[0].querySelector('.provider-logo')?.getAttribute('title')).toBe('Example');
    expect(rows[0].querySelector('.participant-meta')?.textContent).not.toContain('·');
    expect(rows[0].querySelector('.participant-instructions')).toBeNull();
    expect(rows[0].textContent).not.toContain('Uses default instructions only');
    expect(rows[1].querySelector('.participant-instructions')?.textContent?.trim()).toBe(
      'Custom instructions',
    );
    component.sharedCustomInstructions = 'Play carefully.';
    await refresh();
    expect(rows[0].querySelector('.participant-instructions')?.textContent?.trim()).toBe(
      'Shared instructions',
    );
    expect(rows[1].querySelector('.participant-instructions')?.textContent?.trim()).toBe(
      'Custom instructions',
    );
    const remove = Array.from(aside.querySelectorAll<HTMLButtonElement>('.remove-button'));
    expect(remove.map((button) => button.getAttribute('aria-label'))).toEqual([
      'Remove Alpha',
      'Remove Beta',
    ]);
    component.creating.set(true);
    await refresh();
    expect(create.disabled).toBe(true);
    component.creating.set(false);
    await refresh();
    remove[0].click();
    await refresh();
    expect(component.participants().map((p) => p.playerName)).toEqual(['Beta']);
    expect(create.disabled).toBe(true);
    expect(aside.querySelector('.participant-seed')?.textContent?.trim()).toBe('1');
    expect(element.querySelector('.settings-card #setup-opening-heading')).not.toBeNull();
    const groups = Array.from(element.querySelectorAll('.settings-card .field-group')).map(
      (group) => group.getAttribute('aria-labelledby'),
    );
    expect(groups.indexOf('setup-opening-heading')).toBe(
      groups.indexOf('setup-matches-heading') + 1,
    );
  });

  it('shows labelled effort overrides in the roster and omits effort for tournament defaults', async () => {
    const fixture = await renderSetup();
    const component = fixture.componentInstance;
    component.sharedCustomInstructions = 'Play carefully.';
    component.participants.set([
      { ...pricedParticipant('Alpha', 'example/alpha', 1, 2), reasoningEffort: 'high' },
      { playerName: 'Human', controlType: 'HUMAN' },
      pricedParticipant('Beta', 'example/beta', 1, 2),
    ]);
    fixture.changeDetectorRef.markForCheck();
    fixture.detectChanges();
    await fixture.whenStable();
    const rows: NodeListOf<HTMLElement> =
      fixture.nativeElement.querySelectorAll('.participant-row');
    expect(rows[0].querySelector('.participant-text')?.getAttribute('title')).toBe(
      'Uses shared tournament instructions · Reasoning effort: High',
    );
    expect(
      rows[0].querySelector('.participant-meta')?.textContent?.replace(/\s+/g, ' ').trim(),
    ).toBe('example/alpha · effort: High');
    expect(rows[1].querySelector('.participant-meta')?.textContent?.trim()).toBe(
      'Human-controlled',
    );
    expect(rows[1].querySelector('.participant-instructions')).toBeNull();
    expect(rows[1].querySelector('.participant-effort-meta')).toBeNull();
    expect(rows[2].querySelector('.participant-meta')?.textContent?.trim()).toBe('example/beta');
    expect(rows[2].querySelector('.participant-effort-meta')).toBeNull();
  });

  it('renders heuristic band labels and approximate spend and runtime', async () => {
    const fixture = await renderSetup();
    const component = fixture.componentInstance;
    const element: HTMLElement = fixture.nativeElement;
    component.participants.set([
      pricedParticipant('Alpha', 'example/alpha', 1, 2),
      pricedParticipant('Beta', 'example/beta', 1, 2),
    ]);
    const refresh = async () => {
      fixture.changeDetectorRef.markForCheck();
      fixture.detectChanges();
      await fixture.whenStable();
    };
    const metric = (label: string) =>
      Array.from(element.querySelectorAll('.confidence-metric')).find(
        (m) => m.querySelector('.metric-label')?.textContent === label,
      )!;
    await refresh();
    expect(element.querySelector('.confidence-panel h2')?.textContent).toBe('Tournament Estimate');
    expect(element.querySelector('.confidence-panel mat-card-subtitle')?.textContent).toBe(
      'Rough heuristic, not a quote.',
    );
    expect(element.querySelector('.confidence-band')?.textContent?.trim()).toBe('Low risk');
    expect(element.querySelector('.confidence-band')?.classList).toContain('low-risk');
    expect(metric('Estimated Spend').querySelector('strong')?.textContent).toContain('about $');
    expect(metric('Estimated Spend').textContent).toContain('can be higher with reasoning models');
    expect(metric('Estimated Runtime').querySelector('strong')?.textContent).toContain('about ');
    expect(metric('Estimated Runtime').textContent).toContain('open-ended');
    component.limitMoveTime = true;
    component.moveTimeout = 120;
    component.matchupBestOf = 3;
    await refresh();
    expect(metric('Estimated Spend').textContent).toContain('up to about $');
    expect(metric('Estimated Runtime').textContent).toContain('up to about ');
    component.drawPolicy = 'REPLAY_GAME';
    await refresh();
    expect(metric('Estimated Spend').textContent).toContain('at least about $');
    expect(metric('Estimated Runtime').textContent).toContain('at least about ');
    component.participants.set([
      pricedParticipant('Alpha', 'example/alpha', 0, 0),
      pricedParticipant('Beta', 'example/beta', 0, 0),
    ]);
    component.matchupBestOf = 1;
    await refresh();
    expect(metric('Estimated Spend').textContent).toContain('at least about $0.00');
    for (const [riskBand, label, className] of [
      ['Moderate', 'Moderate risk', 'moderate-risk'],
      ['Expensive', 'High cost', 'high-cost'],
    ] as const) {
      const summary = component.confidenceSummary()!;
      component.confidenceSummary = () => ({ ...summary, riskBand });
      await refresh();
      expect(element.querySelector('.confidence-band')?.textContent?.trim()).toBe(label);
      expect(element.querySelector('.confidence-band')?.classList).toContain(className);
    }
  });

  it('sends chat mode Optional by default and the chosen mode otherwise, without the old flag', () => {
    const api = createApiService();
    const component = new TournamentSetup(api, createRouter(), createRoute());
    component.name = 'Chat Arena';

    expect(component.chatMode).toBe('OPTIONAL');
    component.create();
    expect(api.createTournamentPayload?.['chatMode']).toBe('OPTIONAL');
    expect(api.createTournamentPayload).not.toHaveProperty('trashTalkEnabled');

    for (const mode of ['OFF', 'MANDATORY'] as const) {
      component.chatMode = mode;
      component.create();
      expect(api.createTournamentPayload?.['chatMode']).toBe(mode);
    }
  });

  it('offers Off, Optional and Mandatory with a one-line hint for the selected mode', () => {
    const component = new TournamentSetup(createApiService(), createRouter(), createRoute());

    expect(component.chatModeOptions.map((option) => option.label)).toEqual([
      'Off',
      'Optional',
      'Mandatory',
    ]);
    expect(component.chatModeHint()).toContain('may send a message');
    component.chatMode = 'OFF';
    expect(component.chatModeHint()).toContain('stay silent');
    component.chatMode = 'MANDATORY';
    expect(component.chatModeHint()).toContain('never penalized');
  });

  it('presets reset the chat mode to Optional', () => {
    const component = new TournamentSetup(
      createApiService(),
      createRouter(),
      createRoute({ preset: 'balanced' }),
    );
    component.chatMode = 'OFF';

    component.ngOnInit();

    expect(component.chatMode).toBe('OPTIONAL');
  });

  it('shows the system rules and version of the selected chat mode', () => {
    const api = createApiService();
    api.getPromptTemplate = () =>
      of({
        template: 'Rules optional',
        version: 'v2',
        hash: 'h',
        chatModes: {
          OFF: { template: 'Rules off', version: 'v2-silent', hash: 'h0' },
          OPTIONAL: { template: 'Rules optional', version: 'v2', hash: 'h1' },
          MANDATORY: { template: 'Rules mandatory', version: 'v2-chat-required', hash: 'h2' },
        },
      });
    const component = new TournamentSetup(api, createRouter(), createRoute());
    component.ngOnInit();

    expect(component.systemRulesTemplate).toBe('Rules optional');
    expect(component.systemRulesVersion()).toBe('v2');
    component.chatMode = 'MANDATORY';
    expect(component.systemRulesTemplate).toBe('Rules mandatory');
    expect(component.systemRulesVersion()).toBe('v2-chat-required');
  });

  it('falls back to the default rules from a backend without chat modes', () => {
    const component = new TournamentSetup(createApiService(), createRouter(), createRoute());
    component.ngOnInit();
    component.chatMode = 'OFF';

    expect(component.systemRulesTemplate).toBe('Rules');
    expect(component.systemRulesVersion()).toBe('v2');
  });

  it('renders the chat-mode control and selects a mode on click', async () => {
    TestBed.configureTestingModule({
      imports: [TournamentSetup],
      providers: [
        { provide: ApiService, useValue: createApiService() },
        { provide: Router, useValue: createRouter() },
        { provide: ActivatedRoute, useValue: createRoute() },
      ],
    });
    const fixture = TestBed.createComponent(TournamentSetup);
    fixture.detectChanges();
    await fixture.whenStable();
    const element: HTMLElement = fixture.nativeElement;

    const buttons = Array.from(
      element.querySelectorAll<HTMLButtonElement>('.chat-mode-toggle button'),
    );
    expect(buttons.map((button) => button.textContent?.trim())).toEqual([
      'Off',
      'Optional',
      'Mandatory',
    ]);
    expect(element.textContent).not.toContain('Trash Talk Enabled');
    expect(element.querySelector('#chat-mode-hint')?.textContent).toContain('may send a message');

    buttons[2].click();
    fixture.detectChanges();
    await fixture.whenStable();

    expect(fixture.componentInstance.chatMode).toBe('MANDATORY');
    expect(element.querySelector('#chat-mode-hint')?.textContent).toContain('never penalized');
  });

  it('sends adjudication off by default, on when chosen, and resets it in presets', () => {
    const api = createApiService();
    const component = new TournamentSetup(api, createRouter(), createRoute());
    component.name = 'Adjudication Arena';
    component.create();
    expect(api.createTournamentPayload?.['adjudicationEnabled']).toBe(false);
    component.adjudicationEnabled = true;
    component.create();
    expect(api.createTournamentPayload?.['adjudicationEnabled']).toBe(true);
    const preset = new TournamentSetup(
      createApiService(),
      createRouter(),
      createRoute({ preset: 'showcase' }),
    );
    preset.adjudicationEnabled = true;
    preset.ngOnInit();
    expect(preset.adjudicationEnabled).toBe(false);
  });

  it('renders an adjudication toggle with the rule and model-only hint', async () => {
    TestBed.configureTestingModule({
      imports: [TournamentSetup],
      providers: [
        { provide: ApiService, useValue: createApiService() },
        { provide: Router, useValue: createRouter() },
        { provide: ActivatedRoute, useValue: createRoute() },
      ],
    });
    const fixture = TestBed.createComponent(TournamentSetup);
    fixture.detectChanges();
    await fixture.whenStable();
    const element: HTMLElement = fixture.nativeElement;
    expect(element.querySelector('#adjudication-hint')?.textContent).toContain('Model games only');
    const tooltip = fixture.debugElement
      .query(By.css('[aria-label="About adjudication"]'))
      .injector.get(MatTooltip).message;
    expect(tooltip).toContain('10 plies');
    expect(tooltip).toContain('+10 pawns');
    element.querySelector<HTMLButtonElement>('.adjudication-toggle button')?.click();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(fixture.componentInstance.adjudicationEnabled).toBe(true);
  });

  it('sends resignation off by default and on when allowed', () => {
    const api = createApiService();
    const component = new TournamentSetup(api, createRouter(), createRoute());
    component.name = 'Resign Arena';

    expect(component.resignationAllowed).toBe(false);
    component.create();
    expect(api.createTournamentPayload?.['resignationAllowed']).toBe(false);

    component.resignationAllowed = true;
    component.create();
    expect(api.createTournamentPayload?.['resignationAllowed']).toBe(true);
  });

  it('explains the resignation setting in one line', () => {
    const component = new TournamentSetup(createApiService(), createRouter(), createRoute());

    expect(component.resignationHint()).toContain('played to the end');
    component.resignationAllowed = true;
    expect(component.resignationHint()).toContain('10th move');
    expect(component.resignationHint()).toContain('Both models');
  });

  it('presets keep resignation off', () => {
    const component = new TournamentSetup(
      createApiService(),
      createRouter(),
      createRoute({ preset: 'showcase' }),
    );
    component.resignationAllowed = true;

    component.ngOnInit();

    expect(component.resignationAllowed).toBe(false);
  });

  it('shows the rules with resignation for the selected chat mode, and without for older backends', () => {
    const api = createApiService();
    api.getPromptTemplate = () =>
      of({
        template: 'Rules optional',
        version: 'v2',
        hash: 'h',
        chatModes: {
          OFF: { template: 'Rules off', version: 'v2-silent', hash: 'h0' },
          OPTIONAL: { template: 'Rules optional', version: 'v2', hash: 'h1' },
          MANDATORY: { template: 'Rules mandatory', version: 'v2-chat-required', hash: 'h2' },
        },
        resignationChatModes: {
          OFF: { template: 'Rules off + 9', version: 'v2-silent-resign', hash: 'h3' },
          OPTIONAL: { template: 'Rules optional + 9', version: 'v2-resign', hash: 'h4' },
          MANDATORY: {
            template: 'Rules mandatory + 9',
            version: 'v2-chat-required-resign',
            hash: 'h5',
          },
        },
      });
    const component = new TournamentSetup(api, createRouter(), createRoute());
    component.ngOnInit();

    component.resignationAllowed = true;
    expect(component.systemRulesTemplate).toBe('Rules optional + 9');
    expect(component.systemRulesVersion()).toBe('v2-resign');
    component.chatMode = 'MANDATORY';
    expect(component.systemRulesTemplate).toBe('Rules mandatory + 9');
    expect(component.systemRulesVersion()).toBe('v2-chat-required-resign');
    component.resignationAllowed = false;
    expect(component.systemRulesVersion()).toBe('v2-chat-required');

    const older = new TournamentSetup(createApiService(), createRouter(), createRoute());
    older.ngOnInit();
    older.resignationAllowed = true;
    expect(older.systemRulesTemplate).toBe('Rules');
  });

  it('renders the resignation switch with its hint next to the chat control', async () => {
    TestBed.configureTestingModule({
      imports: [TournamentSetup],
      providers: [
        { provide: ApiService, useValue: createApiService() },
        { provide: Router, useValue: createRouter() },
        { provide: ActivatedRoute, useValue: createRoute() },
      ],
    });
    const fixture = TestBed.createComponent(TournamentSetup);
    fixture.detectChanges();
    await fixture.whenStable();
    const element: HTMLElement = fixture.nativeElement;

    const section = element.querySelector('[aria-labelledby="setup-chat-heading"]');
    expect(section?.querySelector('.resignation-toggle')?.textContent).toContain(
      'Allow resignation',
    );
    expect(element.querySelector('#resignation-hint')?.textContent).toContain('played to the end');

    element.querySelector<HTMLButtonElement>('.resignation-toggle button')?.click();
    fixture.detectChanges();
    await fixture.whenStable();

    expect(fixture.componentInstance.resignationAllowed).toBe(true);
    expect(element.querySelector('#resignation-hint')?.textContent).toContain('10th move');
  });

  it('sends no reasoning effort for the model default and the chosen level otherwise', () => {
    const api = createApiService();
    const component = new TournamentSetup(api, createRouter(), createRoute());
    component.name = 'Effort Arena';

    expect(component.reasoningEffort).toBeNull();
    component.create();
    expect(api.createTournamentPayload).not.toHaveProperty('reasoningEffort');

    component.reasoningEffort = 'low';
    component.create();
    expect(api.createTournamentPayload?.['reasoningEffort']).toBe('low');
  });

  it('offers Model default and every level with a one-line hint', () => {
    const component = new TournamentSetup(createApiService(), createRouter(), createRoute());

    expect(component.reasoningEffortOptions.map((option) => option.value)).toEqual([
      'none',
      'minimal',
      'low',
      'medium',
      'high',
      'xhigh',
      'max',
    ]);
    expect(component.reasoningEffortHint).toBe(
      'Lower effort makes heavy thinkers faster and cheaper; some models think without end at their default',
    );
  });

  it('offers a participant only the levels its model supports and sends the override', () => {
    const api = createApiService();
    const ember: OpenRouterModelOption = {
      ...catalogModel('meta/muse-ember', 'Meta: Muse Ember'),
      reasoningSupported: true,
      reasoningMandatory: true,
      reasoningEfforts: ['max', 'xhigh', 'high', 'medium', 'low', 'minimal'],
      defaultEffort: 'max',
    };
    const plain = catalogModel('acme/plain', 'Plain');
    api.getModels = (() => of({ data: [ember, plain] })) as any;
    const component = new TournamentSetup(api, createRouter(), createRoute());
    component.ngOnInit();
    component.name = 'Override Arena';
    component.reasoningEffort = 'medium';

    component.onModelChanged('meta/muse-ember');
    expect(component.selectedModelEfforts()).toEqual([
      'minimal',
      'low',
      'medium',
      'high',
      'xhigh',
      'max',
    ]);
    expect(component.tournamentEffortOptionLabel()).toBe('Tournament setting (Medium)');
    component.newReasoningEffort = 'low';
    component.addParticipant();

    // A model without effort levels gets no override, and a model change resets the choice.
    component.newReasoningEffort = 'high';
    component.onModelChanged('acme/plain');
    expect(component.newReasoningEffort).toBeNull();
    expect(component.selectedModelEfforts()).toEqual([]);
    component.addParticipant();

    component.create();
    const participants = api.createTournamentPayload?.['participants'] as Array<
      Record<string, unknown>
    >;
    expect(participants.map((p) => p['reasoningEffort'])).toEqual(['low', null]);
    expect(api.createTournamentPayload?.['reasoningEffort']).toBe('medium');
  });

  it('offers every normalized level for an all-level model in the setup override', () => {
    const api = createApiService();
    const levels = ['none', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max'];
    api.getModels = (() =>
      of({
        data: [
          {
            ...catalogModel('example/all-levels', 'All levels'),
            reasoningSupported: true,
            reasoningEfforts: levels,
          },
        ],
      })) as any;
    const component = new TournamentSetup(api, createRouter(), createRoute());
    component.ngOnInit();
    component.name = 'All levels arena';
    component.onModelChanged('example/all-levels');
    expect(component.selectedModelEfforts()).toEqual(levels);
    component.newReasoningEffort = 'minimal';
    component.addParticipant();
    component.create();
    const participants = api.createTournamentPayload?.['participants'] as Array<
      Record<string, unknown>
    >;
    expect(participants[0]['reasoningEffort']).toBe('minimal');
  });

  it('presets reset the reasoning effort to the model default', () => {
    const component = new TournamentSetup(
      createApiService(),
      createRouter(),
      createRoute({ preset: 'balanced' }),
    );
    component.reasoningEffort = 'max';

    component.ngOnInit();

    expect(component.reasoningEffort).toBeNull();
  });

  it('renders the tournament select, the participant select and n/a for a model without levels', async () => {
    const api = createApiService();
    api.getModels = (() =>
      of({
        data: [
          {
            ...catalogModel('moonshotai/kimi-k3.1-pro', 'Kimi K3.1 Pro'),
            reasoningSupported: true,
            reasoningEfforts: ['max', 'high', 'low', 'none'],
            defaultEffort: 'max',
          },
          catalogModel('acme/plain', 'Plain'),
        ],
      })) as any;
    TestBed.configureTestingModule({
      imports: [TournamentSetup],
      providers: [
        { provide: ApiService, useValue: api },
        { provide: Router, useValue: createRouter() },
        { provide: ActivatedRoute, useValue: createRoute() },
      ],
    });
    const fixture = TestBed.createComponent(TournamentSetup);
    const settle = async () => {
      // Plain fields changed from the test, as a user event would, mark the view for checking.
      fixture.componentRef.changeDetectorRef.markForCheck();
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();
    };
    await settle();
    const element: HTMLElement = fixture.nativeElement;

    expect(element.querySelector('.reasoning-effort-field mat-label')?.textContent?.trim()).toBe(
      'Reasoning effort',
    );
    expect(
      element.querySelector('.reasoning-effort-field .mat-mdc-select-value')?.textContent?.trim(),
    ).toBe('Model default');
    expect(element.querySelector('.reasoning-effort-hint')?.textContent).toContain(
      'Lower is faster and cheaper.',
    );
    expect(
      fixture.debugElement
        .query(By.css('[aria-label="About reasoning effort"]'))
        .injector.get(MatTooltip).message,
    ).toContain('some models think without end at their default');
    expect(element.querySelector('.participant-effort')).toBeNull();

    fixture.componentInstance.onModelChanged('moonshotai/kimi-k3.1-pro');
    await settle();
    expect(element.querySelector('.participant-effort-field mat-label')?.textContent?.trim()).toBe(
      'Reasoning effort for this participant',
    );
    expect(
      element.querySelector('.participant-effort-field .mat-mdc-select-value')?.textContent?.trim(),
    ).toBe('Tournament setting (model default)');
    expect(element.querySelector('.model-details')?.textContent).toContain('Default effort');

    fixture.componentInstance.onModelChanged('acme/plain');
    await settle();
    expect(element.querySelector('.participant-effort-field')).toBeNull();
    expect(
      element.querySelector('.participant-effort-na')?.textContent?.replace(/\s+/g, ' ').trim(),
    ).toBe('Reasoning effort: n/a help_outline');
  });
});

type FakeTournamentSetupApi = ApiService & {
  addedParticipants: Array<Record<string, unknown>>;
  createTournamentPayload: Record<string, unknown> | null;
};

function createApiService(): FakeTournamentSetupApi {
  const api = {
    addedParticipants: [] as Array<Record<string, unknown>>,
    createTournamentPayload: null as Record<string, unknown> | null,
    getOpenings: () =>
      of([
        {
          id: 'ruy-lopez',
          eco: 'C60',
          name: 'Ruy Lopez',
          moves: ['e4', 'e5', 'Nf3', 'Nc6', 'Bb5'],
        },
      ]),
    getPromptTemplate: () => of({ template: 'Rules', version: 'v2', hash: 'hash' }),
    checkOpenRouterStatus: () => of({ valid: true }),
    getModels: () => of({ data: [], totalMatched: 0, featuredCount: 0, error: null }),
    createTournament(payload: Record<string, unknown>) {
      api.createTournamentPayload = payload;
      return of({ id: 't-1' });
    },
    addParticipant(_tournamentId: string, payload: Record<string, unknown>) {
      api.addedParticipants.push(payload);
      return of({});
    },
  };

  return api as unknown as FakeTournamentSetupApi;
}

function createRouter() {
  return {
    navigate: () => Promise.resolve(true),
  } as any;
}

function createRoute(queryParams: Record<string, string> = {}) {
  return {
    queryParamMap: of(convertToParamMap(queryParams)),
  } as any;
}

function catalogModel(
  id: string,
  name: string,
  promptPricePerMillion: number | null = null,
  completionPricePerMillion: number | null = null,
): OpenRouterModelOption {
  return {
    id,
    name,
    provider: id.split('/')[0],
    contextLength: null,
    promptPricePerMillion,
    completionPricePerMillion,
    featured: false,
  };
}

function pricedParticipant(
  playerName: string,
  modelId: string,
  promptPricePerMillion: number,
  completionPricePerMillion: number,
) {
  return {
    playerName,
    controlType: 'AI' as const,
    modelId,
    seed: 0,
    customInstructions: null,
    promptPricePerMillion,
    completionPricePerMillion,
  };
}

async function renderSetup() {
  TestBed.configureTestingModule({
    imports: [TournamentSetup],
    providers: [
      { provide: ApiService, useValue: createApiService() },
      { provide: Router, useValue: createRouter() },
      { provide: ActivatedRoute, useValue: createRoute() },
    ],
  });
  const fixture = TestBed.createComponent(TournamentSetup);
  fixture.detectChanges();
  await fixture.whenStable();
  return fixture;
}
