import { OpenRouterAccess } from '../../services/openrouter-access';
import { By } from '@angular/platform-browser';
import { TestbedHarnessEnvironment } from '@angular/cdk/testing/testbed';
import { MatSelectHarness } from '@angular/material/select/testing';
import { MatTooltip } from '@angular/material/tooltip';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';
import { TournamentSetup } from './tournament-setup';
import { ApiService } from '../../services/api.service';
import { OpenRouterKeyStatus } from '../../services/openrouter-client';
import { OpenRouterModelOption, OpenRouterModelsResponse } from '../../models/openrouter.model';
import { Tournament, DEFAULT_MOVE_TIMEOUT_SECONDS } from '../../models/tournament.model';
import { TOURNAMENT_RECIPES } from '../../utils/benchmark-recipes';

describe('TournamentSetup', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('uses the saved lineup and settings for a new tournament only after Create', async () => {
    const api = createApiService();
    const create = vi.spyOn(api, 'createTournament');
    const source = { ...rematchSource(), legalMoveHints: false };
    const getTournament = vi.fn(() => of(source));
    Object.assign(api, {
      getTournament,
      getModels: () => of({ data: [catalogModel('a/one', 'One')] }),
    });

    const fixture = await renderSetup(api, 'old');
    const setup = fixture.componentInstance;
    expect(getTournament).toHaveBeenCalledWith('old');
    expect(setup.legalMoveHints).toBe(false);
    expect(create).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('Original');
    expect(setup.readiness().canCreate).toBe(true);
    setup.create();
    expect(create).toHaveBeenCalledWith(
      expect.objectContaining({
        name: 'Original (rematch)',
        drawMode: 'ORDER',
        generateBracket: true,
        runSettings: { maxConcurrentGames: 5, budgetUsd: '2.5' },
        moveTimeoutSeconds: 120,
        maxAttempts: 9,
        openingMode: 'FIXED',
        openingId: 'ruy-lopez',
        sharedCustomInstructions: 'Shared',
        resignationAllowed: true,
        adjudicationEnabled: true,
        legalMoveHints: false,
        reasoningEffort: 'medium',
        maxOutputTokens: 8192,
        matchupBestOf: 3,
        finalsBestOf: 5,
        chatMode: 'MANDATORY',
        banterLevel: 'PLAYFUL',
        drawPolicy: 'HIGHER_SEED_ADVANCES',
        clientRequestId: expect.any(String),
        participants: [
          {
            playerName: 'Model',
            controlType: 'AI',
            modelId: 'a/one',
            seed: 3,
            reasoningEffort: 'high',
            customInstructions: 'My instructions',
          },
          {
            playerName: 'Human',
            controlType: 'HUMAN',
            modelId: null,
            seed: 4,
            reasoningEffort: null,
            customInstructions: null,
          },
        ],
      }),
    );
    expect(create.mock.calls[0][0]).not.toHaveProperty('autoRunStatus');
    expect(create.mock.calls[0][0]).not.toHaveProperty('games');
  });

  it('compares the first game’s starting effort with what the rematch will use', async () => {
    const api = createApiService();
    const source = { ...rematchSource(), reasoningEffort: 'high' } as Tournament;
    const firstGame = {
      ...source.games[0],
      id: 'first-game',
      startedAt: '2026-11-18T10:00:00',
      whiteParticipantId: 'old-p1',
      blackParticipantId: 'old-p2',
      startingConfiguration: {
        format: source.format,
        drawPolicy: source.drawPolicy,
        matchupBestOf: source.matchupBestOf,
        finalsBestOf: source.finalsBestOf,
        openingMode: source.openingMode,
        configuredOpeningId: source.openingId,
        moveTimeoutSeconds: 120,
        maxAttempts: 9,
        maxOutputTokens: 8192,
        chatMode: 'MANDATORY',
        banterLevel: 'PLAYFUL',
        resignationAllowed: true,
        adjudicationEnabled: true,
        legalMoveHints: true,
        reasoningEffort: 'medium',
        white: {
          playerName: 'Model',
          modelId: 'a/one',
          controlType: 'AI',
          reasoningEffortOverride: 'high',
          requestedReasoningEffort: 'high',
        },
        black: { playerName: 'Human', controlType: 'HUMAN' },
      },
    } as Tournament['games'][number];
    source.games = [
      { ...firstGame, id: 'later-game', startedAt: '2026-11-18T10:01:00' },
      firstGame,
    ];
    api.getTournament = () => of(source);
    const fixture = await renderSetup(api, 'old');
    const setup = fixture.componentInstance;

    expect(setup.reasoningEffort).toBe('high');
    expect(setup.rematchStartingGame()?.id).toBe('first-game');
    expect(setup.rematchHistoricalChanges(firstGame)).toEqual([
      { setting: 'Reasoning effort', before: 'Medium', after: 'High' },
    ]);
    expect(
      fixture.nativeElement.querySelector(
        'details [aria-label="Changes from the first game’s start"]',
      ).textContent,
    ).toMatch(/Reasoning effort\s*Medium\s*High/);
    setup.editParticipant(0);
    setup.newPlayerName = 'Refined name';
    setup.addParticipant();
    fixture.detectChanges();
    expect(setup.rematchHistoricalChanges(firstGame)).toContainEqual({
      setting: 'White player',
      before: 'Model',
      after: 'Refined name',
    });
  });

  it('says when the first game predates configuration history', async () => {
    const api = createApiService();
    const source = rematchSource();
    source.games = [{ ...source.games[0], startedAt: '2026-11-18T10:00:00' }];
    api.getTournament = () => of(source);
    const fixture = await renderSetup(api, 'old');

    expect(fixture.nativeElement.textContent).toContain(
      'Starting settings are unknown for this game.',
    );
  });

  it('uses hints on for older sources and previews changes to the new draft', async () => {
    const api = createApiService();
    const source = rematchSource();
    Object.assign(api, {
      getTournament: () => of(source),
      getModels: () => of({ data: [catalogModel('a/one', 'One')] }),
    });
    const fixture = await renderSetup(api, 'old');
    const setup = fixture.componentInstance;
    expect(setup.legalMoveHints).toBe(true);
    expect(
      setup.rematchChanges(source).some((change) => change.setting === 'Legal move hints'),
    ).toBe(false);

    setup.legalMoveHints = false;
    fixture.detectChanges();
    expect(setup.rematchChanges(source)).toContainEqual({
      setting: 'Legal move hints',
      before: 'On',
      after: 'Off',
    });
    setup.create();
    expect(api.createTournamentPayload?.['legalMoveHints']).toBe(false);
    expect(source.legalMoveHints).toBeUndefined();
  });

  it('keeps Create blocked when the rematch source cannot be read', async () => {
    const api = createApiService();
    Object.assign(api, { getTournament: () => throwError(() => ({ status: 404 })) });
    const fixture = await renderSetup(api, 'missing');
    expect(fixture.nativeElement.textContent).toContain('Source tournament not found');
    fixture.componentInstance.create();
    expect(api.createTournamentPayload).toBeNull();
  });

  it('shows unavailable models and legacy prompts, then creates with the edited draft after acknowledgement', async () => {
    const api = createApiService();
    const source = {
      ...rematchSource(),
      creationDrawMode: 'RANDOM',
      legacySystemPrompt: true,
      openingId: 'removed-line',
      budgetUsd: 1e17,
      budgetUsdExact: '99999999999999999.99',
    };
    source.participants[1].legacySystemPrompt = true;
    Object.assign(api, {
      getTournament: () => of(source),
      getModels: () => of({ data: [catalogModel('b/two', 'Two')] }),
    });
    const fixture = await renderSetup(api, 'old');
    const setup = fixture.componentInstance;
    const text = fixture.nativeElement.textContent;
    expect(text).toContain('Tournament-wide legacy system template');
    expect(text).toContain('Legacy system template for Model');
    expect(text).toContain('Random draw');
    expect(text).toContain('Stored seed order');
    expect(text).toContain('The fixed opening removed-line is unavailable');
    expect(text).toContain('Model (a/one)');
    expect(setup.readiness().canCreate).toBe(false);

    const edit = fixture.nativeElement.querySelector(
      'button[aria-label="Edit Model"]',
    ) as HTMLButtonElement;
    edit.click();
    fixture.detectChanges();
    expect(setup.newCustomInstructions).toBe('My instructions');
    setup.onModelChanged('b/two');
    setup.newPlayerName = 'New Model';
    setup.addParticipant();
    setup.openingMode.set('STANDARD');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.rematch-diff').textContent).toContain('New Model');
    expect(fixture.nativeElement.querySelector('.rematch-diff').textContent).toContain(
      'removed-line',
    );
    setup.create();
    expect(api.createTournamentPayload).toBeNull();

    setup.rematchAcknowledged.set(true);
    expect(setup.readiness().canCreate).toBe(true);
    setup.create();
    expect(api.createTournamentPayload).toMatchObject({
      openingMode: 'STANDARD',
      runSettings: { maxConcurrentGames: 5, budgetUsd: '99999999999999999.99' },
      participants: [
        {
          playerName: 'New Model',
          modelId: 'b/two',
          customInstructions: 'My instructions',
          seed: 3,
          reasoningEffort: null,
        },
        { playerName: 'Human', seed: 4 },
      ],
    });
    expect(api.createTournamentPayload).not.toHaveProperty('openingId');
  });

  it('waits for the model catalog and keeps the loaded lineup on failure and retry', async () => {
    const api = createApiService();
    const source = rematchSource();
    const getModels = vi
      .fn()
      .mockReturnValueOnce(throwError(() => new Error('offline')))
      .mockReturnValueOnce(of({ data: [catalogModel('a/one', 'One')] }));
    Object.assign(api, { getTournament: () => of(source), getModels });
    const fixture = await renderSetup(api, 'old');
    const setup = fixture.componentInstance;
    expect(fixture.nativeElement.textContent).toContain('Model availability could not be checked');
    expect(setup.readiness().canCreate).toBe(false);
    setup.loadModels();
    fixture.detectChanges();
    expect(getModels).toHaveBeenCalledTimes(2);
    expect(setup.participants().map((p) => p.modelId)).toEqual(['a/one', null]);
    expect(setup.readiness().canCreate).toBe(true);
  });

  it('refreshes a missing model without clearing the draft and submits the trimmed name', async () => {
    const api = createApiService();
    const getModels = vi
      .fn()
      .mockReturnValueOnce(of({ data: [catalogModel('b/two', 'Two')] }))
      .mockReturnValueOnce(of({ data: [catalogModel('a/one', 'One')] }));
    Object.assign(api, { getTournament: () => of(rematchSource()), getModels });
    const fixture = await renderSetup(api, 'old');
    const setup = fixture.componentInstance;
    const normalizedName = 'A'.repeat(255);
    setup.name = '\u00a0' + normalizedName;
    const refresh = (
      Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[]
    ).find((button) => button.textContent.trim() === 'Refresh model catalog');
    expect(refresh).toBeDefined();
    refresh?.click();
    fixture.detectChanges();
    expect(setup.name).toBe('\u00a0' + normalizedName);
    expect(setup.unavailableModels()).toHaveLength(0);
    setup.create();
    expect(api.createTournamentPayload?.['name']).toBe(normalizedName);
  });

  it('disowns creation when the same setup route opens a different source', async () => {
    const api = createApiService();
    const source = rematchSource();
    const response = new Subject<Tournament>();
    const params = new Subject<ReturnType<typeof convertToParamMap>>();
    Object.assign(api, {
      getTournament: (id: string) => of({ ...source, id, name: id }),
      getModels: () => of({ data: [catalogModel('a/one', 'One')] }),
      createTournament: vi.fn(() => response),
    });
    const fixture = await renderSetup(api, 'old', params);
    const setup = fixture.componentInstance;
    params.next(convertToParamMap({ sourceId: 'old' }));
    setup.create();
    expect(setup.creating()).toBe(true);
    params.next(convertToParamMap({ sourceId: 'other' }));
    expect(setup.creating()).toBe(false);
    response.next(source);
    expect(TestBed.inject(Router).navigate).not.toHaveBeenCalled();
    expect(setup.name).toBe('other (rematch)');
  });

  it.each([
    [0, 1],
    [21, 20],
  ])('copies the effective attempt limit from legacy %s', async (stored, effective) => {
    const api = createApiService();
    const source = { ...rematchSource(), maxAttempts: stored };
    Object.assign(api, {
      getTournament: () => of(source),
      getModels: () => of({ data: [catalogModel('a/one', 'One')] }),
    });
    const fixture = await renderSetup(api, 'old');
    const setup = fixture.componentInstance;
    expect(setup.maxAttempts()).toBe(effective);
    expect(setup.rematchChanges(source)).toContainEqual({
      setting: 'Attempts per move',
      before: String(stored),
      after: String(effective),
    });
  });

  it('fits the rematch suffix and blocks invalid edited rules before submitting', async () => {
    const api = createApiService();
    const source = { ...rematchSource(), name: '♟️'.repeat(127) + ' ', moveTimeoutSeconds: 0 };
    Object.assign(api, {
      getTournament: () => of(source),
      getModels: () => of({ data: [catalogModel('a/one', 'One')] }),
    });
    const fixture = await renderSetup(api, 'old');
    const setup = fixture.componentInstance;
    expect(Array.from(setup.name)).toHaveLength(255);
    expect(setup.name).toMatch(/ \(rematch\)$/);
    expect(setup.readiness().canCreate).toBe(false);
    const timeout = fixture.nativeElement.querySelector('input[min="10"]') as HTMLInputElement;
    timeout.value = '120';
    timeout.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(setup.readiness().canCreate).toBe(true);
    const tokenLimit = fixture.nativeElement.querySelector('input[min="128"]') as HTMLInputElement;
    tokenLimit.value = '';
    tokenLimit.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.create-button').disabled).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('Enter a whole output-token limit');
    setup.create();
    expect(api.createTournamentPayload).toBeNull();
  });

  it('preserves maximum stored seeds and blocks appending beyond the integer range', async () => {
    const api = createApiService();
    const source = rematchSource();
    source.participants[0].seed = 2147483647;
    Object.assign(api, {
      getTournament: () => of(source),
      getModels: () => of({ data: [catalogModel('a/one', 'One')] }),
    });
    const fixture = await renderSetup(api, 'old');
    const setup = fixture.componentInstance;
    setup.newPlayerName = 'Extra';
    setup.onModelChanged('a/one');
    expect(setup.canAddParticipant()).toBe(false);
    setup.addParticipant();
    expect(setup.participants()).toHaveLength(2);
    expect(setup.readiness().canCreate).toBe(true);
  });

  it('shows seed changes when an otherwise identical player is re-added', async () => {
    const api = createApiService();
    const source = rematchSource();
    source.participants[0].seed = 30;
    source.participants[1].seed = 10;
    Object.assign(api, {
      getTournament: () => of(source),
      getModels: () => of({ data: [catalogModel('a/one', 'One')] }),
    });
    const fixture = await renderSetup(api, 'old');
    const setup = fixture.componentInstance;
    setup.removeParticipant(1);
    setup.newControlType = 'HUMAN';
    setup.newPlayerName = 'Human';
    setup.addParticipant();
    fixture.detectChanges();
    const diff = fixture.nativeElement.querySelector('.rematch-diff').textContent;
    expect(diff).toContain('Seed 30');
    expect(diff).toContain('Seed 11');
  });

  it('refreshes model availability after a create rejection while keeping the edited draft', async () => {
    const api = createApiService();
    const getModels = vi
      .fn()
      .mockReturnValueOnce(of({ data: [catalogModel('a/one', 'One')] }))
      .mockReturnValueOnce(of({ data: [catalogModel('b/two', 'Two')] }));
    Object.assign(api, {
      getTournament: () => of(rematchSource()),
      getModels,
      createTournament: () =>
        throwError(() => ({
          status: 400,
          error: { error: 'Model is not allowed by backend policy: a/one' },
        })),
    });
    const fixture = await renderSetup(api, 'old');
    const setup = fixture.componentInstance;
    setup.name = 'Edited draft';
    setup.create();
    expect(getModels).toHaveBeenCalledTimes(2);
    expect(setup.name).toBe('Edited draft');
    expect(setup.unavailableModels()[0].modelId).toBe('a/one');
  });

  it.each(['env', 'file', 'ui'] as const)(
    'shows no connection or invalid-key warning for an untested %s key',
    async (source) => {
      const fixture = await renderSetup(
        createStatusApi({
          source,
          maskedSuffix: null,
          lastValidatedAt: null,
          validation: 'unknown',
        }),
      );
      expect(fixture.componentInstance.openRouterReady()).toBe(true);
      expect(fixture.componentInstance.openRouterValidation()).toBe('unknown');
      expect(fixture.nativeElement.querySelector('.openrouter-status')).toBeNull();
    },
  );

  it('shows an info hint only when the status confirms there is no key', async () => {
    const fixture = await renderSetup(
      createStatusApi({
        source: 'none',
        maskedSuffix: null,
        lastValidatedAt: null,
        validation: 'none',
      }),
    );
    expect(fixture.componentInstance.openRouterReady()).toBe(false);
    const banner = fixture.nativeElement.querySelector('.openrouter-status.info[role="status"]');
    expect(banner.textContent).toContain(
      'Connect your OpenRouter account to create and run tournaments.',
    );
    expect(banner.querySelector('mat-icon').textContent).toBe('info');
    expect(banner.querySelector('button').textContent.trim()).toBe('Connect OpenRouter');
    expect(fixture.nativeElement.querySelector('.openrouter-status[role="alert"]')).toBeNull();
  });

  it('shows an invalid-key hint while keeping a failed key connected', async () => {
    const fixture = await renderSetup(
      createStatusApi({
        source: 'env',
        maskedSuffix: '••••-key',
        lastValidatedAt: '2026-03-06T18:00:00Z',
        validation: 'invalid',
      }),
    );
    expect(fixture.componentInstance.openRouterReady()).toBe(true);
    const alert = fixture.nativeElement.querySelector('.openrouter-status[role="alert"]');
    expect(alert.textContent).toContain('failed its last validation');
    expect(alert.textContent).not.toContain('Connect OpenRouter');
    expect(alert.classList.contains('warn')).toBe(true);
    expect(alert.querySelector('button').textContent.trim()).toBe('Fix connection');
  });

  it('does not warn about an invalid or missing key when a check was unavailable', async () => {
    const fixture = await renderSetup(
      createStatusApi({
        source: 'file',
        maskedSuffix: '••••-key',
        lastValidatedAt: '2026-03-06T18:00:00Z',
        validation: 'unavailable',
      }),
    );
    expect(fixture.componentInstance.openRouterReady()).toBe(true);
    expect(fixture.componentInstance.openRouterValidation()).toBe('unknown');
    expect(fixture.nativeElement.querySelector('.openrouter-status')).toBeNull();
  });

  it('does not mistake a failed status fetch for a missing key', async () => {
    const api = createStatusApi();
    vi.spyOn(api, 'checkOpenRouterStatus').mockReturnValue(throwError(() => new Error('offline')));
    const fixture = await renderSetup(api);
    expect(fixture.componentInstance.openRouterReady()).toBeNull();
    expect(fixture.nativeElement.querySelector('.openrouter-status')).toBeNull();
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

  it.each(['RANDOM', 'ORDER'] as const)(
    'explicitly opts into bracket creation from Matches with %s',
    async (mode) => {
      const api = createApiService();
      const fixture = await renderSetup(api);
      const navigate = vi.spyOn(TestBed.inject(Router), 'navigate');
      const component = fixture.componentInstance;
      component.name = 'Ready Arena';
      component.participants.set([
        pricedParticipant('Alpha', 'a/model', 0, 0),
        { ...pricedParticipant('Beta', 'b/model', 0, 0), seed: 1 },
      ]);
      fixture.detectChanges();
      const select = await TestbedHarnessEnvironment.loader(fixture).getHarness(
        MatSelectHarness.with({ selector: '[aria-labelledby="setup-matches-heading"] mat-select' }),
      );
      expect(await select.getValueText()).toBe('Random draw');
      if (mode === 'ORDER') {
        await select.open();
        await select.clickOptions({ text: 'In added order' });
      }
      fixture.nativeElement.querySelector('.create-button').click();
      expect(api.createTournamentPayload?.['drawMode']).toBe(mode);
      expect(api.createTournamentPayload?.['generateBracket']).toBe(true);
      expect(api.createTournamentPayload?.['participants']).toHaveLength(2);
      expect(navigate).toHaveBeenCalledExactlyOnceWith(['/tournaments', 't-1']);
    },
  );

  it('reuses the create request id until the draw choice changes', () => {
    const api = createApiService();
    const requests: Record<string, unknown>[] = [];
    api.createTournament = ((payload: Record<string, unknown>) => {
      requests.push(payload);
      return throwError(() => ({ status: 0 }));
    }) as any;
    const component = new TournamentSetup(api, createRouter(), createRoute());
    component.create();
    component.create();
    component.drawMode = 'ORDER';
    component.create();
    expect(requests[0]['clientRequestId']).toBe(requests[1]['clientRequestId']);
    expect(requests[2]['clientRequestId']).not.toBe(requests[0]['clientRequestId']);
    expect(requests[2]['drawMode']).toBe('ORDER');
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
        {
          provide: OpenRouterAccess,
          useValue: { keyStatus: () => null, withKey: (action: () => void) => action() },
        },
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

  it('blocks invalid attempt limits and accepts both boundaries', async () => {
    const api = createApiService();
    const fixture = await renderSetup(api);
    const component = fixture.componentInstance;
    component.name = 'Attempts';
    component.participants.set([
      pricedParticipant('Alpha', 'a/model', 0, 0),
      pricedParticipant('Beta', 'b/model', 0, 0),
    ]);
    const field = fixture.nativeElement
      .querySelector('[aria-label="About move attempts"]')
      .closest('mat-form-field') as HTMLElement;
    const input = field.querySelector('input')!;
    const createButton = fixture.nativeElement.querySelector('.create-button') as HTMLButtonElement;
    expect(input.min).toBe('1');
    expect(input.max).toBe('20');
    for (const value of ['0', '-1', '21', '1.5', '', '1', '20']) {
      input.value = value;
      input.dispatchEvent(new Event('input'));
      input.dispatchEvent(new Event('blur'));
      fixture.detectChanges();
      await fixture.whenStable();
      const valid = value === '1' || value === '20';
      expect(createButton.disabled).toBe(!valid);
      if (valid) {
        expect(field.textContent).not.toContain('Enter a whole number');
      } else {
        expect(field.textContent).toContain('Enter a whole number from 1 to 20');
        component.create();
        expect(api.createTournamentPayload).toBeNull();
      }
    }
    createButton.click();
    expect(api.createTournamentPayload?.['maxAttempts']).toBe(20);
    expect(api.createTournamentPayload).not.toHaveProperty('maxRetries');
  });

  it('renders the token bounds and credit-balance hint and validates the boundaries', async () => {
    TestBed.configureTestingModule({
      imports: [TournamentSetup],
      providers: [
        {
          provide: OpenRouterAccess,
          useValue: { keyStatus: () => null, withKey: (action: () => void) => action() },
        },
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
    expect(hint).toContain('Cut-offs pause the game.');
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
        {
          provide: OpenRouterAccess,
          useValue: { keyStatus: () => null, withKey: (action: () => void) => action() },
        },
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
    expect(summary?.riskReason).toBe(
      'Low cost risk; runtime is open-ended without a move time limit.',
    );

    component.limitMoveTime = true;
    component.moveTimeout = 120;
    const timed = component.confidenceSummary();
    expect(timed?.likelyRuntimeMinutes).toBe(35);
    expect(timed?.maxRuntimeMinutes).toBe(70);
    expect(timed?.paceLabel).toBe('Balanced');
    expect(timed?.riskReason).toBe('Good fit for a first pass or low-risk validation run.');
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

  it('puts model search before participant fields and the add action after them', async () => {
    const fixture = await renderSetup(createStatusApi());
    const element: HTMLElement = fixture.nativeElement;
    const card = element.querySelector('.participants-card')!;
    const picker = card.querySelector('app-model-picker')!;
    const name = card.querySelector('input[placeholder="e.g. Claude Opus 4.6"]')!;
    const toggle = card.querySelector('.instructions-toggle')!;
    const add = card.querySelector<HTMLButtonElement>('.add-button')!;
    const follows = (before: Element, after: Element) =>
      Boolean(before.compareDocumentPosition(after) & Node.DOCUMENT_POSITION_FOLLOWING);
    expect(follows(picker, name)).toBe(true);
    expect(follows(name, toggle)).toBe(true);
    expect(follows(toggle, add)).toBe(true);
    expect(add.disabled).toBe(true);
    const search = picker.querySelector<HTMLInputElement>('input')!;
    search.focus();
    search.value = 'Fixture';
    search.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    await fixture.whenStable();
    document.querySelector<HTMLElement>('mat-option')!.click();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(fixture.componentInstance.newPlayerName).toBe('Fixture model');
    expect(add.disabled).toBe(false);
    add.click();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(fixture.componentInstance.participants()).toEqual([
      expect.objectContaining({ playerName: 'Fixture model', modelId: 'acme/model' }),
    ]);
    expect(element.querySelector('.participant-name')?.textContent).toBe('Fixture model');
  });

  it('shows aligned model, price and context columns with readable names and markers', async () => {
    const api = createStatusApi();
    const longName = 'Fixture Research Preview with a Very Long Model Name';
    vi.mocked(api.getModels).mockReturnValue(
      of({
        data: [
          {
            ...catalogModel('acme/paid', longName, 0.35, 1.7),
            contextLength: 128000,
            reasoningSupported: true,
          },
          { ...catalogModel('acme/free', 'Fixture Free', 0, 0), contextLength: 32000 },
        ],
      }),
    );
    const fixture = await renderSetup(api);
    const element: HTMLElement = fixture.nativeElement;
    const search = element.querySelector<HTMLInputElement>('app-model-picker input')!;
    search.click();
    fixture.detectChanges();
    await fixture.whenStable();
    const panel = document.querySelector('.model-picker-panel')!;
    const columns = [
      '.option-title',
      '.option-input-price',
      '.option-output-price',
      '.option-context',
    ];
    const values = (row: Element) =>
      columns.map((column) => row.querySelector(column)?.textContent?.trim());
    expect(values(panel.querySelector('.option-headings')!)).toEqual([
      'Model',
      '$/M in',
      '$/M out',
      'Context',
    ]);
    const options = Array.from(panel.querySelectorAll('mat-option'));
    const paid = options.find((option) => option.textContent?.includes(longName))!;
    const free = options.find((option) => option.textContent?.includes('Fixture Free'))!;
    expect(values(paid)).toEqual([longName, '0.35', '1.70', '128K']);
    expect(values(free)).toEqual(['Fixture Free', '0', '0', '32K']);
    expect(paid.querySelector('.option-title')?.getAttribute('title')).toBe(longName);
    expect(paid.getAttribute('aria-label')).toContain(longName);
    expect(paid.querySelector('.option-markers')?.textContent).toContain('Reasoning');
    expect(free.querySelector('.option-markers')?.textContent).toContain('Free');
    expect(paid.querySelectorAll('.num')).toHaveLength(3);
  });

  it.each([false, true])(
    'moves focus after keyboard model choice (existing name: %s)',
    async (named) => {
      const fixture = await renderSetup(createStatusApi());
      const element: HTMLElement = fixture.nativeElement;
      const name = element.querySelector<HTMLInputElement>(
        'input[placeholder="e.g. Claude Opus 4.6"]',
      )!;
      const search = element.querySelector<HTMLInputElement>('app-model-picker input')!;
      if (named) {
        name.value = 'My Player';
        name.dispatchEvent(new Event('input'));
      }
      search.focus();
      for (const [key, keyCode] of [
        ['ArrowDown', 40],
        ['Enter', 13],
      ] as const) {
        const event = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true });
        Object.defineProperty(event, 'keyCode', { value: keyCode });
        search.dispatchEvent(event);
        fixture.detectChanges();
        await fixture.whenStable();
      }
      expect(name.value).toBe(named ? 'My Player' : 'Fixture model');
      expect(document.activeElement).toBe(named ? element.querySelector('.add-button') : name);
    },
  );

  it('keeps focus on Display Name when the user moves there after model choice', async () => {
    const fixture = await renderSetup(createStatusApi());
    const element: HTMLElement = fixture.nativeElement;
    const name = displayNameInput(element);
    name.value = 'My Player';
    name.dispatchEvent(new Event('input'));
    const search = element.querySelector<HTMLInputElement>('app-model-picker input')!;
    search.focus();
    search.click();
    fixture.detectChanges();
    await fixture.whenStable();

    document.querySelector<HTMLElement>('.model-picker-panel mat-option')!.click();
    expect(document.activeElement).toBe(search);
    name.focus();
    fixture.detectChanges();
    await fixture.whenStable();

    expect(fixture.componentInstance.newModelId).toBe('acme/model');
    expect(document.activeElement).toBe(name);
  });

  it('returns focus to model search after adding a participant', async () => {
    const fixture = await renderSetup(createStatusApi());
    const element: HTMLElement = fixture.nativeElement;
    const search = element.querySelector<HTMLInputElement>('app-model-picker input')!;
    search.click();
    fixture.detectChanges();
    await fixture.whenStable();
    document.querySelector<HTMLElement>('.model-picker-panel mat-option')!.click();
    fixture.detectChanges();
    await fixture.whenStable();
    const add = element.querySelector<HTMLButtonElement>('.add-button')!;
    add.focus();
    add.click();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(fixture.componentInstance.participants()).toHaveLength(1);
    expect(document.activeElement).toBe(search);
    fixture.detectChanges();
    await fixture.whenStable();
    expect(search.value).toBe('');
  });

  it('keeps focus on Display Name when the user moves there after Add', async () => {
    const fixture = await renderSetup(createStatusApi());
    const element: HTMLElement = fixture.nativeElement;
    const name = displayNameInput(element);
    fixture.componentInstance.onModelChanged('acme/model');
    fixture.detectChanges();
    await fixture.whenStable();
    const add = element.querySelector<HTMLButtonElement>('.add-button')!;

    add.focus();
    add.click();
    name.focus();
    fixture.detectChanges();
    await fixture.whenStable();

    expect(fixture.componentInstance.participants()).toHaveLength(1);
    expect(document.activeElement).toBe(name);
  });

  it('focuses the next removal, previous removal, or model search after lineup removal', async () => {
    const fixture = await renderSetup(createStatusApi());
    const element: HTMLElement = fixture.nativeElement;
    fixture.componentInstance.participants.set([
      pricedParticipant('Alpha', 'example/alpha', 1, 2),
      pricedParticipant('Beta', 'example/beta', 1, 2),
      pricedParticipant('Gamma', 'example/gamma', 1, 2),
    ]);
    fixture.detectChanges();

    const search = element.querySelector<HTMLInputElement>('app-model-picker input')!;
    const remove = (name: string) =>
      element.querySelector<HTMLButtonElement>(`button[aria-label="Remove ${name}"]`)!;
    for (const [name, next] of [
      ['Beta', 'Gamma'],
      ['Gamma', 'Alpha'],
      ['Alpha', null],
    ] as const) {
      const button = remove(name);
      button.focus();
      expect(document.activeElement).toBe(button);
      button.click();
      fixture.detectChanges();
      await fixture.whenStable();
      expect(remove(name)).toBeNull();
      expect(next ? remove(next) : search).not.toBeNull();
      expect(document.activeElement).toBe(next ? remove(next) : search);
    }
  });

  it('keeps focus on Display Name when the user moves there after Remove', async () => {
    const fixture = await renderSetup(createStatusApi());
    const element: HTMLElement = fixture.nativeElement;
    fixture.componentInstance.participants.set([pricedParticipant('Alpha', 'example/alpha', 1, 2)]);
    fixture.detectChanges();
    const remove = element.querySelector<HTMLButtonElement>('button[aria-label="Remove Alpha"]')!;
    const name = displayNameInput(element);

    remove.focus();
    remove.click();
    name.focus();
    fixture.detectChanges();
    await fixture.whenStable();

    expect(remove.isConnected).toBe(false);
    expect(document.activeElement).toBe(name);
  });

  it('keeps the focused participant’s Remove action and row when a prior row is removed', async () => {
    const api = createStatusApi();
    api.getTournament = () => of(rematchSource());
    const fixture = await renderSetup(api, 'old');
    const element: HTMLElement = fixture.nativeElement;
    const setup = fixture.componentInstance;
    setup.participants.set(
      ['Alpha', 'Beta', 'Gamma', 'Delta'].map((name) =>
        pricedParticipant(name, 'acme/model', 1, 2),
      ),
    );
    fixture.detectChanges();
    const remove = (name: string) =>
      element.querySelector<HTMLButtonElement>(`button[aria-label="Remove ${name}"]`)!;
    const beta = remove('Beta');
    const gamma = remove('Gamma');
    const delta = remove('Delta');
    const gammaRow = gamma.closest('li');
    const deltaRow = delta.closest('li');
    const deltaSeed = () => deltaRow?.querySelector('.participant-seed')?.textContent?.trim();
    expect(deltaSeed()).toBe('4');

    beta.focus();
    beta.click();
    gamma.focus();
    fixture.detectChanges();
    await fixture.whenStable();

    expect(document.activeElement).toBe(gamma);
    const gammaAfterRemoval = remove('Gamma');
    const gammaRowAfterRemoval = gammaAfterRemoval.closest('li');
    // jsdom does not synthesize the native button click from Enter.
    (document.activeElement as HTMLButtonElement).click();
    fixture.detectChanges();
    await fixture.whenStable();

    expect(setup.participants().map(({ playerName }) => playerName)).toEqual(['Alpha', 'Delta']);
    expect(beta.isConnected).toBe(false);
    expect(gammaAfterRemoval).toBe(gamma);
    expect(gammaRowAfterRemoval).toBe(gammaRow);
    expect(gammaRow?.isConnected).toBe(false);
    expect(remove('Delta')).toBe(delta);
    expect(delta.closest('li')).toBe(deltaRow);
    expect(deltaSeed()).toBe('2');

    element.querySelector<HTMLButtonElement>('button[aria-label="Edit Delta"]')!.click();
    fixture.detectChanges();
    await fixture.whenStable();
    const name = displayNameInput(element);
    name.value = 'Edited Delta';
    name.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    element.querySelector<HTMLButtonElement>('.add-button')!.click();
    fixture.detectChanges();
    await fixture.whenStable();

    expect(setup.participants().map(({ playerName }) => playerName)).toEqual([
      'Alpha',
      'Edited Delta',
    ]);
    expect(remove('Edited Delta')).toBe(delta);
    expect(delta.closest('li')).toBe(deltaRow);
  });

  it('fills an emptied display name and enables Add when reselecting the same model', async () => {
    const fixture = await renderSetup(createStatusApi());
    const element: HTMLElement = fixture.nativeElement;
    const search = element.querySelector<HTMLInputElement>('app-model-picker input')!;
    const name = element.querySelector<HTMLInputElement>(
      'input[placeholder="e.g. Claude Opus 4.6"]',
    )!;
    const add = element.querySelector<HTMLButtonElement>('.add-button')!;
    const chooseModel = async () => {
      search.click();
      fixture.detectChanges();
      await fixture.whenStable();
      document.querySelector<HTMLElement>('.model-picker-panel mat-option')!.click();
      fixture.detectChanges();
      await fixture.whenStable();
    };

    await chooseModel();
    expect(name.value).toBe('Fixture model');
    name.value = '';
    name.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(add.disabled).toBe(true);
    expect(fixture.componentInstance.newModelId).toBe('acme/model');

    await chooseModel();
    expect(name.value).toBe('Fixture model');
    expect(add.disabled).toBe(false);
    expect(document.activeElement).toBe(name);
  });

  it('moves catalog Retry focus through loading to Retry on failure and Model on success', async () => {
    const api = createStatusApi();
    const failed = new Subject<OpenRouterModelsResponse>();
    const succeeded = new Subject<OpenRouterModelsResponse>();
    vi.mocked(api.getModels)
      .mockReturnValueOnce(throwError(() => ({ status: 503 })))
      .mockReturnValueOnce(failed)
      .mockReturnValueOnce(succeeded);
    const fixture = await renderSetup(api);
    const element: HTMLElement = fixture.nativeElement;
    const search = element.querySelector<HTMLInputElement>('app-model-picker input')!;
    const retry = () => element.querySelector<HTMLButtonElement>('.retry-button')!;
    const startRetry = async () => {
      const button = retry();
      button.focus();
      expect(document.activeElement).toBe(button);
      button.click();
      fixture.detectChanges();
      await fixture.whenStable();
      const status = element.querySelector<HTMLElement>('.openrouter-status[role="status"]')!;
      expect(status.textContent).toContain('Loading models');
      expect(status.getAttribute('aria-live')).toBe('polite');
      expect(status.tabIndex).toBe(-1);
      expect(document.activeElement).toBe(status);
    };

    await startRetry();
    failed.error({ status: 503 });
    fixture.detectChanges();
    await fixture.whenStable();
    expect(document.activeElement).toBe(retry());

    await startRetry();
    succeeded.next({ data: [catalogModel('acme/model', 'Fixture model')] });
    succeeded.complete();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(search.disabled).toBe(false);
    expect(document.activeElement).toBe(search);
  });

  it.each(['Retry', 'Retry model catalog'] as const)(
    'keeps focus on Display Name when the user moves there before %s renders loading',
    async (label) => {
      const request = new Subject<OpenRouterModelsResponse>();
      const fixture = await renderCatalogRetry(label, request);
      const element: HTMLElement = fixture.nativeElement;
      const retry = catalogAction(element, label);
      const name = displayNameInput(element);

      retry.focus();
      retry.click();
      name.focus();
      fixture.detectChanges();
      await fixture.whenStable();

      expect(catalogLoadingStatus(element)).not.toBeNull();
      expect(document.activeElement).toBe(name);

      finishCatalogRequest(request, 'success');
      fixture.detectChanges();
      await fixture.whenStable();
      expect(document.activeElement).toBe(name);
    },
  );

  it.each([
    ['Retry', 'success'],
    ['Retry', 'failure'],
    ['Retry model catalog', 'success'],
    ['Retry model catalog', 'failure'],
  ] as const)(
    'keeps focus on Display Name when the user moves there after the %s response (%s)',
    async (label, result) => {
      const request = new Subject<OpenRouterModelsResponse>();
      const fixture = await renderCatalogRetry(label, request);
      const element: HTMLElement = fixture.nativeElement;
      const retry = catalogAction(element, label);
      const name = displayNameInput(element);

      retry.focus();
      retry.click();
      fixture.detectChanges();
      await fixture.whenStable();
      const status = catalogLoadingStatus(element)!;
      expect(document.activeElement).toBe(status);

      finishCatalogRequest(request, result);
      expect(document.activeElement).toBe(status);
      name.focus();
      fixture.detectChanges();
      await fixture.whenStable();

      expect(status.isConnected).toBe(false);
      expect(document.activeElement).toBe(name);
    },
  );

  it.each(['success', 'failure'] as const)(
    'moves Refresh focus through loading to the catalog result (%s)',
    async (result) => {
      const request = new Subject<OpenRouterModelsResponse>();
      const fixture = await renderSetup(createRefreshApi(request), 'old');
      const element: HTMLElement = fixture.nativeElement;
      const refresh = catalogAction(element, 'Refresh model catalog');

      refresh.focus();
      refresh.click();
      fixture.detectChanges();
      await fixture.whenStable();
      const status = catalogLoadingStatus(element)!;
      expect(status).not.toBeNull();
      expect(document.activeElement).toBe(status);

      finishCatalogRequest(request, result);
      fixture.detectChanges();
      await fixture.whenStable();

      const target =
        result === 'success'
          ? element.querySelector<HTMLInputElement>('app-model-picker input')
          : catalogAction(element, 'Retry model catalog');
      expect(target).not.toBeNull();
      expect(document.activeElement).toBe(target);
    },
  );

  it.each([
    ['Retry', 'success'],
    ['Retry', 'failure'],
    ['Refresh model catalog', 'success'],
    ['Refresh model catalog', 'failure'],
  ] as const)(
    'keeps focus on body when the user clicks blank space while %s is pending (%s)',
    async (label, result) => {
      const request = new Subject<OpenRouterModelsResponse>();
      const fixture =
        label === 'Retry'
          ? await renderCatalogRetry(label, request)
          : await renderSetup(createRefreshApi(request), 'old');
      const element: HTMLElement = fixture.nativeElement;
      const action = catalogAction(element, label);

      action.focus();
      action.click();
      fixture.detectChanges();
      await fixture.whenStable();
      const status = catalogLoadingStatus(element)!;
      expect(document.activeElement).toBe(status);

      document.body.click();
      // jsdom does not perform the native focus change on a click on blank space.
      if (document.activeElement === status) status.blur();
      await fixture.whenStable();
      expect(status.isConnected).toBe(true);
      expect(document.activeElement).toBe(document.body);

      finishCatalogRequest(request, result);
      fixture.detectChanges();
      await fixture.whenStable();

      expect(status.isConnected).toBe(false);
      expect(document.activeElement).toBe(document.body);
    },
  );

  it('keeps focus on Display Name when the user moves there during either Refresh handoff', async () => {
    const first = new Subject<OpenRouterModelsResponse>();
    const second = new Subject<OpenRouterModelsResponse>();
    const api = createRefreshApi(first);
    vi.mocked(api.getModels).mockReturnValueOnce(second);
    const fixture = await renderSetup(api, 'old');
    const element: HTMLElement = fixture.nativeElement;
    const name = displayNameInput(element);
    const startRefresh = () => {
      const refresh = catalogAction(element, 'Refresh model catalog');
      refresh.focus();
      refresh.click();
    };

    startRefresh();
    name.focus();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(catalogLoadingStatus(element)).not.toBeNull();
    expect(document.activeElement).toBe(name);
    first.next({ data: [catalogModel('b/two', 'Two')] });
    first.complete();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(document.activeElement).toBe(name);

    startRefresh();
    fixture.detectChanges();
    await fixture.whenStable();
    const status = catalogLoadingStatus(element)!;
    expect(document.activeElement).toBe(status);
    finishCatalogRequest(second, 'success');
    expect(document.activeElement).toBe(status);
    name.focus();
    fixture.detectChanges();
    await fixture.whenStable();

    expect(status.isConnected).toBe(false);
    expect(document.activeElement).toBe(name);
  });

  it('shows one next-input hint beside Create as the lineup and tournament name are filled', async () => {
    const fixture = await renderSetup();
    const element: HTMLElement = fixture.nativeElement;
    const setup = fixture.componentInstance;
    const create = element.querySelector<HTMLButtonElement>('.create-button')!;
    const name = element.querySelector<HTMLInputElement>('.lineup-card input')!;
    const players = [
      pricedParticipant('Alpha', 'example/alpha', 1, 2),
      pricedParticipant('Beta', 'example/beta', 1, 2),
    ];
    const expectHint = (message: string) => {
      expect(element.querySelectorAll('.launch-hint')).toHaveLength(1);
      expect(create.previousElementSibling?.textContent?.trim()).toBe(message);
      expect(element.textContent?.split(message)).toHaveLength(2);
      expect(element.textContent).not.toMatch(
        /Enter a display name to add|at least 2 needed|Add at least two participants/,
      );
      expect(create.disabled).toBe(true);
    };
    expectHint('Add your first participant.');
    expect(element.querySelector('.confidence-empty')?.textContent?.trim()).toBe(
      'Estimate pending.',
    );
    setup.participants.set(players.slice(0, 1));
    fixture.detectChanges();
    expectHint('Add a second participant.');
    setup.participants.set(players);
    fixture.detectChanges();
    expectHint('Enter a tournament name.');
    name.value = 'Fixture Cup';
    name.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    await fixture.whenStable();
    expect(element.querySelector('.launch-hint')?.textContent?.trim()).toBe('');
    expect(create.disabled).toBe(false);
  });

  it('shows the participant count for empty, singular and populated lineups', async () => {
    const fixture = await renderSetup();
    const element: HTMLElement = fixture.nativeElement;
    const count = () =>
      element
        .querySelector('.participants-list-head .caption')
        ?.textContent?.replace(/\s+/g, ' ')
        .trim();
    expect(count()).toBe('0 participants');
    for (const size of [1, 4]) {
      fixture.componentInstance.participants.set(
        Array.from({ length: size }, (_, i) =>
          pricedParticipant(`Player ${i}`, `model/${i}`, 1, 2),
        ),
      );
      fixture.detectChanges();
      await fixture.whenStable();
      expect(count()).toBe(`${size} ${size === 1 ? 'participant' : 'participants'}`);
    }
  });

  it('starts estimate assumptions closed and allows expanding and closing them', async () => {
    const fixture = await renderSetup();
    fixture.componentInstance.participants.set([
      pricedParticipant('Alpha', 'example/alpha', 1, 2),
      pricedParticipant('Beta', 'example/beta', 1, 2),
    ]);
    fixture.detectChanges();
    await fixture.whenStable();
    const element: HTMLElement = fixture.nativeElement;
    const details = element.querySelector<HTMLDetailsElement>('.confidence-assumptions')!;
    const summary = details.querySelector('summary')!;
    expect(details.open).toBe(false);
    expect(summary.textContent?.trim()).toBe('Assumptions');
    summary.click();
    expect(details.open).toBe(true);
    expect(details.textContent).toContain('70 moves per game');
    expect(details.textContent).toContain('Without move limits, runtime is open-ended');
    summary.click();
    expect(details.open).toBe(false);
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
    expect(aside.querySelector('.participants-list-head')?.textContent).toContain('2 participants');
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
    expect(element.querySelector('.confidence-note')?.textContent).toBe(
      'Low cost risk; runtime is open-ended without a move time limit.',
    );
    expect(
      Array.from(element.querySelectorAll('.confidence-primary .metric-label')).map(
        (label) => label.textContent,
      ),
    ).toEqual(['Estimated Spend', 'Estimated Runtime', 'Projected Games']);
    expect(
      Array.from(element.querySelectorAll('.confidence-secondary .metric-label')).map(
        (label) => label.textContent,
      ),
    ).toEqual(['Series', 'Move Pace', 'Pricing Coverage']);
    expect(metric('Move Pace').querySelector('.num')).toBeNull();
    expect(metric('Move Pace').querySelector('strong')?.textContent).toBe('No limit');
    expect(metric('Pricing Coverage').querySelector('.num')?.textContent).toBe('2/2');
    expect(metric('Pricing Coverage').querySelector('strong')?.textContent).toContain(
      'models priced',
    );
    expect(metric('Estimated Spend').querySelector('.num')?.textContent).toMatch(/^\$/);
    expect(metric('Estimated Runtime').querySelector('.num')?.textContent).not.toContain('about');
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

  it('qualifies every cost band when runtime has no move limit', () => {
    const component = new TournamentSetup(createApiService(), createRouter(), createRoute());
    component.limitMoveTime = false;
    component.participants.set([
      pricedParticipant('Alpha', 'example/alpha', 1, 2),
      pricedParticipant('Beta', 'example/beta', 1, 2),
    ]);
    component.matchupBestOf = 7;
    component.finalsBestOf = null;
    component.drawPolicy = 'REPLAY_GAME';
    expect(component.confidenceSummary()).toMatchObject({
      riskBand: 'Moderate',
      riskReason: 'Moderate cost risk; runtime is open-ended without a move time limit.',
    });
    component.participants.set(
      Array.from({ length: 16 }, (_, i) =>
        pricedParticipant(`Player ${i}`, `model/${i}`, 100, 100),
      ),
    );
    expect(component.confidenceSummary()).toMatchObject({
      riskBand: 'Expensive',
      riskReason: 'High cost; runtime is open-ended without a move time limit.',
    });
    component.limitMoveTime = true;
    expect(component.confidenceSummary()?.riskReason).toBe(
      'A larger field, longer series, or pricier models make this a heavier run.',
    );
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

  it('sends Original by default, each chosen tone, and retains it while chat is Off', () => {
    const api = createApiService();
    const component = new TournamentSetup(api, createRouter(), createRoute());
    component.name = 'Banter Arena';
    component.create();
    expect(api.createTournamentPayload?.['banterLevel']).toBeNull();
    for (const level of ['POLITE', 'PLAYFUL', 'SPICY'] as const) {
      component.banterLevel = level;
      component.create();
      expect(api.createTournamentPayload?.['banterLevel']).toBe(level);
      expect(component.banterHint()).toBe(component.banterHints[level]);
    }
    component.chatMode = 'OFF';
    component.create();
    expect(api.createTournamentPayload?.['banterLevel']).toBe('SPICY');
    component.banterLevel = null;
    component.create();
    expect(api.createTournamentPayload?.['banterLevel']).toBeNull();
  });

  it('offers Mind games and explains when opponents cannot resign', () => {
    const api = createApiService();
    const component = new TournamentSetup(api, createRouter(), createRoute());
    component.name = 'Mind games arena';
    const option = component.banterOptions.find(({ label }) => label === 'Mind games');
    expect(option).toEqual({ value: 'MIND_GAMES', label: 'Mind games' });
    component.banterLevel = option!.value;

    component.resignationAllowed = false;
    expect(component.banterHint()).toContain('Allow resignation is off');
    component.resignationAllowed = true;
    expect(component.banterHint()).not.toContain('Allow resignation is off');
    component.create();
    expect(api.createTournamentPayload?.['banterLevel']).toBe('MIND_GAMES');
  });

  it('shows tone-specific rules including resignation, and ignores tone when chat is Off', () => {
    const api = createApiService();
    api.getPromptTemplate = () =>
      of({
        template: 'Original rules',
        version: 'v2',
        hash: 'h',
        chatModes: { OFF: { template: 'Silent', version: 'v2-silent', hash: 'h0' } },
        banterChatModes: {
          SPICY: {
            MANDATORY: {
              template: 'Bold tone',
              version: 'v2-chat-required-banter-spicy',
              hash: 'h1',
            },
          },
        },
        resignationBanterChatModes: {
          SPICY: {
            MANDATORY: {
              template: 'Bold with resignation',
              version: 'v2-chat-required-resign-banter-spicy',
              hash: 'h2',
            },
          },
        },
      });
    const component = new TournamentSetup(api, createRouter(), createRoute());
    component.ngOnInit();
    component.chatMode = 'MANDATORY';
    component.banterLevel = 'SPICY';
    expect(component.systemRulesTemplate).toBe('Bold tone');
    expect(component.systemRulesVersion()).toBe('v2-chat-required-banter-spicy');
    component.resignationAllowed = true;
    expect(component.systemRulesTemplate).toBe('Bold with resignation');
    expect(component.systemRulesVersion()).toBe('v2-chat-required-resign-banter-spicy');
    component.chatMode = 'OFF';
    expect(component.systemRulesTemplate).toBe('Silent');
    expect(component.systemRulesVersion()).toBe('v2-silent');
    component.chatMode = 'OPTIONAL';
    component.banterLevel = null;
    expect(component.systemRulesTemplate).toBe('Original rules');
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
    component.banterLevel = 'SPICY';

    component.ngOnInit();

    expect(component.chatMode).toBe('OPTIONAL');
    expect(component.banterLevel).toBeNull();
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
        {
          provide: OpenRouterAccess,
          useValue: { keyStatus: () => null, withKey: (action: () => void) => action() },
        },
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
    const banterSelect: HTMLElement = element.querySelector('#banter-level')!;
    expect(banterSelect).toBeTruthy();
    expect(banterSelect.textContent).toContain('Original');
    banterSelect.click();
    fixture.detectChanges();
    await fixture.whenStable();
    const options = Array.from(document.querySelectorAll<HTMLElement>('mat-option'));
    expect(options.map((option) => option.textContent?.trim())).toEqual([
      'Original',
      'Polite',
      'Playful',
      'Spicy',
      'Mind games',
    ]);
    options[3].click();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(fixture.componentInstance.banterLevel).toBe('SPICY');
    expect(element.querySelector('#banter-hint')?.textContent).toContain('no slurs');
    buttons[0].click();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(element.querySelector('#banter-level')).toBeNull();
    expect(fixture.componentInstance.banterLevel).toBe('SPICY');
    buttons[2].click();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(element.querySelector('#chat-mode-hint')?.textContent).toContain('never penalized');
  });

  it('sends the tournament hint setting and previews the matching prompt version', () => {
    const api = createApiService();
    api.getPromptTemplate = () =>
      of({
        template: 'Rules',
        version: 'v2',
        hash: 'hash',
        noHints: { rule: 'Derive the move from the position.', versionSuffix: '-no-hints' },
      });
    const component = new TournamentSetup(api, createRouter(), createRoute());
    component.ngOnInit();
    component.name = 'Hints Arena';

    component.create();
    expect(api.createTournamentPayload?.['legalMoveHints']).toBe(true);
    expect(component.systemRulesTemplate).toBe('Rules');
    expect(component.systemRulesVersion()).toBe('v2');

    component.legalMoveHints = false;
    component.create();
    expect(api.createTournamentPayload?.['legalMoveHints']).toBe(false);
    expect(component.systemRulesTemplate).toBe('Rules\n\nDerive the move from the position.');
    expect(component.systemRulesVersion()).toBe('v2-no-hints');
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
        {
          provide: OpenRouterAccess,
          useValue: { keyStatus: () => null, withKey: (action: () => void) => action() },
        },
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
        {
          provide: OpenRouterAccess,
          useValue: { keyStatus: () => null, withKey: (action: () => void) => action() },
        },
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

  it.each(TOURNAMENT_RECIPES.map((recipe) => [recipe.id, recipe] as const))(
    'the %s recipe pre-fills exactly its documented settings and keeps the rest',
    (_id, recipe) => {
      const api = createApiService();
      const create = vi.spyOn(api, 'createTournament');
      const component = new TournamentSetup(api, createRouter(), createRoute());
      component.name = 'Kept name';
      component.matchupBestOf = 5;
      component.maxOutputTokens = 32768;
      component.sharedCustomInstructions = 'Kept instructions';
      component.legalMoveHints = !recipe.settings.legalMoveHints;
      component.reasoningEffort = 'max';
      component.chatMode = 'OPTIONAL';
      component.banterLevel = 'POLITE';
      component.resignationAllowed = !recipe.settings.resignationAllowed;
      component.adjudicationEnabled = !recipe.settings.adjudicationEnabled;
      component.limitMoveTime = !recipe.settings.limitMoveTime;
      component.maxAttempts.set(7);

      component.applyRecipe(recipe.id);

      const { reasoningEffort, maxAttempts, ...toggles } = recipe.settings;
      expect({
        legalMoveHints: component.legalMoveHints,
        chatMode: component.chatMode,
        banterLevel: component.banterLevel,
        resignationAllowed: component.resignationAllowed,
        adjudicationEnabled: component.adjudicationEnabled,
        limitMoveTime: component.limitMoveTime,
      }).toEqual(toggles);
      expect(component.reasoningEffort).toBe(
        reasoningEffort === 'default' ? null : reasoningEffort,
      );
      expect(component.maxAttempts()).toBe(maxAttempts);
      expect(component.recipeEdited).toBe(false);
      expect([
        component.name,
        component.matchupBestOf,
        component.maxOutputTokens,
        component.sharedCustomInstructions,
      ]).toEqual(['Kept name', 5, 32768, 'Kept instructions']);
      expect(create).not.toHaveBeenCalled();
    },
  );

  it('choosing a recipe never creates; edits after it are sent as edited', async () => {
    const api = createApiService();
    const create = vi.spyOn(api, 'createTournament');
    const fixture = await renderSetup(api);
    const component = fixture.componentInstance;
    component.name = 'Persuasion night';
    component.participants.set([
      pricedParticipant('Alpha', 'a/model', 0, 0),
      { ...pricedParticipant('Beta', 'b/model', 0, 0), seed: 1 },
    ]);
    fixture.detectChanges();
    const select = await TestbedHarnessEnvironment.loader(fixture).getHarness(
      MatSelectHarness.with({ selector: '[aria-labelledby="setup-recipe-heading"] mat-select' }),
    );
    await select.open();
    await select.clickOptions({ text: 'Personality and persuasion' });
    await fixture.whenStable();
    expect(create).not.toHaveBeenCalled();
    const note: HTMLElement = fixture.nativeElement.querySelector('.recipe-note');
    expect(note.textContent).toContain('Mind games chat and resignation on');
    expect(note.querySelector('a')?.getAttribute('href')).toBe(
      'https://github.com/FJrepo/ai-chess-arena/blob/main/docs/benchmarks.md#personality-and-persuasion',
    );

    const optional = Array.from<HTMLButtonElement>(
      fixture.nativeElement.querySelectorAll('.chat-mode-toggle button'),
    ).find((button) => button.textContent?.trim() === 'Optional')!;
    optional.click();
    await fixture.whenStable();
    expect(component.chatMode).toBe('OPTIONAL');
    expect(note.textContent).toContain('Settings changed since you chose this recipe.');
    fixture.nativeElement.querySelector('.create-button').click();
    expect(create).toHaveBeenCalledTimes(1);
    expect(api.createTournamentPayload).toEqual(
      expect.objectContaining({
        chatMode: 'OPTIONAL',
        banterLevel: 'MIND_GAMES',
        resignationAllowed: true,
        adjudicationEnabled: false,
        legalMoveHints: true,
        moveTimeoutSeconds: 0,
        maxAttempts: 3,
      }),
    );
    expect(api.createTournamentPayload).not.toHaveProperty('reasoningEffort');
  });

  it('offers no recipe when rerunning a saved tournament', async () => {
    const api = createApiService();
    Object.assign(api, { getTournament: () => of(rematchSource()) });
    const fixture = await renderSetup(api, 'old');
    expect(fixture.nativeElement.querySelector('#setup-recipe-heading')).toBeNull();
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
        {
          provide: OpenRouterAccess,
          useValue: { keyStatus: () => null, withKey: (action: () => void) => action() },
        },
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

function displayNameInput(element: HTMLElement): HTMLInputElement {
  return element.querySelector<HTMLInputElement>('input[placeholder="e.g. Claude Opus 4.6"]')!;
}

function catalogLoadingStatus(element: HTMLElement): HTMLElement | null {
  return element.querySelector<HTMLElement>('.openrouter-status[role="status"][tabindex="-1"]');
}

function catalogAction(element: HTMLElement, label: string): HTMLButtonElement {
  return Array.from(element.querySelectorAll('button')).find(
    (button) => button.textContent.trim() === label,
  )!;
}

function finishCatalogRequest(
  request: Subject<OpenRouterModelsResponse>,
  result: 'success' | 'failure',
): void {
  if (result === 'failure') {
    request.error({ status: 503 });
  } else {
    request.next({
      data: [catalogModel('acme/model', 'Fixture model'), catalogModel('a/one', 'One')],
    });
    request.complete();
  }
}

function createRefreshApi(request: Subject<OpenRouterModelsResponse>): FakeTournamentSetupApi {
  const api = createStatusApi();
  api.getTournament = () => of(rematchSource());
  vi.mocked(api.getModels)
    .mockReturnValueOnce(of({ data: [catalogModel('b/two', 'Two')] }))
    .mockReturnValueOnce(request);
  return api;
}

function renderCatalogRetry(
  label: 'Retry' | 'Retry model catalog',
  request: Subject<OpenRouterModelsResponse>,
) {
  const api = createStatusApi();
  vi.mocked(api.getModels)
    .mockReturnValueOnce(throwError(() => ({ status: 503 })))
    .mockReturnValueOnce(request);
  if (label === 'Retry model catalog') {
    api.getTournament = () => of(rematchSource());
    return renderSetup(api, 'old');
  }
  return renderSetup(api);
}

function createStatusApi(
  key: OpenRouterKeyStatus = {
    source: 'ui',
    maskedSuffix: '••••-key',
    lastValidatedAt: null,
    validation: 'unknown',
  },
): FakeTournamentSetupApi {
  const api = createApiService(key);
  vi.spyOn(api, 'getModels').mockReturnValue(
    of({
      data: [catalogModel('acme/model', 'Fixture model')],
      totalMatched: 1,
      featuredCount: 0,
      error: null,
    }),
  );
  return api;
}

function createApiService(
  key: OpenRouterKeyStatus = {
    source: 'ui',
    maskedSuffix: '••••-key',
    lastValidatedAt: null,
    validation: 'valid',
  },
): FakeTournamentSetupApi {
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
    checkOpenRouterStatus: () => of({ valid: key.validation === 'valid', key }),
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

function rematchSource(): Tournament {
  return {
    id: 'old',
    name: 'Original',
    format: 'SINGLE_ELIMINATION',
    status: 'COMPLETED',
    drawPolicy: 'HIGHER_SEED_ADVANCES',
    sharedCustomInstructions: 'Shared',
    moveTimeoutSeconds: 120,
    maxAttempts: 9,
    maxOutputTokens: 8192,
    matchupBestOf: 3,
    finalsBestOf: 5,
    chatMode: 'MANDATORY',
    banterLevel: 'PLAYFUL',
    resignationAllowed: true,
    adjudicationEnabled: true,
    reasoningEffort: 'medium',
    openingMode: 'FIXED',
    openingId: 'ruy-lopez',
    maxConcurrentGames: 5,
    budgetUsd: 2.5,
    autoRunStatus: 'FINISHED',
    participants: [
      { id: 'old-p2', playerName: 'Human', controlType: 'HUMAN', seed: 4, modelId: null },
      {
        id: 'old-p1',
        playerName: 'Model',
        controlType: 'AI',
        seed: 3,
        modelId: 'a/one',
        reasoningEffort: 'high',
        customInstructions: 'My instructions',
      },
    ],
    games: [{ id: 'old-game' }],
  } as Tournament;
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

async function renderSetup(
  api = createApiService(),
  sourceId?: string,
  params?: Subject<ReturnType<typeof convertToParamMap>>,
) {
  const paramMap = convertToParamMap(sourceId ? { sourceId } : {});
  TestBed.configureTestingModule({
    imports: [TournamentSetup],
    providers: [
      {
        provide: OpenRouterAccess,
        useValue: { keyStatus: () => null, withKey: (action: () => void) => action() },
      },
      { provide: ApiService, useValue: api },
      provideRouter([]),
      {
        provide: ActivatedRoute,
        useValue: { ...createRoute(), snapshot: { paramMap }, paramMap: params ?? of(paramMap) },
      },
    ],
  });
  vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
  const fixture = TestBed.createComponent(TournamentSetup);
  fixture.detectChanges();
  await fixture.whenStable();
  return fixture;
}
