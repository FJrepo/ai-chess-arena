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
