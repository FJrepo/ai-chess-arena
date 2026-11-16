import { Component, OnDestroy, OnInit, signal, computed } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { MatTabsModule } from '@angular/material/tabs';
import { CurrencyPipe, DatePipe, DecimalPipe, formatNumber } from '@angular/common';
import { ApiService } from '../../services/api.service';
import { Tournament } from '../../models/tournament.model';
import {
  AnalyticsComparisonModel,
  AnalyticsComparisonResponse,
  AnalyticsHealthResponse,
  AnalyticsTalkativeness,
  ModelReliabilityResponse,
} from '../../models/analytics.model';

type ComparisonSortOption =
  | 'winRate'
  | 'reliabilityScore'
  | 'firstTryLegalRate'
  | 'averageResponseTimeMs'
  | 'costPerWinUsd'
  | 'forfeits';

@Component({
  selector: 'app-analytics-dashboard',
  standalone: true,
  imports: [
    FormsModule,
    MatButtonModule,
    MatCardModule,
    MatChipsModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressSpinnerModule,
    MatSelectModule,
    MatTabsModule,
    CurrencyPipe,
    DatePipe,
    DecimalPipe,
  ],
  templateUrl: './analytics-dashboard.html',
  styleUrl: './analytics-dashboard.scss',
})
export class AnalyticsDashboard implements OnInit, OnDestroy {
  readonly dayOptions = [7, 30, 90];
  readonly scoreExplanation =
    'Score, based on finished games: 50% first-try legal moves, 30% no forfeits, 20% few retries; low samples pulled toward 60. Latency is shown but not scored.';
  readonly qualityExplanation =
    "ACPL is the average centipawn loss per move (lower is better). A move is ?! / ? / ?? when it drops the mover's winning chances by at least 5 / 10 / 15 percentage points (Lichess-style win-probability model); forced moves, overrides and moves without an evaluation are not judged.";
  readonly talkExplanation =
    "Talk rate is the share of a model's moves in finished games that came with a non-empty message: with chat Optional it is the model's own choice, with chat Mandatory it shows how often it complied. Average length is in characters per message; moves made with chat Off are left out.";
  readonly comparisonSortOptions = [
    { value: 'winRate', label: 'Win Rate' },
    { value: 'reliabilityScore', label: 'Reliability' },
    { value: 'firstTryLegalRate', label: 'First-try legal' },
    { value: 'averageResponseTimeMs', label: 'Latency' },
    { value: 'costPerWinUsd', label: 'Cost / Win' },
    { value: 'forfeits', label: 'Fewest Forfeits' },
  ] as const satisfies ReadonlyArray<{ value: ComparisonSortOption; label: string }>;

  selectedDays = 30;
  selectedTournamentId = '';
  minGames = 0;
  comparisonSortBy = signal<ComparisonSortOption>('winRate');

  tournaments = signal<Tournament[]>([]);

  comparison = signal<AnalyticsComparisonResponse | null>(null);
  health = signal<AnalyticsHealthResponse | null>(null);
  reliability = signal<ModelReliabilityResponse | null>(null);

  comparisonLoading = signal(false);
  healthLoading = signal(false);
  reliabilityLoading = signal(false);

  comparisonError = signal<string | null>(null);
  healthError = signal<string | null>(null);
  reliabilityError = signal<string | null>(null);

  lastUpdated = signal<Date | null>(null);

  comparisonRows = computed(() => {
    const rows = [...(this.comparison()?.models ?? [])];
    const sortBy = this.comparisonSortBy();

    return rows.sort((a, b) => this.compareRows(a, b, sortBy));
  });

  leaderCards = computed(() => {
    const rows = this.comparisonRows();
    return [
      {
        label: 'Strongest',
        showsGrade: false,
        description: 'Highest win rate in the current sample',
        row: this.pickBest(
          rows,
          (row) => row.winRate,
          (row) => row.wins,
          true,
        ),
        value: (row: AnalyticsComparisonModel) => this.percent(row.winRate),
        detail: (row: AnalyticsComparisonModel) => `${row.wins}-${row.draws}-${row.losses}`,
      },
      {
        label: 'Most Reliable',
        description: 'Most first-try legal moves, fewest forfeits and retries',
        row: this.pickBest(
          rows,
          (row) => row.reliabilityScore,
          (row) => row.gamesPlayed,
          true,
        ),
        showsGrade: true,
        value: (row: AnalyticsComparisonModel) => this.formatScore(row.reliabilityScore),
        detail: (row: AnalyticsComparisonModel) =>
          `Out of 100, over ${row.gamesPlayed} ${row.gamesPlayed === 1 ? 'game' : 'games'}`,
      },
      {
        label: 'Fastest',
        showsGrade: false,
        description: 'Lowest average move latency',
        row: this.pickBest(
          rows,
          (row) => row.averageResponseTimeMs,
          (row) => row.movesSampled,
          false,
        ),
        value: (row: AnalyticsComparisonModel) => this.formatMs(row.averageResponseTimeMs),
        detail: (row: AnalyticsComparisonModel) => `${row.movesSampled} sampled moves`,
      },
      {
        label: 'Best Value',
        showsGrade: false,
        description: 'Lowest cost per win when pricing exists',
        row: this.pickBest(
          rows.filter((row) => row.pricingAvailable && row.costPerWinUsd !== null && row.wins > 0),
          (row) => row.costPerWinUsd,
          (row) => row.wins,
          false,
        ),
        value: (row: AnalyticsComparisonModel) => this.currency(row.costPerWinUsd),
        detail: (row: AnalyticsComparisonModel) => `${this.currency(row.totalCostUsd)} total spend`,
      },
    ];
  });

