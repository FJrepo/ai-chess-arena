import { TestbedHarnessEnvironment } from '@angular/cdk/testing/testbed';
import { TestBed } from '@angular/core/testing';
import { MatSelectHarness } from '@angular/material/select/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { Observable, Subject, of, throwError } from 'rxjs';
import type {
  BenchmarkRun,
  CreateBenchmarkRequest,
  RerunBenchmarkRequest,
} from '../../models/benchmark.model';
import { ApiService } from '../../services/api.service';
import { BENCHMARK_RECIPES } from '../../utils/benchmark-recipes';
import { Benchmarks } from './benchmarks';

const empty = { wins: 0, draws: 0, losses: 0, forfeits: 0, points: 0, scoredGames: 0 };
const won = { ...empty, wins: 1, points: 1, scoredGames: 1 };
const run: BenchmarkRun = {
  run: {
    id: 'fixture-run',
    name: 'Sample ladder',
    createdAt: '2026-01-01T15:00:00Z',
    status: 'PAUSED',
    pauseReason: 'Budget reached',
    rerunOf: null,
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
    promptVersion: 'v2-silent-no-hints',
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
    rerunBenchmark: vi.fn<(id: string, request: RerunBenchmarkRequest) => Observable<BenchmarkRun>>(
      () => of({ ...run, run: { ...run.run, id: 'candidate-run' } }),
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

function clickButton(page: HTMLElement, label: string) {
  const button = [...page.querySelectorAll('button')].find(
    (element) => element.textContent.trim() === label,
  );
  expect(button).toBeDefined();
  button!.click();
}

const reloadWarning =
  'Reload protection is unavailable in this browser (storage blocked). If you reload before the run appears, a second run could be started. Start anyway?';

const spendCases = [
  { amount: 0.000049, total: '<$0.01', game: '<$0.0001' },
  { amount: 0.004321, total: '<$0.01', game: '$0.0043' },
  { amount: 0.00999, total: '<$0.01', game: '$0.01' },
  { amount: 0.0001, total: '<$0.01', game: '$0.0001' },
  { amount: 0.01, total: '$0.01', game: '$0.01' },
  { amount: 0, total: '$0.00', game: '$0.00' },
  { amount: null, total: '', game: '' },
];

describe('Benchmarks', () => {
  beforeEach(() => sessionStorage.clear());
  afterEach(() => sessionStorage.clear());

  it('validates the create form and starts a run with exact budget and chosen models', () => {
    const { component, api, navigate } = setup();
    api.createBenchmark.mockImplementationOnce(({ clientRequestId, ...details }) => {
      expect(JSON.parse(sessionStorage.getItem('benchmark-pending-submissions') ?? '{}')).toEqual({
        [JSON.stringify(details)]: clientRequestId,
      });
      return of(run);
    });
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

  it.each(BENCHMARK_RECIPES.map((recipe) => [recipe.id, recipe] as const))(
    'the %s recipe pre-fills exactly its documented settings and keeps the rest',
    (_id, recipe) => {
      const { component, api } = setup();
      component.name = 'My run';
      component.modelsText = 'fixture/one';
      component.budget = '3.25';
      component.concurrency = 4;
      component.legalMoveHints = !recipe.settings.legalMoveHints;
      component.reasoningEffort = 'max';
      component.chatMode = 'OPTIONAL';
      component.lowerStrength = 2500;
      component.higherStrength = 3000;
      component.applyRecipe(recipe.id);
      expect(api.createBenchmark).not.toHaveBeenCalled();
      expect(component.recipeEdited).toBe(false);
      component.create();
      expect(api.createBenchmark).toHaveBeenCalledExactlyOnceWith({
        clientRequestId: expect.any(String),
        name: 'My run',
        modelIds: ['fixture/one'],
        ...recipe.settings,
        runSettings: { budgetUsd: '3.25', maxConcurrentGames: 4 },
      });
    },
  );

  it('choosing a recipe in the form fills it without submitting and links to the guide', async () => {
    const { component, fixture, api, navigate } = setup();
    fixture.detectChanges();
    const select = await TestbedHarnessEnvironment.loader(fixture).getHarness(
      MatSelectHarness.with({ selector: '[name="recipe"]' }),
    );
    expect(await select.getValueText()).toBe('None (choose settings yourself)');
    await select.open();
    await select.clickOptions({ text: 'Board understanding' });
    fixture.detectChanges();
    expect(api.createBenchmark).not.toHaveBeenCalled();
    expect(navigate).not.toHaveBeenCalled();
    expect(component.legalMoveHints).toBe(false);
    const note: HTMLElement = fixture.nativeElement.querySelector('.recipe-note');
    expect(note.textContent).toContain('Hints off: models must find legal moves');
    expect(note.querySelector('a')?.getAttribute('href')).toBe(
      'https://github.com/FJrepo/ai-chess-arena/blob/main/docs/benchmarks.md#board-understanding',
    );
  });

  it('keeps every field editable after choosing a recipe and says the settings changed', () => {
    const { component, fixture, api } = setup();
    fillCreateForm(component);
    component.applyRecipe('effort-baseline');
    component.reasoningEffort = 'high';
    component.higherStrength = 2200;
    component.legalMoveHints = false;
    component.chatMode = 'MANDATORY';
    expect(component.recipeEdited).toBe(true);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.recipe-note').textContent).toContain(
      'Settings changed since you chose this recipe.',
    );
    component.applyRecipe('');
    expect(component.recipeEdited).toBe(false);
    expect(component.reasoningEffort).toBe('high');
    component.create();
    expect(api.createBenchmark).toHaveBeenCalledWith(
      expect.objectContaining({
        reasoningEffort: 'high',
        targetStrengths: [1500, 2200],
        legalMoveHints: false,
        chatMode: 'MANDATORY',
      }),
    );
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

  it('asks before creating with storage blocked and retries with the confirmed UUID', () => {
    const storage = vi.spyOn(window, 'sessionStorage', 'get').mockImplementation(() => {
      throw new DOMException('Storage blocked', 'SecurityError');
    });
    const uuid = vi.spyOn(crypto, 'randomUUID');
    try {
      const { component, fixture, api, navigate } = setup();
      api.createBenchmark.mockReturnValueOnce(throwError(() => ({ status: 0 })));
      fillCreateForm(component);
      component.create();
      expect(api.createBenchmark).not.toHaveBeenCalled();
      fixture.detectChanges();
      expect(fixture.nativeElement.textContent).toContain(reloadWarning);
      const pendingId = uuid.mock.results.at(-1)?.value;
      expect(pendingId).toMatch(/^[0-9a-f-]{36}$/);
      clickButton(fixture.nativeElement, 'Start anyway');
      expect(api.createBenchmark).toHaveBeenCalledTimes(1);
      expect(api.createBenchmark.mock.calls[0][0].clientRequestId).toBe(pendingId);
      component.create();
      expect(api.createBenchmark).toHaveBeenCalledTimes(2);
      expect(api.createBenchmark.mock.calls[1][0]).toEqual(api.createBenchmark.mock.calls[0][0]);
      expect(navigate).toHaveBeenCalledWith(['/benchmarks', run.run.id]);
    } finally {
      uuid.mockRestore();
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

  it('folds unchanged rerun settings into a labeled disclosure', () => {
    const { fixture } = setup(run.run.id);
    const page: HTMLElement = fixture.nativeElement;
    fixture.detectChanges();
    clickButton(page, 'Rerun');
    fixture.detectChanges();

    const disclosure = page.querySelector('details:has([id="rerun-unchanged-summary"])');
    expect(disclosure?.querySelector('summary')?.textContent.trim()).toBe('Unchanged settings');
    expect(disclosure?.hasAttribute('open')).toBe(false);
    expect(disclosure?.querySelector('table')?.getAttribute('aria-labelledby')).toBe(
      'rerun-unchanged-summary',
    );
  });

  it('describes the rerun submit button with the planned games and budget cap', () => {
    const { fixture } = setup(run.run.id);
    const page: HTMLElement = fixture.nativeElement;
    fixture.detectChanges();
    clickButton(page, 'Rerun');
    fixture.detectChanges();

    const submit = page.querySelector('button[form="rerun-form"]');
    const description = submit?.getAttribute('aria-describedby');
    expect(description).toBeTruthy();
    expect(page.querySelector(`#${description}`)?.textContent.replace(/\s+/g, ' ').trim()).toBe(
      '4 games · $1.00 budget cap',
    );
  });

  it.each([
    { budget: null, cap: '$1.00' },
    { budget: '2.50', cap: '$2.50' },
  ])('shows and describes the storage-blocked rerun cost ($cap)', async ({ budget, cap }) => {
    const { fixture } = setup(run.run.id);
    const page: HTMLElement = fixture.nativeElement;
    const storage = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('Storage blocked', 'QuotaExceededError');
    });
    try {
      fixture.detectChanges();
      clickButton(page, 'Rerun');
      fixture.detectChanges();
      await fixture.whenStable();
      if (budget !== null) {
        const setting = page.querySelector<HTMLSelectElement>('select[name="rerunSetting"]')!;
        setting.value = 'budgetUsd';
        setting.dispatchEvent(new Event('change'));
        fixture.detectChanges();
        await fixture.whenStable();
        const input = page.querySelector<HTMLInputElement>('input[name="rerunBudget"]')!;
        input.value = budget;
        input.dispatchEvent(new Event('input'));
        fixture.detectChanges();
      }
      clickButton(page, 'Create and start rerun');
      fixture.detectChanges();

      const start = [...page.querySelectorAll('button')].find(
        (button) => button.textContent.trim() === 'Start anyway',
      )!;
      const summaryText = `4 games · ${cap} budget cap`;
      const summary = [...start.parentElement!.querySelectorAll('p')].find(
        (element) => element.textContent.replace(/\s+/g, ' ').trim() === summaryText,
      );
      expect(summary).toBeDefined();
      expect(summary!.closest('[hidden], [inert], details:not([open])')).toBeNull();
      const description = start
        .getAttribute('aria-describedby')!
        .split(/\s+/)
        .map((id) => page.querySelector(`#${id}`)?.textContent)
        .join(' ')
        .replace(/\s+/g, ' ');
      expect(description).toContain(summaryText);
    } finally {
      storage.mockRestore();
    }
  });

  it('asks before posting or reposting after a reload when storage rejects writes', () => {
    const rerunWarning =
      'Browser storage is blocked. Starting another rerun after reloading can create a second paid run.';
    const { fixture, api, component } = setup(run.run.id);
    const storage = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('Storage blocked', 'QuotaExceededError');
    });
    const uuid = vi.spyOn(crypto, 'randomUUID');
    try {
      api.rerunBenchmark.mockReturnValue(throwError(() => ({ status: 0 })));
      const page: HTMLElement = fixture.nativeElement;
      fixture.detectChanges();
      clickButton(page, 'Rerun');
      fixture.detectChanges();
      clickButton(page, 'Create and start rerun');
      expect(api.rerunBenchmark).not.toHaveBeenCalled();
      fixture.detectChanges();
      expect(page.querySelector('#rerun-storage-warning[role="alert"]')?.textContent.trim()).toBe(
        rerunWarning,
      );

      clickButton(page, 'Go back');
      fixture.detectChanges();
      expect(api.rerunBenchmark).not.toHaveBeenCalled();
      expect(page.textContent).not.toContain('A rerun may already have been created');
      expect(page.textContent).not.toContain(rerunWarning);
      clickButton(page, 'Create and start rerun');
      fixture.detectChanges();
      const pendingId = uuid.mock.results.at(-1)?.value;
      expect(pendingId).toMatch(/^[0-9a-f-]{36}$/);
      clickButton(page, 'Start anyway');
      expect(api.rerunBenchmark).toHaveBeenCalledTimes(1);
      const original = api.rerunBenchmark.mock.calls[0][1];
      expect(original.clientRequestId).toBe(pendingId);
      expect(component.pendingRerun()).toEqual(original);

      fixture.detectChanges();
      expect(page.textContent).not.toContain('Create and start rerun');
      clickButton(page, 'Recover rerun');
      expect(api.rerunBenchmark).toHaveBeenCalledTimes(2);
      expect(api.rerunBenchmark).toHaveBeenLastCalledWith(run.run.id, original);
      fixture.destroy();

      const reloaded = TestBed.createComponent(Benchmarks);
      const nextPage: HTMLElement = reloaded.nativeElement;
      reloaded.detectChanges();
      clickButton(nextPage, 'Rerun');
      reloaded.detectChanges();
      clickButton(nextPage, 'Create and start rerun');
      reloaded.detectChanges();
      expect(api.rerunBenchmark).toHaveBeenCalledTimes(2);
      expect(nextPage.textContent).toContain(rerunWarning);
    } finally {
      uuid.mockRestore();
      storage.mockRestore();
    }
  });

  it.each(['Recover rerun', 'Start a separate rerun'])(
    'requires an explicit reload choice after an unconfirmed submission (%s)',
    async (choice) => {
      const { fixture, api, navigate } = setup(run.run.id);
      fixture.detectChanges();
      const page: HTMLElement = fixture.nativeElement;
      clickButton(page, 'Rerun');
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();
      const setting = page.querySelector<HTMLSelectElement>('select[name="rerunSetting"]')!;
      setting.value = 'legalMoveHints';
      setting.dispatchEvent(new Event('change'));
      await fixture.whenStable();
      fixture.detectChanges();
      const diff = page.querySelector('table[aria-label="Rerun settings"]')!;
      const hints = [...diff.querySelectorAll('tr')].find((row) =>
        row.textContent.includes('Legal move hints'),
      );
      expect(hints?.textContent).toMatch(/Off.*On/);
      expect(api.rerunBenchmark).not.toHaveBeenCalled();
      api.rerunBenchmark.mockImplementationOnce((sourceId, request) => {
        expect(JSON.parse(sessionStorage.getItem('benchmark-pending-reruns') ?? '{}')).toEqual({
          [sourceId]: request,
        });
        return throwError(() => ({ status: 0 }));
      });
      clickButton(page, 'Create and start rerun');
      const original = api.rerunBenchmark.mock.calls[0][1];
      expect(original.change).toEqual({ legalMoveHints: true });
      fixture.destroy();

      api.getBenchmark.mockReturnValue(of({ ...run, budgetUsd: '9.00', maxConcurrentGames: 3 }));
      const reloaded = TestBed.createComponent(Benchmarks);
      reloaded.detectChanges();
      const nextPage: HTMLElement = reloaded.nativeElement;
      clickButton(nextPage, 'Rerun');
      reloaded.detectChanges();
      expect(nextPage.textContent).toContain('A rerun may already have been created');
      expect(nextPage.textContent).not.toContain('Create and start rerun');
      expect(api.rerunBenchmark).toHaveBeenCalledTimes(1);

      const error = 'The source run limits changed.';
      api.rerunBenchmark.mockReturnValueOnce(throwError(() => ({ status: 409, error: { error } })));
      clickButton(nextPage, 'Recover rerun');
      reloaded.detectChanges();
      const rerunPanel = nextPage.querySelector('[aria-labelledby="rerun-title"]')!;
      expect(rerunPanel.querySelector('[role="alert"]')?.textContent).toBe(error);
      expect(rerunPanel.textContent).not.toContain('Create and start rerun');
      expect(JSON.parse(sessionStorage.getItem('benchmark-pending-reruns') ?? '{}')).toEqual({
        [run.run.id]: original,
      });
      expect(api.rerunBenchmark).toHaveBeenLastCalledWith(run.run.id, original);

      clickButton(nextPage, choice);
      if (choice === 'Recover rerun') {
        expect(api.rerunBenchmark).toHaveBeenLastCalledWith(run.run.id, original);
      } else {
        reloaded.detectChanges();
        await reloaded.whenStable();
        reloaded.detectChanges();
        expect(api.rerunBenchmark).toHaveBeenCalledTimes(2);
        clickButton(nextPage, 'Create and start rerun');
        const separate = api.rerunBenchmark.mock.calls[2][1];
        expect(separate.clientRequestId).not.toBe(original.clientRequestId);
        expect(separate.change).toEqual({});
        expect(separate.expectedRunSettings).toEqual({ budgetUsd: '9', maxConcurrentGames: 3 });
      }
      expect(api.rerunBenchmark).toHaveBeenCalledTimes(3);
      expect(sessionStorage.getItem('benchmark-pending-reruns')).toBeNull();
      expect(navigate).toHaveBeenCalledWith(['/benchmarks', 'compare'], {
        queryParams: { base: run.run.id, candidate: 'candidate-run' },
      });
    },
  );

  it('renders scores, the incomplete pair, both game links, and spend', () => {
    const { fixture, api, component } = setup(run.run.id);
    fixture.detectChanges();
    const page: HTMLElement = fixture.nativeElement;
    expect(page.textContent).toContain('Performance against this Stockfish setup');
    expect(page.textContent).toContain('1 / 1');
    expect(page.textContent).toContain('Incomplete');
    expect(page.textContent).toContain('$1.01');
    expect(page.querySelector('a[href="/games/white-game"]')?.textContent?.trim()).toBe('Win');
    expect(page.querySelector('a[href="/games/black-game"]')?.textContent?.trim()).toBe('Paused');
    component.refresh();
    expect(api.getBenchmark).toHaveBeenCalledTimes(2);
  });

  it('puts the score and planned sample in a named model results table', () => {
    const { fixture } = setup(run.run.id);
    fixture.detectChanges();
    const page: HTMLElement = fixture.nativeElement;
    const row = page.querySelector(
      'table[aria-label="Performance against this Stockfish setup"] tbody tr',
    );
    expect(row?.querySelector('th[scope="row"]')?.textContent?.trim()).toBe('fixture/model');
    const cells = [...(row?.querySelectorAll('td') ?? [])].map((cell) => cell.textContent.trim());
    expect(cells.slice(0, 5)).toEqual(['1 / 1', '1 / 2', '1', '0', '0']);
  });

  it('names game links with the opening, model ID and color, and Stockfish level', () => {
    const { fixture } = setup(run.run.id);
    fixture.detectChanges();
    const page: HTMLElement = fixture.nativeElement;
    expect(page.querySelector('a[href="/games/white-game"]')?.getAttribute('aria-label')).toBe(
      'Win, French Winawer, fixture/model as White, Stockfish level 1500',
    );
    expect(page.querySelector('a[href="/games/black-game"]')?.getAttribute('aria-label')).toBe(
      'Paused, French Winawer, fixture/model as Black, Stockfish level 1500',
    );
  });

  it.each([
    ['model', '.overview-table tbody td:last-child'],
    ['run', '.run-facts dd'],
    ['game', 'td:has(a[href="/games/white-game"]) + td'],
  ])('distinguishes tiny, zero, and unknown %s spend', (scope, selector) => {
    const { fixture, component } = setup(run.run.id);
    const page: HTMLElement = fixture.nativeElement;
    const displayed = spendCases.map(({ amount }) => {
      const report = structuredClone(run);
      const model = report.models[0];
      for (const item of [report, model, model.pairs[0].games[0]]) {
        Object.assign(item, { spendUsd: amount });
      }
      component.report.set(report);
      fixture.detectChanges();
      const spend = page.querySelector(selector)?.textContent;
      return scope === 'run' ? spend?.split('/')[0].trim() : spend?.trim();
    });

    expect(displayed).toEqual(
      spendCases.map((entry) => (scope === 'game' ? entry.game : entry.total)),
    );
  });

  it('shows one empty marker and its accessible text in an unscored split cell', () => {
    const { fixture } = setup(run.run.id);
    fixture.detectChanges();
    const page: HTMLElement = fixture.nativeElement;
    const cell = page.querySelectorAll('.split-table tbody td')[1];
    const accessibleText = cell.querySelector('.cdk-visually-hidden');
    expect(accessibleText?.textContent).toBe('No scored games');
    expect(accessibleText?.closest('[aria-hidden="true"]')).toBeNull();
    expect(cell.querySelector('[aria-hidden="true"]')?.textContent).toBe('—');
    expect(cell.textContent.trim()).toMatch(/^No scored games\s*—$/);
  });

  it('shows an unplayed game as a quiet Waiting link to its board with a dash for spend', () => {
    const { fixture, component } = setup(run.run.id);
    const report = structuredClone(run);
    Object.assign(report.models[0].pairs[0].games[1], {
      status: 'WAITING',
      statusDetail: null,
      spendUsd: 0,
    });
    component.report.set(report);
    fixture.detectChanges();
    const page: HTMLElement = fixture.nativeElement;
    const result = page.querySelectorAll('.opening-table tbody .game-result')[1];
    expect(result?.textContent.trim()).toBe('Waiting');
    const link = result?.querySelector('a');
    expect(link?.getAttribute('href')).toBe(`/games/${report.models[0].pairs[0].games[1].id}`);
    expect(link?.getAttribute('data-outcome')).toBe('Waiting');
    expect(result?.nextElementSibling?.textContent.trim()).toBe('—');
  });

  it('hides the Pair column when all opening pairs are complete', () => {
    const { fixture, component } = setup(run.run.id);
    const report = structuredClone(run);
    const pair = report.models[0].pairs[0];
    pair.complete = true;
    Object.assign(pair.games[1], { status: 'COMPLETED', result: 'DRAW', statusDetail: null });
    component.report.set(report);
    fixture.detectChanges();
    const page: HTMLElement = fixture.nativeElement;
    const table = page.querySelector<HTMLTableElement>('.opening-table')!;
    expect([...table.tHead!.rows[0].cells].map((cell) => cell.textContent.trim())).toEqual([
      'Opening',
      'Level',
      'Model as White',
      'Model as Black',
    ]);
    expect(table.tBodies[0].rows[0].cells).toHaveLength(6);
  });

  it('shows the Pair column when any opening pair is incomplete', () => {
    const { fixture, component } = setup(run.run.id);
    const report = structuredClone(run);
    const pairs = report.models[0].pairs;
    const complete = structuredClone(pairs[0]);
    complete.id = 'complete-pair';
    complete.complete = true;
    Object.assign(complete.games[1], { status: 'COMPLETED', result: 'DRAW', statusDetail: null });
    pairs.unshift(complete);
    component.report.set(report);
    fixture.detectChanges();
    const page: HTMLElement = fixture.nativeElement;
    const table = page.querySelector<HTMLTableElement>('.opening-table')!;
    expect(table.tHead!.rows[0].lastElementChild?.textContent.trim()).toBe('Pair');
    expect([...table.tBodies[0].rows].map((row) => row.cells.length)).toEqual([7, 7]);
    expect([...table.tBodies[0].rows].map((row) => row.cells[6].textContent.trim())).toEqual([
      '',
      'Incomplete',
    ]);
  });

  it('shows the game resume hint only when a game is paused', () => {
    const { fixture, component } = setup(run.run.id);
    fixture.detectChanges();
    const page: HTMLElement = fixture.nativeElement;
    const hint = 'Open a paused game and choose Resume';
    expect(page.querySelector('.run-management')?.textContent).toContain(hint);

    const report = structuredClone(run);
    report.models[0].pairs[0].games[1].status = 'WAITING';
    component.report.set(report);
    fixture.detectChanges();
    expect(page.querySelector('.run-management')?.textContent).toContain('Resume run');
    expect(page.querySelector('.run-management')?.textContent).not.toContain(hint);
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
