import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ApiService } from './api.service';
import { BracketDrawMode } from '../models/tournament.model';

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

  it.each([true, false])('loads leaderboard with includeForfeits=%s', (includeForfeits) => {
    const received = vi.fn();
    api.getLeaderboard(includeForfeits).subscribe(received);
    const request = http.expectOne(`/api/analytics/leaderboard?includeForfeits=${includeForfeits}`);
    expect(request.request.method).toBe('GET');
    const response = { includeForfeits, gamesCount: 0, models: [] };
    request.flush(response);
    expect(received).toHaveBeenCalledWith(response);
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
