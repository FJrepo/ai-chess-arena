import { TestBed } from '@angular/core/testing';
import { Observable, of, Subject } from 'rxjs';
import { ActivatedRoute, convertToParamMap, provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { ApiService } from '../../services/api.service';
import {
  AnalyticsComparisonModel,
  AnalyticsComparisonResponse,
  AnalyticsHealthResponse,
  ModelReliabilityResponse,
} from '../../models/analytics.model';
import { AnalyticsDashboard } from './analytics-dashboard';

describe('AnalyticsDashboard', () => {
  it.each([
    {
      tab: 'overview',
      label: 'Overview',
      headings: ['Model Comparison Table', 'Color Split', 'Failure Profile', 'Cost Efficiency'],
    },
    { tab: 'quality', label: 'Play quality', headings: ['Move Quality', 'Conversion'] },
    { tab: 'behaviour', label: 'Behaviour', headings: ['Talkativeness', 'Resignations'] },
    {
      tab: 'cost',
      label: 'Cost & reliability',
      headings: [
        'Per-Model Performance Metrics',
        'Prompt caching',
        'Reliability Ranking & Stability',
      ],
    },
  ])('renders the sections for $label', async ({ tab, label, headings }) => {
    const { harness } = await renderDashboard(`?tab=${tab}`);
    const element = harness.routeNativeElement!;
    const labels = Array.from(element.querySelectorAll('[role="tab"]')).map((node) =>
      node.textContent?.replace(node.querySelector('mat-icon')?.textContent ?? '', '').trim(),
    );
    expect(labels).toEqual(['Overview', 'Play quality', 'Behaviour', 'Cost & reliability']);
    expect(element.querySelector('[role="tab"][aria-selected="true"]')?.textContent).toContain(
      label,
    );
    expect(
      Array.from(element.querySelectorAll('h2')).map((node) => node.textContent?.trim()),
    ).toEqual(headings);
    expect(element.querySelectorAll('h1')).toHaveLength(1);
    expect(element.querySelectorAll('.tab-intro')).toHaveLength(1);
    expect(element.querySelector('.tab-intro')?.textContent?.trim()).not.toBe('');
    if (tab === 'overview') {
      expect(element.querySelectorAll('.leader-card')).toHaveLength(4);
      expect(element.querySelectorAll('.breakdown-grid .breakdown-card')).toHaveLength(3);
      expect(element.querySelectorAll('.comparison-table thead th')).toHaveLength(11);
    }
    if (tab === 'cost') {
      const grid = element.querySelector('.cost-kpi-grid')!;
      expect(grid.querySelectorAll(':scope > .kpi-card')).toHaveLength(6);
      expect(
        Array.from(grid.querySelectorAll('.label')).map((node) => node.textContent?.trim()),
      ).toEqual([
        'Total Cost',
        'Move Volume',
        'Avg Latency',
        'Total Tokens',
        'Trust Index',
        'Low Confidence',
      ]);
      expect(element.querySelector('.kpi-grid, .table-card')).toBe(grid);
      expect(element.querySelectorAll('.kpi-grid')).toHaveLength(1);
      expect(element.querySelector('.table-footnote')?.textContent).toContain('All attempts');
      expect(element.textContent).toContain('Latency is shown but not scored.');
    }
  });

  it('opens Play quality from the tab query parameter', async () => {
    const { harness, component } = await renderDashboard('?tab=quality');
    expect(component.selectedTabIndex()).toBe(1);
    expect(harness.routeNativeElement!.querySelector('.quality-table')).not.toBeNull();
    expect(harness.routeNativeElement!.querySelector('.conversion-table')).not.toBeNull();
  });

  it('updates the tab query parameter with replaceUrl and keeps other parameters', async () => {
    const { harness, api } = await renderDashboard('?tab=quality&scope=sample');
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigate');
    const tabs = harness.routeNativeElement!.querySelectorAll<HTMLElement>('[role="tab"]');
    tabs[2].click();
    harness.detectChanges();
    await harness.fixture.whenStable();
    harness.detectChanges();

    expect(navigate).toHaveBeenCalledExactlyOnceWith([], {
      relativeTo: TestBed.inject(Router).routerState.root.firstChild,
      queryParams: { tab: 'behaviour' },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
    await vi.waitFor(() => {
      expect(router.parseUrl(router.url).queryParams).toEqual({
        tab: 'behaviour',
        scope: 'sample',
      });
    });
    expect(api.getAnalyticsComparison).toHaveBeenCalledTimes(1);
    expect(api.getAnalyticsHealth).toHaveBeenCalledTimes(1);
    expect(api.getAnalyticsReliability).toHaveBeenCalledTimes(1);
  });

  it.each(['', '?tab=unknown', '?tab=QUALITY', '?tab='])(
    'falls back to Overview for a missing or unknown tab (%s)',
    async (query) => {
      const { harness, component } = await renderDashboard(query);
      expect(component.selectedTabIndex()).toBe(0);
      expect(
        harness.routeNativeElement!.querySelector('[role="tab"][aria-selected="true"]')
          ?.textContent,
      ).toContain('Overview');
      expect(harness.routeNativeElement!.querySelector('.comparison-table')).not.toBeNull();
    },
  );

  it('restores selection on query changes and keeps shared filters across tabs', async () => {
    const { harness, component, api } = await renderDashboard();
    component.selectedDays = 7;
    component.selectedTournamentId = 'tournament-sample';
    component.minGames = 3;
    component.onCommonFiltersChanged();
    await harness.navigateByUrl('/analytics?tab=cost');
    await harness.fixture.whenStable();
    harness.detectChanges();

    expect(component.selectedTabIndex()).toBe(3);
    expect(component.selectedDays).toBe(7);
    expect(component.selectedTournamentId).toBe('tournament-sample');
    expect(component.minGames).toBe(3);
    expect(api.getAnalyticsComparison).toHaveBeenLastCalledWith({
      days: 7,
      tournamentId: 'tournament-sample',
      minGames: 3,
    });
    expect(api.getAnalyticsHealth).toHaveBeenLastCalledWith({
      days: 7,
      tournamentId: 'tournament-sample',
    });
    expect(api.getAnalyticsReliability).toHaveBeenLastCalledWith({
      days: 7,
      tournamentId: 'tournament-sample',
      minGames: 3,
    });
    expect(api.getAnalyticsComparison).toHaveBeenCalledTimes(2);
    expect(api.getAnalyticsHealth).toHaveBeenCalledTimes(2);
    expect(api.getAnalyticsReliability).toHaveBeenCalledTimes(2);
    await vi.waitFor(() => {
      harness.detectChanges();
      expect(harness.routeNativeElement!.querySelector('.cache-table')).not.toBeNull();
    });
  });

  it('keeps health and reliability visible when comparison data fails in the merged tab', async () => {
    const { harness, component } = await renderDashboard('?tab=cost');
    component.comparison.set(null);
    component.comparisonError.set('Failed to load model comparison metrics.');
    harness.detectChanges();
    expect(harness.routeNativeElement!.textContent).toContain(
      'Failed to load model comparison metrics.',
    );
    expect(harness.routeNativeElement!.textContent).toContain('Per-Model Performance Metrics');
    expect(harness.routeNativeElement!.textContent).toContain('Reliability Ranking & Stability');
  });

  it.each([
    {
      source: 'health' as const,
      loading: 'Analyzing engine performance...',
      error: 'Failed to load engine health metrics.',
      headings: ['Prompt caching', 'Reliability Ranking & Stability'],
      missingCards: [0, 1, 2, 3],
    },
    {
      source: 'reliability' as const,
      loading: 'Calculating reliability scores...',
      error: 'Failed to load model reliability metrics.',
      headings: ['Per-Model Performance Metrics', 'Prompt caching'],
      missingCards: [4, 5],
    },
    {
      source: 'comparison' as const,
      loading: 'Comparing model results...',
      error: 'Failed to load model comparison metrics.',
      headings: ['Per-Model Performance Metrics', 'Reliability Ranking & Stability'],
      missingCards: [],
    },
  ])(
    'preserves the KPI grid and other tables while $source loads or fails',
    async ({ source, loading, error, headings, missingCards }) => {
      const response = new Subject<never>();
      const { harness } = await renderDashboard('?tab=cost', { [source]: response });
      const element = harness.routeNativeElement!;
      const grid = element.querySelector('.cost-kpi-grid')!;
      const cards = Array.from(grid.querySelectorAll('.kpi-card'));
      expect(cards).toHaveLength(6);
      expect(element.querySelector('.kpi-grid, .loading-state, .table-card')).toBe(grid);
      expect(element.textContent).toContain(loading);
      expect(
        Array.from(element.querySelectorAll('h2')).map((node) => node.textContent?.trim()),
      ).toEqual(headings);
      for (const index of missingCards) {
        expect(cards[index].querySelector('.value')?.textContent).toBe('—');
        expect(cards[index].querySelector('.sub')?.textContent).toContain('Loading...');
      }

      response.error(new Error('Request failed'));
      harness.detectChanges();
      expect(element.textContent).toContain(error);
      expect(element.querySelector('.cost-kpi-grid')).toBe(grid);
      expect(Array.from(grid.querySelectorAll('.kpi-card'))).toEqual(cards);
      for (const index of missingCards) {
        expect(cards[index].querySelector('.sub')?.textContent).toContain('Unavailable');
      }
      expect(
        Array.from(element.querySelectorAll('h2')).map((node) => node.textContent?.trim()),
      ).toEqual(headings);
    },
  );

  it('replaces reliability placeholders when its response arrives without moving the cards', async () => {
    const response = new Subject<ModelReliabilityResponse>();
    const { harness } = await renderDashboard('?tab=cost', { reliability: response });
    const element = harness.routeNativeElement!;
    const cards = Array.from(element.querySelectorAll('.cost-kpi-grid .kpi-card'));

    response.next({ windowDays: 30, tournamentId: null, minGames: 0, models: [] });
    response.complete();
    harness.detectChanges();

    expect(Array.from(element.querySelectorAll('.cost-kpi-grid .kpi-card'))).toEqual(cards);
    expect(cards[4].querySelector('.value')?.textContent).toBe('0.0');
    expect(cards[4].querySelector('.sub')?.textContent).toContain('Average across 0 models');
    expect(cards[5].querySelector('.value')?.textContent).toBe('0');
    expect(element.textContent).not.toContain('Calculating reliability scores...');
    expect(element.textContent).toContain('No reliability data available for current criteria.');
  });

  it('renders conversion counts, coverage and n/a in the selected comparison scope', async () => {
    const response = {
      windowDays: 30,
      tournamentId: null,
      minGames: 0,
      gamesCount: 3,
      models: [
        comparisonRow('model.converted', {
          gamesPlayed: 3,
          conversion: {
            evaluatedGames: 2,
            winningPositionsReached: 2,
            converted: 1,
            adjudicatedWins: 1,
            thrownAway: 1,
            missedMates: 4,
          },
        }),
        comparisonRow('model.unknown'),
        comparisonRow('model.zero', {
          conversion: {
            evaluatedGames: 1,
            winningPositionsReached: 0,
            converted: 0,
            adjudicatedWins: 0,
            thrownAway: 0,
            missedMates: 0,
          },
        }),
      ],
    };
    TestBed.configureTestingModule({
      imports: [AnalyticsDashboard],
      providers: [
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { queryParamMap: of(convertToParamMap({ tab: 'quality' })) },
        },
        {
          provide: ApiService,
          useValue: {
            getTournaments: () => of([]),
            getAnalyticsComparison: () => of(response),
            getAnalyticsHealth: () => of(null),
            getAnalyticsReliability: () => of(null),
          },
        },
      ],
    });
    const fixture = TestBed.createComponent(AnalyticsDashboard);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const element: HTMLElement = fixture.nativeElement;
    const table = element.querySelector('.conversion-table');
    expect(table?.querySelectorAll('thead th')).toHaveLength(6);
    expect(table?.textContent).toContain('Adjudicated wins');
    const rows = Array.from(table?.querySelectorAll('tbody tr') ?? []);
    const converted = rows.find((row) => row.textContent?.includes('model.converted'));
    expect(converted?.querySelector('.table-note')?.textContent).toContain(
      '2 of 3 games evaluated',
    );
    expect(
      Array.from(converted?.querySelectorAll('td.num') ?? []).map((cell) =>
        cell.textContent?.trim(),
      ),
    ).toEqual(['2', '1', '1', '1', '4']);
    const unknown = rows.find((row) => row.textContent?.includes('model.unknown'));
    expect(
      Array.from(unknown?.querySelectorAll('td.num') ?? []).every(
        (cell) => cell.textContent?.trim() === 'n/a',
      ),
    ).toBe(true);
    const zero = rows.find((row) => row.textContent?.includes('model.zero'));
    expect(
      Array.from(zero?.querySelectorAll('td.num') ?? []).every(
        (cell) => cell.textContent?.trim() === '0',
      ),
    ).toBe(true);
    expect(element.querySelector('.conversion-table-card .table-footnote')?.textContent).toContain(
      '≥90%',
    );
    fixture.destroy();
  });

  it('distinguishes unknown conversion counts from observed zero', () => {
    const component = createDashboard();
    expect(component.conversionCount(null)).toBe('n/a');
    expect(component.conversionCount(undefined)).toBe('n/a');
    expect(component.conversionCount(0)).toBe('0');
  });

  it('sorts comparison rows by win rate by default', () => {
    const component = createDashboard();
    component.comparison.set({
      windowDays: 30,
      tournamentId: null,
      minGames: 0,
      gamesCount: 6,
      models: [
        comparisonRow('model.alpha', { winRate: 0.75, wins: 3, gamesPlayed: 4 }),
        comparisonRow('model.beta', { winRate: 0.5, wins: 2, gamesPlayed: 4 }),
      ],
    });

    expect(component.comparisonRows().map((row) => row.modelId)).toEqual([
      'model.alpha',
      'model.beta',
    ]);
  });

  it('sorts comparison rows by first-try legal rate', () => {
    const component = createDashboard();
    component.comparison.set({
      windowDays: 30,
      tournamentId: null,
      minGames: 0,
      gamesCount: 6,
      models: [
        comparisonRow('model.alpha', { winRate: 0.75, firstTryLegalRate: 0.6 }),
        comparisonRow('model.beta', { winRate: 0.5, firstTryLegalRate: 0.95 }),
      ],
    });

    component.comparisonSortBy.set('firstTryLegalRate');

    expect(component.comparisonRows().map((row) => row.modelId)).toEqual([
      'model.beta',
      'model.alpha',
    ]);
    expect(component.comparisonSortOptions.map((option) => option.label)).toContain(
      'First-try legal',
    );
  });

  it('explains the reliability score without latency', () => {
    const component = createDashboard();

    expect(component.scoreExplanation).toBe(
      'Score, based on finished games: 50% first-try legal moves, 30% no forfeits, 20% few retries; low samples pulled toward 60. Latency is shown but not scored.',
    );
  });

  it('picks best value only from models with pricing and wins', () => {
    const component = createDashboard();
    component.comparison.set({
      windowDays: 30,
      tournamentId: null,
      minGames: 0,
      gamesCount: 8,
      models: [
        comparisonRow('model.alpha', {
          wins: 4,
          pricingAvailable: true,
          costPerWinUsd: 0.8,
          totalCostUsd: 3.2,
        }),
        comparisonRow('model.beta', {
          wins: 3,
          pricingAvailable: false,
          costPerWinUsd: null,
          totalCostUsd: null,
        }),
      ],
    });

    const bestValue = component.leaderCards().find((card) => card.label === 'Best Value');

    expect(bestValue?.row?.modelId).toBe('model.alpha');
    expect(bestValue?.value(bestValue.row!)).toBe('$0.80');
  });

  it('shows the score as the most reliable value and only that card carries the grade', () => {
    const component = createDashboard();
    component.comparison.set({
      windowDays: 30,
      tournamentId: null,
      minGames: 0,
      gamesCount: 7,
      models: [comparisonRow('model.alpha', { reliabilityScore: 99.18, gamesPlayed: 7 })],
    });

    const cards = component.leaderCards();
    const mostReliable = cards.find((card) => card.label === 'Most Reliable');

    expect(mostReliable?.value(mostReliable.row!)).toBe('99.2');
    expect(mostReliable?.detail(mostReliable.row!)).toBe('Out of 100, over 7 games');
    expect(cards.filter((card) => card.showsGrade).map((card) => card.label)).toEqual([
      'Most Reliable',
    ]);
  });

  it('rounds the most reliable card and the table score the same way', () => {
    const component = createDashboard();
    component.comparison.set({
      windowDays: 30,
      tournamentId: null,
      minGames: 0,
      gamesCount: 5,
      models: [comparisonRow('model.alpha', { reliabilityScore: 86.55 })],
    });

    const mostReliable = component.leaderCards().find((card) => card.label === 'Most Reliable');

    expect(mostReliable?.value(mostReliable.row!)).toBe('86.6');
    expect(component.formatScore(86.55)).toBe('86.6');
  });

  it('formats latency with thousands separators', () => {
    const component = createDashboard();

    expect(component.formatMs(10196.4)).toBe('10,196 ms');
    expect(component.formatMs(812)).toBe('812 ms');
    expect(component.formatMs(null)).toBe('n/a');
  });

  it('lists move quality for models with judged moves, lowest ACPL first', () => {
    const component = createDashboard();
    component.comparison.set({
      windowDays: 30,
      tournamentId: null,
      minGames: 0,
      gamesCount: 6,
      models: [
        comparisonRow('model.alpha', {
          analyzedMoves: 80,
          analyzedGames: 3,
          averageCentipawnLoss: 92.4,
          blunders: 4,
          blundersPerGame: 1.333333,
        }),
        comparisonRow('model.beta', {
          analyzedMoves: 60,
          analyzedGames: 2,
          averageCentipawnLoss: 35.6,
          blunders: 1,
          blundersPerGame: 0.5,
        }),
        comparisonRow('model.unjudged', {
          analyzedMoves: 0,
          averageCentipawnLoss: null,
          blundersPerGame: null,
        }),
        // An older backend without the fields.
        comparisonRow('model.legacy'),
      ],
    });

    expect(component.qualityRows().map((row) => row.modelId)).toEqual([
      'model.beta',
      'model.alpha',
    ]);
    expect(component.formatAcpl(35.6)).toBe('36');
    expect(component.formatAcpl(null)).toBe('n/a');
    expect(component.formatPerGame(1.333333)).toBe('1.33');
    expect(component.formatPerGame(undefined)).toBe('n/a');
  });

  it('lists talkativeness per chat mode, most talkative first, with n/a for modes without moves', () => {
    const component = createDashboard();
    component.comparison.set({
      windowDays: 30,
      tournamentId: null,
      minGames: 0,
      gamesCount: 4,
      models: [
        comparisonRow('openai.quiet', {
          optionalChat: { moves: 40, messages: 0, talkRate: 0 },
          mandatoryChat: { moves: 0, messages: 0 },
        }),
        comparisonRow('anthropic.chatty', {
          optionalChat: { moves: 38, messages: 38, talkRate: 1, averageMessageLength: 87.4 },
          mandatoryChat: { moves: 20, messages: 19, talkRate: 0.95, averageMessageLength: 64 },
        }),
        comparisonRow('model.mandatory-only', {
          optionalChat: { moves: 0, messages: 0 },
          mandatoryChat: { moves: 10, messages: 7, talkRate: 0.7, averageMessageLength: 120 },
        }),
        // Only moves with chat Off, and an older backend without the fields: not listed.
        comparisonRow('model.silent', {
          optionalChat: { moves: 0, messages: 0 },
          mandatoryChat: { moves: 0, messages: 0 },
        }),
        comparisonRow('model.legacy'),
      ],
    });

    const rows = component.talkRows();
    expect(rows.map((row) => row.modelId)).toEqual([
      'anthropic.chatty',
      'openai.quiet',
      'model.mandatory-only',
    ]);

    const [chatty, quiet, mandatoryOnly] = rows;
    expect(component.formatTalkRate(chatty.optionalChat)).toBe('100.0%');
    expect(component.talkSample(chatty.optionalChat)).toBe('38 of 38 moves');
    expect(component.formatMessageLength(chatty.optionalChat)).toBe('87 chars');
    expect(component.formatTalkRate(chatty.mandatoryChat)).toBe('95.0%');
    expect(component.formatTalkRate(quiet.optionalChat)).toBe('0.0%');
    expect(component.formatMessageLength(quiet.optionalChat)).toBe('n/a');
    expect(component.formatTalkRate(quiet.mandatoryChat)).toBe('n/a');
    expect(component.talkSample(quiet.mandatoryChat)).toBe('');
    expect(component.formatTalkRate(mandatoryOnly.optionalChat)).toBe('n/a');
    expect(component.talkSample({ moves: 1, messages: 1, talkRate: 1 })).toBe('1 of 1 move');
    expect(component.formatTalkRate(undefined)).toBe('n/a');
  });

  it('lists caching metrics, reported zeros and n/a for legacy or incomplete usage', () => {
    const component = createDashboard();
    component.comparison.set({
      windowDays: 30,
      tournamentId: null,
      minGames: 0,
      gamesCount: 3,
      models: [
        comparisonRow('model.legacy'),
        comparisonRow('model.zero', {
          promptCaching: {
            promptTokens: 100,
            cachedPromptTokens: 0,
            cacheHitRate: 0,
            cacheWriteTokens: 0,
            estimatedSavingsUsd: 0,
          },
        }),
        comparisonRow('model.cached', {
          promptCaching: {
            promptTokens: 1000,
            cachedPromptTokens: 800,
            cacheHitRate: 0.8,
            cacheWriteTokens: null,
            estimatedSavingsUsd: 0.000216,
          },
        }),
      ],
    });
    expect(component.cacheRows().map((row) => row.modelId)).toEqual([
      'model.cached',
      'model.zero',
      'model.legacy',
    ]);
    const [cached, zero, legacy] = component.cacheRows();
    expect(component.cacheTokens(cached.promptCaching?.promptTokens)).toBe('1,000');
    expect(component.cacheTokens(cached.promptCaching?.cachedPromptTokens)).toBe('800');
    expect(component.percent(cached.promptCaching?.cacheHitRate ?? null)).toBe('80.0%');
    expect(component.cacheTokens(cached.promptCaching?.cacheWriteTokens)).toBe('n/a');
    expect(component.cacheSavings(cached.promptCaching?.estimatedSavingsUsd)).toBe('$0.000216');
    expect(component.cacheTokens(zero.promptCaching?.cachedPromptTokens)).toBe('0');
    expect(component.percent(zero.promptCaching?.cacheHitRate ?? null)).toBe('0.0%');
    expect(component.cacheSavings(zero.promptCaching?.estimatedSavingsUsd)).toBe('$0.00');
    expect(component.cacheTokens(legacy.promptCaching?.promptTokens)).toBe('n/a');
    expect(component.cacheSavings(null)).toBe('n/a');
    expect(component.cacheSavings(undefined)).toBe('n/a');
    expect(component.cacheExplanation).toContain('All attempts');
    expect(component.cacheExplanation).toContain('finished, non-bye games');
    expect(component.cacheExplanation).toContain('current catalog');
  });

  it('explains talkativeness in one line', () => {
    const component = createDashboard();

    expect(component.talkExplanation).toContain('non-empty message');
    expect(component.talkExplanation).toContain('Mandatory');
    expect(component.talkExplanation).toContain('chat Off are left out');
  });

  it('lists resignations, those while not losing and wins by resignation, with gaps in evaluation', () => {
    const component = createDashboard();
    component.comparison.set({
      windowDays: 30,
      tournamentId: null,
      minGames: 0,
      gamesCount: 6,
      models: [
        comparisonRow('model.winner', { resignations: 0, wonByResignation: 3 }),
        comparisonRow('model.quitter', {
          gamesPlayed: 4,
          resignations: 3,
          resignationsEvaluated: 2,
          resignationsWhileNotLosing: 1,
          wonByResignation: 0,
        }),
        comparisonRow('model.unevaluated', {
          resignations: 1,
          resignationsEvaluated: 0,
          resignationsWhileNotLosing: 0,
        }),
        // No resignations either way, and an older backend without the fields: not listed.
        comparisonRow('model.fighter', { resignations: 0, wonByResignation: 0 }),
        comparisonRow('model.legacy'),
      ],
    });

    const rows = component.resignationRows();
    expect(rows.map((row) => row.modelId)).toEqual([
      'model.quitter',
      'model.unevaluated',
      'model.winner',
    ]);
    const [quitter, unevaluated, winner] = rows;
    expect(component.formatResignations(quitter)).toBe('3 (75.0%)');
    expect(component.formatNotLosing(quitter)).toBe('1 of 2');
    expect(component.notEvaluatedNote(quitter)).toBe('1 not evaluated');
    expect(component.formatNotLosing(unevaluated)).toBe('n/a');
    expect(component.notEvaluatedNote(unevaluated)).toBe('1 not evaluated');
    expect(component.formatResignations(winner)).toBe('0');
    expect(component.formatNotLosing(winner)).toBe('–');
    expect(component.notEvaluatedNote(winner)).toBe('');
  });

  it('explains resignations and "not losing" in one line', () => {
    const component = createDashboard();

    expect(component.resignationExplanation).toContain('35% winning chances');
    expect(component.resignationExplanation).toContain('10th move');
    expect(component.resignationExplanation).toContain('not judged');
  });

  it('explains the move-quality metrics in one line', () => {
    const component = createDashboard();

    expect(component.qualityExplanation).toContain('average centipawn loss');
    expect(component.qualityExplanation).toContain('5 / 10 / 15 percentage points');
    expect(component.qualityExplanation).toContain('Lichess-style');
  });
});

type AnalyticsComparisonRow = {
  modelId: string;
  gamesPlayed: number;
  wins: number;
  draws: number;
  losses: number;
  forfeits: number;
  timeoutForfeits: number;
  winRate: number;
  drawRate: number;
  lossRate: number;
  whiteGames: number;
  whiteWins: number;
  whiteWinRate: number;
  blackGames: number;
  blackWins: number;
  blackWinRate: number;
  movesSampled: number;
  averageResponseTimeMs: number | null;
  averageRetriesPerMove: number;
  firstTryLegalRate: number;
  totalCostUsd: number | null;
  averageCostPerMoveUsd: number | null;
  costPerWinUsd: number | null;
  pricingAvailable: boolean;
  reliabilityScore: number;
  reliabilityBand: string;
  insufficientData: boolean;
};

function comparisonRow(modelId: string, overrides: Partial<AnalyticsComparisonModel> = {}) {
  return { ...baseRow(modelId), ...overrides };
}

function baseRow(modelId: string): AnalyticsComparisonRow {
  return {
    modelId,
    gamesPlayed: 5,
    wins: 2,
    draws: 1,
    losses: 2,
    forfeits: 0,
    timeoutForfeits: 0,
    winRate: 0.4,
    drawRate: 0.2,
    lossRate: 0.4,
    whiteGames: 3,
    whiteWins: 1,
    whiteWinRate: 0.3333,
    blackGames: 2,
    blackWins: 1,
    blackWinRate: 0.5,
    movesSampled: 30,
    averageResponseTimeMs: 1200,
    averageRetriesPerMove: 0.1,
    firstTryLegalRate: 0.9,
    totalCostUsd: 2.5,
    averageCostPerMoveUsd: 0.08,
    costPerWinUsd: 1.25,
    pricingAvailable: true,
    reliabilityScore: 81.3,
    reliabilityBand: 'B',
    insufficientData: false,
  };
}

function createDashboard(): AnalyticsDashboard {
  return new AnalyticsDashboard({} as ApiService, {} as ActivatedRoute, {} as Router);
}

async function renderDashboard(
  query = '',
  responses: {
    comparison?: Observable<AnalyticsComparisonResponse>;
    health?: Observable<AnalyticsHealthResponse>;
    reliability?: Observable<ModelReliabilityResponse>;
  } = {},
) {
  const api = {
    getTournaments: vi.fn(() => of([])),
    getAnalyticsComparison: vi.fn(
      () =>
        responses.comparison ??
        of({
          windowDays: 30,
          tournamentId: null,
          minGames: 0,
          gamesCount: 3,
          models: [comparisonRow('model.sample')],
        }),
    ),
    getAnalyticsHealth: vi.fn(
      () =>
        responses.health ??
        of({
          windowDays: 30,
          tournamentId: null,
          gamesCount: 3,
          activeGamesCount: 0,
          completedGamesCount: 3,
          forfeitGamesCount: 0,
          movesCount: 30,
          retriesTotal: 2,
          averageRetriesPerMove: 0.067,
          averageResponseTimeMs: 1000,
          p95ResponseTimeMs: 1500,
          promptTokensTotal: 1000,
          completionTokensTotal: 100,
          costTotalUsd: 0.01,
          models: [],
        }),
    ),
    getAnalyticsReliability: vi.fn(
      () =>
        responses.reliability ??
        of({ windowDays: 30, tournamentId: null, minGames: 0, models: [] }),
    ),
  };
  TestBed.configureTestingModule({
    providers: [
      provideRouter([{ path: 'analytics', component: AnalyticsDashboard }]),
      { provide: ApiService, useValue: api },
    ],
  });
  const harness = await RouterTestingHarness.create(`/analytics${query}`);
  await harness.fixture.whenStable();
  harness.detectChanges();
  const component = harness.routeDebugElement!.componentInstance as AnalyticsDashboard;
  return { harness, component, api };
}
