import { of } from 'rxjs';
import { TournamentSetup } from './tournament-setup';
import { ApiService } from '../../services/api.service';

describe('TournamentSetup', () => {
  it('adds participant-specific custom instructions when provided', () => {
    const component = new TournamentSetup(createApiService(), createRouter());
    component.newPlayerName = 'Alpha';
    component.newModelId = 'model-alpha';
    component.newCustomInstructions = 'Play aggressively.';

    component.addParticipant();

    expect(component.participants()[0].customInstructions).toBe('Play aggressively.');
  });

  it('creates tournament with shared custom instructions and participant fallback behavior', () => {
    const api = createApiService();
    const component = new TournamentSetup(api, createRouter());

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
