import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Observable } from 'rxjs';
import { ApiService, OpenRouterStatusResponse, SystemStatusResponse } from './api.service';
import { BracketDrawMode } from '../models/tournament.model';
import { OpenRouterClient, OpenRouterKeyStatus } from './openrouter-client';

describe('ApiService bracket draw', () => {
  let api: ApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(ApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it.each(['pgn', 'csv', 'json'] as const)('downloads tournament %s as a blob', (format) => {
    const received = vi.fn();
    const blob = new Blob(['export'], { type: 'application/octet-stream' });
    api.getTournamentExport('cup', format).subscribe(received);
    const request = http.expectOne(`/api/tournaments/cup/export.${format}`);
    expect(request.request.method).toBe('GET');
    expect(request.request.responseType).toBe('blob');
    request.flush(blob);
    expect(received).toHaveBeenCalledWith(blob);
  });

  it('keeps the single-game PGN endpoint and reads it as text', () => {
    const received = vi.fn();
    api.getGamePgn('game').subscribe(received);
    const request = http.expectOne('/api/games/game/pgn');
    expect(request.request.responseType).toBe('text');
    request.flush('[Event "Arena"]\n\n*');
    expect(received).toHaveBeenCalledWith('[Event "Arena"]\n\n*');
  });

  it('starts without a queue parameter and relies on the server queuing a full run by default', () => {
    const received = vi.fn();
    api.startGame('game').subscribe(received);
    const request = http.expectOne('/api/games/game/start');
    expect(request.request.method).toBe('POST');
    expect(request.request.params.keys()).toEqual([]);
    const queued = { status: 'PAUSED', resumeRequestedAt: '2026-11-17T20:00:00Z' };
    request.flush(queued);
    expect(received).toHaveBeenCalledWith(queued);
  });

  it('resumes after a spend check by acknowledging exactly the displayed request', () => {
    const received = vi.fn();
    api.resumeAfterSpendCheck('game', '2026-11-18T10:00:00.123456Z').subscribe(received);
    const request = http.expectOne(
      (req) =>
        req.url === '/api/games/game/start' &&
        req.params.get('acknowledgeUncertainRequest') === '2026-11-18T10:00:00.123456Z',
    );
    expect(request.request.method).toBe('POST');
    const resumed = { status: 'IN_PROGRESS', uncertainRequest: false };
    request.flush(resumed);
    expect(received).toHaveBeenCalledWith(resumed);
  });

  it('deletes the displayed revision of a queued resume and returns the current game', () => {
    const received = vi.fn();
    api.cancelResumeRequest('game', 5).subscribe(received);
    const request = http.expectOne('/api/games/game/resume-request?statusRevision=5');
    expect(request.request.method).toBe('DELETE');
    const response = { status: 'PAUSED', statusDetail: 'Answer cut off' };
    request.flush(response);
    expect(received).toHaveBeenCalledWith(response);
  });

  it.each([true, false])('loads leaderboard with includeForfeits=%s', (includeForfeits) => {
    const received = vi.fn();
    api.getLeaderboard(includeForfeits).subscribe(received);
    const request = http.expectOne(`/api/analytics/leaderboard?includeForfeits=${includeForfeits}`);
    expect(request.request.method).toBe('GET');
    const response = { includeForfeits, gamesCount: 0, models: [] };
    request.flush(response);
    expect(received).toHaveBeenCalledWith(response);
  });

  it('retains both analytics filters when legal move hints is false', () => {
    api.getAnalyticsComparison({ legalMoveHints: false, reasoningEffort: 'high' }).subscribe();
    const request = http.expectOne(({ url }) => url === '/api/analytics/comparison');
    expect(request.request.params.get('legalMoveHints')).toBe('false');
    expect(request.request.params.get('reasoningEffort')).toBe('high');
    request.flush({ gamesCount: 0, models: [] });
  });

  it('includes forfeits by default on the leaderboard', () => {
    api.getLeaderboard().subscribe();
    http.expectOne('/api/analytics/leaderboard?includeForfeits=true').flush({
      includeForfeits: true,
      gamesCount: 0,
      models: [],
    });
  });

  it('sends the run, edit and pause API contracts', () => {
    const settings = { maxConcurrentGames: 3, budgetUsd: null };
    api.runTournament('cup', settings).subscribe();
    const run = http.expectOne('/api/tournaments/cup/run');
    expect(run.request.method).toBe('POST');
    expect(run.request.body).toEqual(settings);
    run.flush({});
    api.updateTournamentRun('cup', { ...settings, budgetUsd: 5 }).subscribe();
    const edit = http.expectOne('/api/tournaments/cup/run');
    expect(edit.request.method).toBe('PUT');
    expect(edit.request.body).toEqual({ ...settings, budgetUsd: 5 });
    edit.flush({});
    api.pauseTournamentRun('cup').subscribe();
    const pause = http.expectOne('/api/tournaments/cup/run/pause');
    expect(pause.request.method).toBe('POST');
    expect(pause.request.body).toEqual({});
    pause.flush({});
  });

  it('loads the authored opening catalog', () => {
    const lines = [
      {
        id: 'italian',
        name: 'Italian Game',
        eco: 'C50',
        moves: ['e4', 'e5', 'Nf3', 'Nc6', 'Bc4', 'Bc5'],
      },
    ];
    const received = vi.fn();
    api.getOpenings().subscribe(received);
    const request = http.expectOne('/api/openings');
    expect(request.request.method).toBe('GET');
    request.flush(lines);
    expect(received).toHaveBeenCalledWith(lines);
  });

  it.each([undefined, 'ORDER', 'RANDOM'] as const)(
    'sends drawMode %s, defaulting to ORDER for existing callers',
    (mode: BracketDrawMode | undefined) => {
      api.generateBracket('cup', mode).subscribe();

      const request = http.expectOne(
        `/api/tournaments/cup/generate-bracket?drawMode=${mode ?? 'ORDER'}`,
      );
      expect(request.request.method).toBe('POST');
      expect(request.request.body).toEqual({});
      request.flush([]);
    },
  );
});

describe.each(['openrouter-status', 'system-status'] as const)(
  'ApiService %s connection reconciliation',
  (endpoint) => {
    let api: ApiService;
    let connection: OpenRouterClient;
    let http: HttpTestingController;
    const missingKey: OpenRouterKeyStatus = {
      source: 'none',
      maskedSuffix: null,
      lastValidatedAt: null,
      validation: 'none',
    };
    const connectedKey: OpenRouterKeyStatus = {
      source: 'ui',
      maskedSuffix: 'test',
      lastValidatedAt: '2026-11-17T12:00:00Z',
      validation: 'valid',
    };
    const url = `/api/config/${endpoint}`;
    const readStatus = (): Observable<OpenRouterStatusResponse | SystemStatusResponse> =>
      endpoint === 'openrouter-status' ? api.checkOpenRouterStatus() : api.getSystemStatus();
    const response = (key: OpenRouterKeyStatus) =>
      endpoint === 'openrouter-status'
        ? { valid: key.validation === 'valid', key }
        : {
            backendVersion: 'fixture-version',
            openRouterValid: key.validation === 'valid',
            key,
            stockfishAvailable: true,
            stockfishReason: null,
            checkedAt: '2026-11-17T12:00:00Z',
          };

    beforeEach(() => {
      TestBed.configureTestingModule({
        providers: [provideHttpClient(), provideHttpClientTesting()],
      });
      api = TestBed.inject(ApiService);
      connection = TestBed.inject(OpenRouterClient);
      http = TestBed.inject(HttpTestingController);
    });

    afterEach(() => http.verify());

    it.each([
      ['callback', 'complete', (client: OpenRouterClient) => client.complete('code', 'state')],
      ['manual save', 'key', (client: OpenRouterClient) => client.manual('fixture-private-key')],
      ['delete', 'key', (client: OpenRouterClient) => client.delete()],
      ['test', 'test', (client: OpenRouterClient) => client.test()],
      ['claim', 'claim', (client: OpenRouterClient) => client.claim('fixture-token')],
    ] as const)(
      'preserves a successful %s when an older status GET resolves afterwards',
      (operation, path, mutate) => {
        const initialKey =
          operation === 'delete' || operation === 'test' ? connectedKey : missingKey;
        const nextKey =
          operation === 'delete'
            ? missingKey
            : operation === 'test'
              ? { ...connectedKey, validation: 'invalid' as const }
              : connectedKey;
        connection.reconcileKeyStatus(initialKey);
        const received = vi.fn();
        readStatus().subscribe(received);
        const staleRequest = http.expectOne(url);

        mutate(connection).subscribe();
        http.expectOne(`/api/config/openrouter/${path}`).flush({ key: nextKey, canManage: true });
        expect(connection.keyStatus()).toEqual(nextKey);

        const staleResponse = response(initialKey);
        staleRequest.flush(staleResponse);
        expect(received).toHaveBeenCalledExactlyOnceWith(staleResponse);
        expect(connection.keyStatus()).toEqual(nextKey);
      },
    );

    it('captures the generation at each subscription, including reuse of the same observable', () => {
      const status = readStatus();
      status.subscribe();
      const staleRequest = http.expectOne(url);
      connection.manual('fixture-private-key').subscribe();
      http.expectOne('/api/config/openrouter/key').flush({ key: connectedKey, canManage: true });
      staleRequest.flush(response(missingKey));
      expect(connection.keyStatus()).toEqual(connectedKey);

      // The observable was created before the mutation; this request starts afterwards.
      status.subscribe();
      http.expectOne(url).flush(response(missingKey));
      expect(connection.keyStatus()).toEqual(missingKey);
    });

    it('still reconciles pending status reads after a failed mutation or another status response', () => {
      connection.reconcileKeyStatus(connectedKey);
      readStatus().subscribe();
      const pendingRequest = http.expectOne(url);
      connection.manual('fixture-private-key').subscribe({ error: () => {} });
      http
        .expectOne('/api/config/openrouter/key')
        .flush({ error: 'Rejected fixture' }, { status: 400, statusText: 'Bad Request' });
      readStatus().subscribe();
      http.expectOne(url).flush(response(connectedKey));

      pendingRequest.flush(response(missingKey));
      expect(connection.keyStatus()).toEqual(missingKey);
    });
  },
);
