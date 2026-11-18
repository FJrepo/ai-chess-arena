import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import type {
  BenchmarkComparison,
  BenchmarkComparisonGameSide,
  BenchmarkSummary,
  BenchmarkTelemetry,
} from '../../models/benchmark.model';
import { BenchmarkComparisonPage } from './benchmark-comparison';

const base: BenchmarkSummary = {
  id: 'base',
  name: 'Ladder fixture',
  status: 'FINISHED',
  createdAt: '2026-01-01T15:00:00',
  pauseReason: null,
  rerunOf: null,
};
const candidate: BenchmarkSummary = {
  ...base,
  id: 'candidate',
  name: 'Ladder fixture (rerun)',
  rerunOf: base.id,
};
const telemetry: BenchmarkTelemetry = {
  firstTryMoves: 8,
  turnsSampled: 10,
  firstTryLegalRate: 0.8,
  reasoningTokensPerMove: 0,
  reasoningMovesReported: 9,
};
const win: BenchmarkComparisonGameSide = {
  gameId: 'base-game',
  status: 'COMPLETED',
  reason: 'CHECKMATE',
  statusDetail: null,
  uncertainRequest: false,
  outcome: 'WIN',
  points: 1,
  forfeit: false,
  pairIncomplete: false,
  telemetry,
  spendUsd: 0.1,
};
const loss: BenchmarkComparisonGameSide = {
  ...win,
  gameId: 'candidate-game',
  status: 'FORFEIT',
  reason: 'FORFEIT_INVALID_MOVES',
  outcome: 'LOSS',
  forfeit: true,
  points: 0,
  pairIncomplete: true,
  spendUsd: 0.3,
};
const report = {
  base,
  candidate,
  comparable: true,
  comparisonType: 'CONTROLLED_RERUN',
  differences: [],
  settingChanges: [{ setting: 'Requested effort', base: 'low', candidate: 'high' }],
  operationalLimitsNote: 'Budget and concurrency are operational limits and are not compared',
  baseSpendUsd: 0.1,
  candidateSpendUsd: 0.3,
  spendDeltaUsd: 0.2,
  unmatchedBaseModels: [],
  unmatchedCandidateModels: [],
  models: [
    {
      baseModelId: 'fixture/base',
      candidateModelId: 'fixture/candidate',
      base: {
        telemetry,
        score: { wins: 1, losses: 0, draws: 0, forfeits: 0, points: 1, scoredGames: 1 },
        incompletePairs: 0,
        spendUsd: 0.1,
        resolvedModelIds: [],
      },
      candidate: {
        telemetry,
        score: { wins: 0, losses: 1, draws: 0, forfeits: 1, points: 0, scoredGames: 1 },
        incompletePairs: 1,
        spendUsd: 0.3,
        resolvedModelIds: [],
      },
      matchedGames: 1,
      scoreDelta: -1,
      spendDeltaUsd: 0.2,
    },
  ],
  matchups: [
    {
      baseModelId: 'fixture/base',
      candidateModelId: 'fixture/candidate',
      openingId: 'french',
      openingName: 'French Winawer',
      targetStrength: 1500,
      color: 'WHITE',
      base: win,
      candidate: loss,
      outcomeChanged: true,
      scoreDelta: -1,
      spendDeltaUsd: 0.2,
    },
  ],
} satisfies BenchmarkComparison;

const unchangedMatchup = {
  ...report.matchups[0],
  openingId: 'italian',
  openingName: 'Italian Giuoco Piano',
  base: { ...win, gameId: 'unchanged-base' },
  candidate: { ...win, gameId: 'unchanged-candidate' },
  outcomeChanged: false,
  scoreDelta: 0,
} satisfies BenchmarkComparison['matchups'][number];

function render(data: object) {
  const params = convertToParamMap({ base: base.id, candidate: candidate.id });
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      provideHttpClient(),
      provideHttpClientTesting(),
      { provide: ActivatedRoute, useValue: { queryParamMap: of(params) } },
    ],
  });
  const fixture = TestBed.createComponent(BenchmarkComparisonPage);
  fixture.detectChanges();
  const http = TestBed.inject(HttpTestingController);
  http.expectOne('/api/benchmarks').flush([base, candidate]);
  http
    .expectOne('/api/benchmarks/compare?base=base&candidate=candidate')
    .flush(JSON.parse(JSON.stringify(data)));
  fixture.detectChanges();
  http.verify();
  return fixture.nativeElement as HTMLElement;
}