  /** Models with judged moves, lowest average centipawn loss first. */
  qualityRows = computed(() =>
    (this.comparison()?.models ?? [])
      .filter((row) => (row.analyzedMoves ?? 0) > 0 && row.averageCentipawnLoss != null)
      .slice()
      .sort(
        (a, b) =>
          (a.averageCentipawnLoss ?? 0) - (b.averageCentipawnLoss ?? 0) ||
          (b.analyzedMoves ?? 0) - (a.analyzedMoves ?? 0),
      ),
  );

  /** Models with moves made with chat Optional or Mandatory, most talkative first. */
  talkRows = computed(() =>
    (this.comparison()?.models ?? [])
      .filter((row) => (row.optionalChat?.moves ?? 0) > 0 || (row.mandatoryChat?.moves ?? 0) > 0)
      .slice()
      .sort(
        (a, b) =>
          talkRateOrder(b.optionalChat) - talkRateOrder(a.optionalChat) ||
          talkRateOrder(b.mandatoryChat) - talkRateOrder(a.mandatoryChat) ||
          a.modelId.localeCompare(b.modelId),
      ),
  );

  colorSplitHighlights = computed(() =>
    this.comparisonRows()
      .filter((row) => row.gamesPlayed > 0)
      .slice()
      .sort((a, b) => b.whiteWinRate - b.blackWinRate - (a.whiteWinRate - a.blackWinRate))
      .slice(0, 3),
  );

  failureWatch = computed(() =>
    this.comparisonRows()
      .filter((row) => row.gamesPlayed > 0)
      .slice()
      .sort((a, b) => a.forfeits - b.forfeits || a.losses - b.losses)
      .slice(0, 3),
  );

  costEfficiencyHighlights = computed(() =>
    this.comparisonRows()
      .filter((row) => row.pricingAvailable && row.costPerWinUsd !== null && row.wins > 0)
      .slice()
      .sort((a, b) => (a.costPerWinUsd ?? Number.MAX_VALUE) - (b.costPerWinUsd ?? Number.MAX_VALUE))
      .slice(0, 3),
  );

  reliabilityAverageScore = computed(() => {
    const models = this.reliability()?.models ?? [];
    if (models.length === 0) return 0;
    return models.reduce((sum, model) => sum + model.finalScore, 0) / models.length;
  });

  reliabilityLowConfidenceCount = computed(() => {
    return (this.reliability()?.models ?? []).filter((m) => m.insufficientData).length;
  });

  private refreshTimer: ReturnType<typeof setInterval> | null = null;

  constructor(private api: ApiService) {}

  ngOnInit(): void {
    this.loadTournaments();
    this.refreshAll();
    this.startAutoRefresh();
  }

  ngOnDestroy(): void {
    if (this.refreshTimer) {
      clearInterval(this.refreshTimer);
    }
  }

  onCommonFiltersChanged(): void {
    this.refreshAll();
  }

  onReliabilityFiltersChanged(): void {
    this.loadReliability();
  }

  manualRefresh(): void {
    this.refreshAll();
  }

  /** One formatter for every reliability score, so the leader card and the tables agree. */
  formatScore(value: number): string {
    return formatNumber(value, 'en-US', '1.1-1');
  }

  /** Average centipawn loss, rounded to whole centipawns. */
  formatAcpl(value: number | null | undefined): string {
    return value == null ? 'n/a' : formatNumber(value, 'en-US', '1.0-0');
  }

  formatPerGame(value: number | null | undefined): string {
    return value == null ? 'n/a' : formatNumber(value, 'en-US', '1.2-2');
  }

  /** Share of moves with a message; n/a without moves in that chat mode. */
  formatTalkRate(talk: AnalyticsTalkativeness | null | undefined): string {
    return !talk || talk.moves === 0 || talk.talkRate == null ? 'n/a' : this.percent(talk.talkRate);
  }

  /** "12 of 40 moves"; empty without moves in that chat mode. */
  talkSample(talk: AnalyticsTalkativeness | null | undefined): string {
    if (!talk || talk.moves === 0) {
      return '';
    }
    return `${talk.messages} of ${talk.moves} ${talk.moves === 1 ? 'move' : 'moves'}`;
  }

  /** Average message length in characters; n/a without messages. */
  formatMessageLength(talk: AnalyticsTalkativeness | null | undefined): string {
    return talk?.averageMessageLength == null
      ? 'n/a'
      : `${formatNumber(talk.averageMessageLength, 'en-US', '1.0-0')} chars`;
  }

