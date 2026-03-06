import { convertToParamMap } from '@angular/router';
import { of } from 'rxjs';
import { TournamentSetup } from './tournament-setup';
import { ApiService } from '../../services/api.service';

describe('TournamentSetup', () => {
  it('adds participant-specific custom instructions when provided', () => {
    const component = new TournamentSetup(createApiService(), createRouter(), createRoute());
    component.newPlayerName = 'Alpha';
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
      { playerName: 'Alpha', modelId: 'model-alpha', seed: 0, customInstructions: null },
      { playerName: 'Beta', modelId: 'model-beta', seed: 1, customInstructions: 'Avoid trades.' },
    ]);

    component.create();

    expect(api.createTournamentPayload?.['sharedCustomInstructions']).toBe(
      'Prefer tactical complications.',
    );
    expect(api.addedParticipants).toHaveLength(2);
    expect(api.addedParticipants[0]['customInstructions']).toBeNull();
    expect(api.addedParticipants[1]['customInstructions']).toBe('Avoid trades.');
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

function pricedParticipant(
  playerName: string,
  modelId: string,
  promptPricePerMillion: number,
  completionPricePerMillion: number,
) {
  return {
    playerName,
    modelId,
    seed: 0,
    customInstructions: null,
    promptPricePerMillion,
    completionPricePerMillion,
  };
}