describe('Benchmark comparison', () => {
  it('labels the score changes summary and gives the signed swing its shared-game denominator', () => {
    const page = render(report);
    const summary = page.querySelector('section[aria-labelledby="score-summary"]');
    expect(summary?.querySelector('h2#score-summary')?.textContent).toBe('Score changes');
    expect(summary?.querySelector('th[scope="col"]:last-child')?.textContent).toBe('Cost delta');
    expect(summary?.textContent?.replace(/\s+/g, ' ')).toMatch(
      /Score delta \(points\).*Lower\s*-1 point\s*over 1 shared game\s*Low sample/,
    );
    expect(page.querySelector('.model-detail thead')?.textContent).toContain(
      'Score rate change (percentage points)',
    );
  });

  it('names each game link with the outcome, run side, model, and matchup', () => {
    const page = render(report);
    const baseLink = page.querySelector('a[href="/games/base-game"]');
    const candidateLink = page.querySelector('a[href="/games/candidate-game"]');
    expect(baseLink?.textContent).toBe('Win');
    expect(baseLink?.getAttribute('aria-label')).toBe(
      'Win, Base run, fixture/base, French Winawer, White, Stockfish level 1,500',
    );
    expect(candidateLink?.textContent).toBe('Forfeit');
    expect(candidateLink?.getAttribute('aria-label')).toBe(
      'Forfeit, Candidate run, fixture/candidate, French Winawer, White, Stockfish level 1,500',
    );
  });

  it('places the changed and other matchup headers in their own row groups', () => {
    const page = render({ ...report, matchups: [...report.matchups, unchangedMatchup] });
    const groups = Array.from(page.querySelectorAll('.matchup-detail tbody'));
    expect(
      groups.map((group) =>
        group.querySelector('th[scope="rowgroup"][colspan="5"]')?.textContent?.trim(),
      ),
    ).toEqual(['Changed outcomes', 'Other matchups']);
    expect(groups[0].querySelector('th[scope="row"]')?.textContent).toContain('French Winawer');
    expect(groups[1].querySelector('th[scope="row"]')?.textContent).toContain(
      'Italian Giuoco Piano',
    );
  });

  it('keeps changed outcomes visible and collapses other matchups behind a counted disclosure', () => {
    const page = render({
      ...report,
      matchups: [
        ...report.matchups,
        unchangedMatchup,
        {
          ...unchangedMatchup,
          color: 'BLACK',
          base: { ...win, gameId: 'unchanged-black-base' },
          candidate: { ...win, gameId: 'unchanged-black-candidate' },
        },
      ],
    });
    const otherLink = page.querySelector('a[href="/games/unchanged-base"]');
    const disclosure = otherLink?.closest('details');
    expect(disclosure?.open).toBe(false);
    expect(disclosure?.querySelector('summary')?.textContent).toContain('Show 2 other matchups');
    expect(page.querySelector('a[href="/games/base-game"]')?.closest('details')).toBeNull();
  });

  it('uses a closed inline disclosure for controlled rerun requirements', () => {
    const page = render(report);
    const requirements = page.querySelector('#controlled-requirements');
    const disclosure = requirements?.closest('details');
    expect(disclosure?.open).toBe(false);
    expect(disclosure?.querySelector('summary')?.textContent).toBe('Controlled rerun requirements');
    expect(getComputedStyle(requirements!).position).not.toBe('absolute');
    expect(page.querySelector('[role="tooltip"]')).toBeNull();
  });

  it('shows settings, paired scores and telemetry, spend delta, and both changed-game links', () => {
    const page = render(report);
    expect(page.textContent).toContain('Controlled rerun');
    expect(page.querySelector('table[aria-label="Run settings changes"]')?.textContent).toMatch(
      /Requested effort.*low.*high/,
    );
    expect(page.textContent).toContain('fixture/candidate');
    expect(page.textContent).toContain('-100 pp');
    expect(page.textContent).toContain('1 matched');
    expect(page.textContent).toContain('80% (8/10)');
    expect(page.textContent).toContain('0 (9 reported)');
    expect(page.textContent).toContain('+$0.20');
    const changedRow = page.querySelector('a[href="/games/base-game"]')?.closest('tr');
    expect(changedRow?.textContent).toContain('French Winawer');
    expect(changedRow?.textContent).toContain('Incomplete pair');
    expect(changedRow?.textContent).toMatch(/First-try legal\s+80% \(8\/10\)/);
    expect(changedRow?.textContent).toMatch(/Reasoning tok\/move\s+0 \(9 reported\)/);
    expect(changedRow?.querySelector('a[href="/games/candidate-game"]')?.textContent).toBe(
      'Forfeit',
    );
  });

  it('shows both incompatible values and suppresses comparative scores and cost deltas', () => {
    const page = render({
      ...report,
      spendDeltaUsd: undefined,
      comparable: false,
      comparisonType: 'NOT_COMPARABLE',
      differences: [{ setting: 'Opening suite', base: 'suite-v1', candidate: 'suite-v2' }],
    });
    const warning = page.querySelector('[role="alert"]');
    expect(warning?.textContent).toContain('Not comparable');
    expect(warning?.textContent).toMatch(/Opening suite.*suite-v1.*suite-v2/);
    expect(page.textContent).not.toContain('By matchup');
    expect(page.textContent).not.toContain('Cost delta');
    expect(page.querySelector('a[href="/games/base-game"]')).toBeNull();
  });

  it('keeps omitted scores and telemetry blank while rendering numeric zeroes', () => {
    const model = { ...report.models[0], scoreDelta: undefined };
    const matchup = { ...report.matchups[0], scoreDelta: undefined };
    const noSamples = {
      firstTryMoves: 0,
      turnsSampled: 0,
      reasoningMovesReported: 0,
    };
    const waiting = {
      gameId: 'waiting-game',
      status: 'WAITING',
      pairIncomplete: true,
      forfeit: false,
      spendUsd: 0,
      telemetry: noSamples,
    };
    const page = render({
      ...report,
      spendDeltaUsd: 0,
      models: [
        {
          ...model,
          matchedGames: 0,
          candidate: { ...model.candidate, telemetry: noSamples },
        },
      ],
      matchups: [{ ...matchup, candidate: waiting, outcomeChanged: false, spendDeltaUsd: 0 }],
    });
    const waitingCell = page.querySelector('a[href="/games/waiting-game"]')?.closest('td');
    expect(waitingCell?.textContent).toMatch(/First-try legal\s*—/);
    expect(waitingCell?.textContent).toMatch(/Reasoning tok\/move\s*—/);
    expect(waitingCell?.textContent).toContain('Incomplete pair');
    expect(page.textContent).not.toContain(' pp');
    expect(page.textContent).toContain('0 (9 reported)');
    expect(page.querySelector('section[aria-labelledby="run-totals"]')?.textContent).toMatch(
      /Cost delta\s*\$0.00/,
    );
  });

  it('uses the tinted error banner state for incompatible runs', () => {
    const fixture = render({
      ...report,
      comparable: false,
      comparisonType: 'NOT_COMPARABLE',
    });
    const state = fixture.querySelector<HTMLElement>('.comparison-state.blocked[role="alert"]')!;
    state.style.setProperty('--app-error-fill', 'mistyrose');
    state.style.setProperty('--mat-sys-error-container', 'firebrick');
    expect(getComputedStyle(state).getPropertyValue('--state-fill').trim()).toBe('mistyrose');
  });

  it('explains the attribution limit and keeps numbers available when several settings differ', () => {
    const page = render({ ...report, comparisonType: 'MULTIPLE_SETTINGS' });
    expect(page.querySelector('.comparison-state.warning')?.textContent).toContain(
      'Multiple settings differ',
    );
    expect(page.textContent).toContain('Differences cannot be attributed to a single setting');
    expect(page.textContent).toContain('-100 pp');
  });

  it('shows the operational limits note from the API and names the playing settings', () => {
    const operationalLimitsNote =
      'Budget and concurrency are operational limits and are not compared';
    const page = render({ ...report, operationalLimitsNote });
    expect(page.textContent).toContain(operationalLimitsNote);
    const definition = page
      .querySelector('[aria-labelledby="setting-changes"]')
      ?.textContent?.replace(/\s+/g, ' ');
    expect(page.querySelector('.comparison-state.info')?.textContent).toContain('Controlled rerun');
    expect(definition).toContain('model IDs, requested effort, legal move hints, chat mode');
    expect(definition).toContain(
      'maximum attempts per move, move time limit, and maximum output tokens',
    );
    expect(definition).toContain(
      'opening suite, seed and lines, Stockfish executable and NNUE weights',
    );
    expect(definition).toContain(
      'search nodes, threads and hash size, Stockfish levels, model colors, and underlying prompt version',
    );
  });

  it('shows the pause reason and warns of a missing charge only for an unresolved request', () => {
    const statusDetail =
      'A model request lost its connection after it was sent. Check spend, then resume.';
    const base = {
      ...win,
      status: 'PAUSED',
      reason: null,
      outcome: null,
      points: null,
      statusDetail,
      uncertainRequest: true,
    };
    const candidate = {
      ...base,
      gameId: loss.gameId,
      statusDetail: 'Paused by operator',
      uncertainRequest: false,
    };
    const page = render({ ...report, matchups: [{ ...report.matchups[0], base, candidate }] });
    const baseCell = page.querySelector('a[href="/games/base-game"]')?.closest('td');
    const candidateCell = page.querySelector('a[href="/games/candidate-game"]')?.closest('td');
    expect(baseCell?.textContent).toContain('Paused');
    expect(baseCell?.textContent).toContain(statusDetail);
    expect(baseCell?.textContent).toContain('Displayed spend may be missing this request’s cost.');
    expect(candidateCell?.textContent).toContain('Paused by operator');
    expect(candidateCell?.textContent).not.toContain('Displayed spend may be missing');
  });

  it('renders the full pause reason as wrapping secondary text without a line clamp', () => {
    const statusDetail =
      'A model request lost its connection after it was sent. Check spend, then resume. The provider may have charged for this request.';
    const matchup = {
      ...report.matchups[0],
      base: { ...win, status: 'PAUSED', statusDetail },
    };
    const page = render({ ...report, matchups: [matchup] });
    const reason = page.querySelector<HTMLElement>('.pause-detail.secondary');
    expect(reason?.textContent?.trim()).toBe(statusDetail);
    expect(['', 'none']).toContain(
      getComputedStyle(reason!).getPropertyValue('-webkit-line-clamp'),
    );
    expect(getComputedStyle(reason!).overflow).not.toBe('hidden');
  });
});
