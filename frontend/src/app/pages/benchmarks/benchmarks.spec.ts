import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import type { BenchmarkRun } from '../../models/benchmark.model';
import { ApiService } from '../../services/api.service';
import { Benchmarks } from './benchmarks';

const empty = { wins: 0, draws: 0, losses: 0, forfeits: 0, points: 0, scoredGames: 0 };
const won = { ...empty, wins: 1, points: 1, scoredGames: 1 };
const run: BenchmarkRun = {
  run: {
    id: 'fixture-run',
    name: 'Sample ladder',
    createdAt: '2026-01-01T15:00:00',
    status: 'PAUSED',
    pauseReason: 'Budget reached',
  },
  definition: {
    suite: 'stockfish-ladder-v1',
    openings: [{ id: 'french', name: 'French Winawer', moves: ['e4', 'e6', 'd4', 'd5'] }],
    modelIds: ['fixture/model'],
    targetStrengths: [1500, 2000],
    modelColors: ['WHITE', 'BLACK'],
    engineSha256: 'abc'.repeat(21) + 'a',
    searchNodes: 50000,
    threads: 1,
    hashMiB: 16,
  },
  legalMoveHints: false,
  reasoningEffort: 'low',
  chatMode: 'OFF',
  budgetUsd: '1.00',
  maxConcurrentGames: 2,
  spendUsd: 1.01,
  models: [
    {
      modelId: 'fixture/model',
      overall: won,
      splits: [
        { targetStrength: 1500, color: 'WHITE', score: won },
        { targetStrength: 1500, color: 'BLACK', score: empty },
      ],
      incompletePairs: 1,
      spendUsd: 1.01,
      pairs: [
        {
          id: 'fixture-pair',
          openingId: 'french',
          targetStrength: 1500,
          complete: false,
          games: [
            {
              id: 'white-game',
              color: 'WHITE',
              status: 'COMPLETED',
              result: 'WHITE_WINS',
              reason: 'CHECKMATE',
              statusDetail: null,
              spendUsd: 1,
            },
            {
              id: 'black-game',
              color: 'BLACK',
              status: 'PAUSED',
              result: null,
              reason: null,
              statusDetail: 'Budget reached',
              spendUsd: 0.01,
            },
          ],
        },
      ],
    },
  ],
};

function setup(id?: string) {
  const api = {
    listBenchmarks: vi.fn(() => of([run.run])),
    getBenchmark: vi.fn(() => of(run)),
    createBenchmark: vi.fn(() => of(run)),
    pauseBenchmark: vi.fn(() => of(run)),
    resumeBenchmark: vi.fn(() =>
      of({ ...run, budgetUsd: '2.50', run: { ...run.run, status: 'RUNNING', pauseReason: null } }),
    ),
  };
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      { provide: ApiService, useValue: api },
      { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ id })) } },
    ],
  });
  const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
  const fixture = TestBed.createComponent(Benchmarks);
  return { api, fixture, component: fixture.componentInstance, navigate };
}

describe('Benchmarks', () => {
  it('validates the create form and starts a run with exact budget and chosen models', () => {
    const { component, api, navigate } = setup();
    component.name = 'Opening comparison';
    component.modelsText = 'fixture/one\nfixture/one';
    component.budget = '.50';
    component.create();
    expect(component.actionError()).toContain('different');
    component.modelsText = 'fixture/one\nfixture/two';
    component.higherStrength = 1500.5;
    component.create();
    expect(component.actionError()).toContain('whole-number targets');
    expect(api.createBenchmark).not.toHaveBeenCalled();
    component.higherStrength = 2200;
    component.chatMode = 'MANDATORY';
    component.create();
    expect(api.createBenchmark).toHaveBeenCalledWith({
      name: 'Opening comparison',
      modelIds: ['fixture/one', 'fixture/two'],
      targetStrengths: [1500, 2200],
      legalMoveHints: true,
      reasoningEffort: 'default',
      chatMode: 'MANDATORY',
      runSettings: { budgetUsd: '0.5', maxConcurrentGames: 2 },
    });
    expect(navigate).toHaveBeenCalledWith(['/benchmarks', run.run.id]);
  });

  it('renders scores, the incomplete pair, both game links, and spend', () => {
    const { fixture, api, component } = setup(run.run.id);
    fixture.detectChanges();
    const page: HTMLElement = fixture.nativeElement;
    expect(page.textContent).toContain('Performance against this Stockfish setup');
    expect(page.textContent).toContain('1 / 1');
    expect(page.querySelector('[aria-label="No scored games"]')).not.toBeNull();
    expect(page.textContent).toContain('Incomplete');
    expect(page.textContent).toContain('$1.01');
    expect(page.querySelector('a[href="/games/white-game"]')?.textContent?.trim()).toBe('Win');
    expect(page.querySelector('a[href="/games/black-game"]')?.textContent?.trim()).toBe('Paused');
    component.refresh();
    expect(api.getBenchmark).toHaveBeenCalledTimes(2);
  });

  it('preserves the report during a refresh error and resumes with updated limits', () => {
    const { api, component } = setup(run.run.id);
    api.getBenchmark.mockReturnValueOnce(throwError(() => ({ error: { error: 'Unavailable' } })));
    component.refresh();
    expect(component.loadError()).toBe('Unavailable');
    expect(component.report()).toEqual(run);
    component.resumeBudget = '0';
    component.changeRun(run.run, 'resume');
    expect(api.resumeBenchmark).not.toHaveBeenCalled();
    component.resumeBudget = '2.50';
    component.resumeConcurrency = 1;
    component.changeRun(run.run, 'resume');
    expect(api.resumeBenchmark).toHaveBeenCalledWith(run.run.id, {
      budgetUsd: '2.5',
      maxConcurrentGames: 1,
    });
    expect(component.report()?.run.status).toBe('RUNNING');
  });
});
