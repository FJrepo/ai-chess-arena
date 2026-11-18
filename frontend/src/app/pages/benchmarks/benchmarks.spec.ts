import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { Observable, Subject, of, throwError } from 'rxjs';
import type { BenchmarkRun, CreateBenchmarkRequest } from '../../models/benchmark.model';
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
    engineNetworks: [{ name: 'nn-test.nnue', sha256: 'a'.repeat(64) }],
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
      resolvedModelIds: [],
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
    createBenchmark: vi.fn<(request: CreateBenchmarkRequest) => Observable<BenchmarkRun>>(() =>
      of(run),
    ),
    pauseBenchmark: vi.fn(() => of(run)),
    cancelBenchmark: vi.fn(() =>
      of({ ...run, run: { ...run.run, status: 'CANCELLED', pauseReason: null } }),
    ),
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

function fillCreateForm(component: Benchmarks, budget = '1') {
  component.name = 'Pending ladder';
  component.modelsText = 'fixture/model';
  component.budget = budget;
}

describe('Benchmarks', () => {
  beforeEach(() => sessionStorage.clear());
  afterEach(() => sessionStorage.clear());

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
      clientRequestId: expect.any(String),
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

  it('reuses a failed submission’s request UUID and accepts moving model aliases', () => {
    const { component, api } = setup();
    component.name = 'Alias run';
    component.modelsText = '~anthropic/claude-sonnet-latest';
    component.budget = '1';
    api.createBenchmark.mockReturnValueOnce(throwError(() => ({ status: 503 })));
    component.create();
    component.create();
    expect(api.createBenchmark).toHaveBeenCalledTimes(2);
    const first = api.createBenchmark.mock.calls[0][0];
    expect(first).toMatchObject({
      clientRequestId: expect.stringMatching(/^[0-9a-f-]{36}$/),
      modelIds: ['~anthropic/claude-sonnet-latest'],
    });
    expect(api.createBenchmark.mock.calls[1][0]).toEqual(first);
    component.budget = '2';
    component.create();
    expect(api.createBenchmark.mock.calls[2][0]).not.toMatchObject({
      clientRequestId: first.clientRequestId,
    });
  });

  it.each([0, 408, 503])(
    'reuses the pending UUID after reload following HTTP status %i',
    (status) => {
      const { component, fixture, api, navigate } = setup();
      api.createBenchmark.mockReturnValueOnce(throwError(() => ({ status })));
      fillCreateForm(component);
      component.create();
      const first = api.createBenchmark.mock.calls[0][0];
      fixture.destroy();

      const reloaded = TestBed.createComponent(Benchmarks);
      reloaded.detectChanges();
      expect(reloaded.nativeElement.textContent).toContain(
        'A benchmark creation may already be in progress',
      );
      fillCreateForm(reloaded.componentInstance, '1.00');
      reloaded.componentInstance.create();
      expect(api.createBenchmark.mock.calls[1][0]).toEqual(first);
      expect(navigate).toHaveBeenCalledWith(['/benchmarks', run.run.id]);
      reloaded.destroy();

      const afterSuccess = TestBed.createComponent(Benchmarks);
      afterSuccess.detectChanges();
      expect(afterSuccess.nativeElement.textContent).not.toContain(
        'A benchmark creation may already be in progress',
      );
      fillCreateForm(afterSuccess.componentInstance);
      afterSuccess.componentInstance.create();
      expect(api.createBenchmark.mock.calls[2][0].clientRequestId).not.toBe(first.clientRequestId);
    },
  );

  it('retains an in-flight submission when leaving the page and clears it after a definitive rejection', () => {
    const { component, fixture, api } = setup();
    api.createBenchmark.mockReturnValueOnce(new Subject<BenchmarkRun>());
    fillCreateForm(component);
    component.create();
    const firstId = api.createBenchmark.mock.calls[0][0].clientRequestId;
    fixture.destroy();

    const reloaded = TestBed.createComponent(Benchmarks);
    fillCreateForm(reloaded.componentInstance);
    api.createBenchmark.mockReturnValueOnce(throwError(() => ({ status: 409 })));
    reloaded.componentInstance.create();
    expect(api.createBenchmark.mock.calls[1][0].clientRequestId).toBe(firstId);
    reloaded.destroy();

    const afterRejection = TestBed.createComponent(Benchmarks);
    afterRejection.detectChanges();
    expect(afterRejection.nativeElement.textContent).not.toContain(
      'A benchmark creation may already be in progress',
    );
    fillCreateForm(afterRejection.componentInstance);
    afterRejection.componentInstance.create();
    expect(api.createBenchmark.mock.calls[2][0].clientRequestId).not.toBe(firstId);
  });

  it('keeps a pending UUID when different form values are submitted successfully', () => {
    const { component, fixture, api } = setup();
    api.createBenchmark.mockReturnValueOnce(throwError(() => ({ status: 0 })));
    fillCreateForm(component);
    component.create();
    const firstId = api.createBenchmark.mock.calls[0][0].clientRequestId;
    fillCreateForm(component, '2');
    component.create();
    expect(api.createBenchmark.mock.calls[1][0].clientRequestId).not.toBe(firstId);
    fixture.destroy();

    const reloaded = TestBed.createComponent(Benchmarks);
    fillCreateForm(reloaded.componentInstance);
    reloaded.componentInstance.create();
    expect(api.createBenchmark.mock.calls[2][0].clientRequestId).toBe(firstId);
  });

  it('can retry in memory with session storage blocked', () => {
    const storage = vi.spyOn(window, 'sessionStorage', 'get').mockImplementation(() => {
      throw new DOMException('Storage blocked', 'SecurityError');
    });
    try {
      const { component, api, navigate } = setup();
      api.createBenchmark.mockReturnValueOnce(throwError(() => ({ status: 0 })));
      fillCreateForm(component);
      component.create();
      component.create();
      expect(api.createBenchmark.mock.calls[1][0]).toEqual(api.createBenchmark.mock.calls[0][0]);
      expect(navigate).toHaveBeenCalledWith(['/benchmarks', run.run.id]);
    } finally {
      storage.mockRestore();
    }
  });

  it('confirms cancellation, renders the terminal state, and removes control actions', () => {
    const { fixture, api } = setup(run.run.id);
    const confirm = vi
      .spyOn(window, 'confirm')
      .mockReturnValueOnce(false)
      .mockReturnValueOnce(true);
    try {
      fixture.detectChanges();
      const page: HTMLElement = fixture.nativeElement;
      const cancel = [...page.querySelectorAll('button')].find(
        (b) => b.textContent.trim() === 'Cancel run',
      );
      expect(cancel).toBeDefined();
      cancel?.click();
      expect(api.cancelBenchmark).not.toHaveBeenCalled();
      cancel?.click();
      fixture.detectChanges();
      expect(api.cancelBenchmark).toHaveBeenCalledWith(run.run.id);
      expect(page.textContent).toContain('Cancelled');
      expect(page.textContent).not.toContain('Resume run');
      expect(page.textContent).not.toContain('Cancel run');
    } finally {
      confirm.mockRestore();
    }
  });

  it('renders the requested alias and observed concrete models', () => {
    const { fixture, component } = setup(run.run.id);
    const alias = '~anthropic/claude-sonnet-latest';
    const model = {
      ...run.models[0],
      modelId: alias,
      resolvedModelIds: ['anthropic/claude-sonnet-5'],
    };
    component.report.set({ ...run, models: [model] });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain(alias);
    expect(fixture.nativeElement.textContent).toContain('anthropic/claude-sonnet-5');
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