  formatMs(value: number | null): string {
    if (value === null || value === undefined) {
      return 'n/a';
    }
    return `${Math.round(value).toLocaleString('en-US')} ms`;
  }

  private refreshAll(): void {
    this.loadComparison();
    this.loadHealth();
    this.loadReliability();
  }

  private startAutoRefresh(): void {
    this.refreshTimer = setInterval(() => {
      this.refreshAll();
    }, 15000);
  }

  private currentTournamentFilter(): string | null {
    return this.selectedTournamentId.trim().length > 0 ? this.selectedTournamentId : null;
  }

  private loadTournaments(): void {
    this.api.getTournaments().subscribe({
      next: (tournaments) => this.tournaments.set(tournaments),
      error: () => this.tournaments.set([]),
    });
  }

  private loadHealth(): void {
    this.healthLoading.set(true);
    this.healthError.set(null);

    this.api
      .getAnalyticsHealth({
        days: this.selectedDays,
        tournamentId: this.currentTournamentFilter(),
      })
      .subscribe({
        next: (response) => {
          this.health.set(response);
          this.lastUpdated.set(new Date());
        },
        error: () => {
          this.healthError.set('Failed to load engine health metrics.');
        },
        complete: () => this.healthLoading.set(false),
      });
  }

  private loadComparison(): void {
    this.comparisonLoading.set(true);
    this.comparisonError.set(null);

    this.api
      .getAnalyticsComparison({
        days: this.selectedDays,
        tournamentId: this.currentTournamentFilter(),
        minGames: Math.max(0, this.minGames),
      })
      .subscribe({
        next: (response) => {
          this.comparison.set(response);
          this.lastUpdated.set(new Date());
        },
        error: () => {
          this.comparisonError.set('Failed to load model comparison metrics.');
        },
        complete: () => this.comparisonLoading.set(false),
      });
  }

  private loadReliability(): void {
    this.reliabilityLoading.set(true);
    this.reliabilityError.set(null);

    this.api
      .getAnalyticsReliability({
        days: this.selectedDays,
        tournamentId: this.currentTournamentFilter(),
        minGames: Math.max(0, this.minGames),
      })
      .subscribe({
        next: (response) => {
          this.reliability.set(response);
          this.lastUpdated.set(new Date());
        },
        error: () => {
          this.reliabilityError.set('Failed to load model reliability metrics.');
        },
        complete: () => this.reliabilityLoading.set(false),
      });
  }

  private pickBest(
    rows: AnalyticsComparisonModel[],
    primary: (row: AnalyticsComparisonModel) => number | null,
    secondary: (row: AnalyticsComparisonModel) => number,
    desc: boolean,
  ): AnalyticsComparisonModel | null {
    if (rows.length === 0) {
      return null;
    }

    return rows.slice().sort((a, b) => {
      const aValue = primary(a);
      const bValue = primary(b);
      if (aValue === null && bValue === null) return secondary(b) - secondary(a);
      if (aValue === null) return 1;
      if (bValue === null) return -1;
      const diff = desc ? bValue - aValue : aValue - bValue;
      return diff !== 0 ? diff : secondary(b) - secondary(a);
    })[0];
  }

  private compareRows(
    a: AnalyticsComparisonModel,
    b: AnalyticsComparisonModel,
    sortBy: ComparisonSortOption,
  ): number {
    switch (sortBy) {
      case 'reliabilityScore':
        return (
          b.reliabilityScore - a.reliabilityScore ||
          b.gamesPlayed - a.gamesPlayed ||
          b.winRate - a.winRate
        );
      case 'firstTryLegalRate':
        return (
          b.firstTryLegalRate - a.firstTryLegalRate ||
          b.movesSampled - a.movesSampled ||
          b.winRate - a.winRate
        );
      case 'averageResponseTimeMs':
        return (
          (a.averageResponseTimeMs ?? Number.MAX_VALUE) -
            (b.averageResponseTimeMs ?? Number.MAX_VALUE) || b.movesSampled - a.movesSampled
        );
      case 'costPerWinUsd':
        return (
          (a.costPerWinUsd ?? Number.MAX_VALUE) - (b.costPerWinUsd ?? Number.MAX_VALUE) ||
          b.wins - a.wins
        );
      case 'forfeits':
        return a.forfeits - b.forfeits || b.gamesPlayed - a.gamesPlayed;
      case 'winRate':
      default:
        return b.winRate - a.winRate || b.wins - a.wins || b.gamesPlayed - a.gamesPlayed;
    }
  }

  percent(value: number | null): string {
    if (value === null || value === undefined) {
      return 'n/a';
    }
    return `${(value * 100).toFixed(1)}%`;
  }

  currency(value: number | null): string {
    if (value === null || value === undefined) {
      return 'N/A';
    }
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    }).format(value);
  }
}

/** Sort key for a talk rate: models without moves in that mode go last. */
function talkRateOrder(talk: AnalyticsTalkativeness | null | undefined): number {
  return talk && talk.moves > 0 && talk.talkRate != null ? talk.talkRate : -1;
}
