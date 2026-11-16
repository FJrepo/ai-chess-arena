import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';
import { TournamentSetup } from './tournament-setup';
import { ApiService } from '../../services/api.service';
import { OpenRouterModelOption } from '../../models/openrouter.model';

describe('TournamentSetup', () => {
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
    expect(api.createTournamentPayload?.['maxOutputTokens']).toBe(4096);
  });

  it('sends the chosen max output tokens per answer', () => {
    const api = createApiService();
    const component = new TournamentSetup(api, createRouter(), createRoute());
    component.name = 'Reasoning Arena';
    component.maxOutputTokens = 16384;

    component.create();

    expect(api.createTournamentPayload?.['maxOutputTokens']).toBe(16384);
  });

  it('sends the move time limit in seconds while the limit is on', () => {
    const api = createApiService();
    const component = new TournamentSetup(api, createRouter(), createRoute());
    component.name = 'Timed Arena';
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
    expect(component.maxOutputTokens).toBe(8192);
    expect(component.limitMoveTime).toBe(true);
    expect(component.moveTimeout).toBe(60);
  });

  it('presets switch the move time limit back on with their seconds', () => {
    const component = new TournamentSetup(
      createApiService(),
      createRouter(),
      createRoute({ preset: 'quickstart' }),
    );
    component.limitMoveTime = false;

    component.ngOnInit();

    expect(component.limitMoveTime).toBe(true);
    expect(component.moveTimeout).toBe(30);
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

    component.limitMoveTime = true;
    component.moveTimeout = 60;
    const timed = component.confidenceSummary();
    expect(timed?.maxRuntimeMinutes).toBe(25);
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

  it('allows a single human participant without a model id', () => {
    const component = new TournamentSetup(createApiService(), createRouter(), createRoute());
    component.newPlayerName = 'Human Challenger';
    component.newControlType = 'HUMAN';

    component.addParticipant();

    expect(component.participants()[0].controlType).toBe('HUMAN');
    expect(component.participants()[0].modelId).toBeNull();
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
});

type FakeTournamentSetupApi = ApiService & {
  addedParticipants: Array<Record<string, unknown>>;
  createTournamentPayload: Record<string, unknown> | null;
};

function createApiService(): FakeTournamentSetupApi {
  const api = {
    addedParticipants: [] as Array<Record<string, unknown>>,
    createTournamentPayload: null as Record<string, unknown> | null,
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
