import {
  afterRenderEffect,
  Component,
  computed,
  ElementRef,
  OnDestroy,
  OnInit,
  signal,
  viewChildren,
} from '@angular/core';
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
import { MatTooltipModule } from '@angular/material/tooltip';
import {
  CurrencyPipe,
  DatePipe,
  DecimalPipe,
  formatNumber,
  Location,
  NgTemplateOutlet,
} from '@angular/common';
import {
  ActivatedRoute,
  isActive,
  NavigationEnd,
  NavigationSkipped,
  NavigationStart,
  Router,
  UrlTree,
} from '@angular/router';
import { finalize, Subscription, timeout } from 'rxjs';
import { ApiService } from '../../services/api.service';
import { Tournament } from '../../models/tournament.model';
import {
  AnalyticsComparisonModel,
  AnalyticsComparisonResponse,
  AnalyticsHealthResponse,
  AnalyticsTalkativeness,
  ModelReliabilityResponse,
} from '../../models/analytics.model';

import {
  observedReasoningEffortLabel,
  ObservedReasoningEffort,
  REASONING_EFFORTS,
} from '../../utils/reasoning-effort';

type ComparisonSortOption =
  | 'winRate'
  | 'reliabilityScore'
  | 'firstTryLegalRate'
  | 'averageResponseTimeMs'
  | 'costPerWinUsd'
  | 'forfeits';

const COST_SECTIONS = [
  { id: 'spend', label: 'Spend' },
  { id: 'prompt-caching', label: 'Prompt caching' },
  { id: 'reliability', label: 'Reliability' },
] as const;

type CostSection = (typeof COST_SECTIONS)[number]['id'];

type ChatCounts = Pick<AnalyticsTalkativeness, 'moves' | 'messages'>;

@Component({
  selector: 'app-analytics-dashboard',
  standalone: true,
  host: { '(document:focusin)': 'onDocumentFocusIn($event)' },
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
    MatTooltipModule,
    CurrencyPipe,
    DatePipe,
    DecimalPipe,
    NgTemplateOutlet,
  ],
  templateUrl: './analytics-dashboard.html',
  styleUrl: './analytics-dashboard.scss',
})
export class AnalyticsDashboard implements OnInit, OnDestroy {
  readonly tabIds = ['overview', 'quality', 'behaviour', 'cost'] as const;
  selectedTabIndex = signal(0);
  readonly effortOptions: readonly ObservedReasoningEffort[] = [
    ...REASONING_EFFORTS,
    'default',
    'mixed',
    'unknown',
  ];
  readonly effortLabel = observedReasoningEffortLabel;
  selectedReasoningEffort: ObservedReasoningEffort | '' = '';

  readonly dayOptions = [7, 30, 90];
  readonly scoreExplanation =
    'Score, based on finished games: 50% first-try legal moves, 30% no forfeits, 20% few retries; low samples pulled toward 60. Latency is shown but not scored.';
  readonly qualityExplanation =
    "ACPL is the average centipawn loss per move (lower is better). A move is ?! / ? / ?? when it drops the mover's winning chances by at least 5 / 10 / 15 percentage points (Lichess-style win-probability model); forced moves, overrides and moves without an evaluation are not judged.";
  readonly talkExplanation =
    "Talk rate is the share of a model's moves in finished games that came with a non-empty message: with chat Optional it is the model's own choice, with chat Mandatory it shows how often it complied. Average length is in characters per message; moves made with chat Off are left out.";
  readonly conversionExplanation =
    'Model sides reaching ≥90% winning chances: converted = won (including adjudication), thrown away = drawn/lost; n/a = no evaluation data.';
  conversionRows = computed(() => this.comparisonRows());

  conversionCount(value: number | null | undefined): string {
    return value == null ? 'n/a' : String(value);
  }

  readonly resignationExplanation =
    "Resignations in finished games (tournaments with resignation allowed, from a side's 10th move on). Resigned while not losing: the resigning side still had at least 35% winning chances by Stockfish's evaluation of the position it gave up, i.e. roughly level or better; resignations without an evaluation are not judged.";
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
  selectedLegalMoveHints: boolean | 'all' = 'all';
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
      .filter(
        (row) =>
          ((row.analyzedMoves ?? 0) > 0 && row.averageCentipawnLoss != null) ||
          (row.reasoning?.modelMoves ?? 0) > 0,
      )
      .slice()
      .sort(
        (a, b) =>
          (a.averageCentipawnLoss ?? Infinity) - (b.averageCentipawnLoss ?? Infinity) ||
          (b.analyzedMoves ?? 0) - (a.analyzedMoves ?? 0),
      ),
  );

  /** Models with moves made with chat Optional or Mandatory, most talkative first. */
  talkRows = computed(() =>
    (this.comparison()?.models ?? [])
      .filter((row) => (row.optionalChat?.moves ?? 0) > 0 || (row.mandatoryChat?.moves ?? 0) > 0)
      .sort(
        (a, b) =>
          compareChatRates(a.optionalChat, b.optionalChat) ||
          compareChatRates(a.mandatoryChat, b.mandatoryChat) ||
          a.modelId.localeCompare(b.modelId),
      ),
  );

  readonly minOptionalChatMoves = 20;
  optionalChatSummary = computed(() => {
    // Every takeaway figure uses reported Optional-chat counts. A reported zero counts
    // toward coverage, and every partial result carries the reporting model count for display.
    const entries = (this.comparison()?.models ?? []).map(({ modelId, optionalChat }) => ({
      modelId,
      counts: optionalChat
        ? { moves: optionalChat.moves, messages: optionalChat.messages }
        : ('unknown' as const),
    }));
    const reported = entries.flatMap(({ modelId, counts }) =>
      counts === 'unknown' ? [] : [{ modelId, ...counts }],
    );
    if (reported.length === 0) return { kind: 'unreported' as const };

    const coverage = { reportingCount: reported.length, partial: reported.length < entries.length };
    const moves = reported.reduce((total, sample) => total + sample.moves, 0);
    if (moves === 0) return { kind: 'zero' as const, ...coverage };

    const qualified = reported
      .filter(({ moves }) => moves >= this.minOptionalChatMoves)
      .sort((a, b) => compareChatRates(a, b) || a.modelId.localeCompare(b.modelId));
    const qualifyingCount = qualified.length;
    if (qualifyingCount < 2) {
      return { kind: 'sparse' as const, ...coverage, qualifyingCount, moves };
    }

    const most = qualified[0];
    const least = qualified[qualifyingCount - 1];
    if (compareChatRates(most, least) === 0) {
      return {
        kind: 'equal' as const,
        ...coverage,
        qualifyingCount,
        moves: qualified.reduce((total, sample) => total + sample.moves, 0),
        messages: qualified.reduce((total, sample) => total + sample.messages, 0),
      };
    }
    return { kind: 'contrast' as const, ...coverage, most, least };
  });

  readonly cacheExplanation =
    'All attempts with usage in finished, non-bye games; hit rate = cached / prompt tokens, savings use current catalog read prices; incomplete counters or unknown prices are n/a.';

  cacheRows = computed(() =>
    [...(this.comparison()?.models ?? [])].sort(
      (a, b) =>
        (b.promptCaching?.cacheHitRate ?? -1) - (a.promptCaching?.cacheHitRate ?? -1) ||
        a.modelId.localeCompare(b.modelId),
    ),
  );

  cacheTokens(value: number | null | undefined): string {
    return value == null ? 'n/a' : formatNumber(value, 'en-US', '1.0-0');
  }

  cacheSavings(value: number | null | undefined): string {
    return value == null ? 'n/a' : '$' + formatNumber(value, 'en-US', '1.2-6');
  }

  /** Models that resigned or won by resignation, most resignations first. */
  resignationRows = computed(() =>
    (this.comparison()?.models ?? [])
      .filter((row) => (row.resignations ?? 0) > 0 || (row.wonByResignation ?? 0) > 0)
      .slice()
      .sort(
        (a, b) =>
          (b.resignations ?? 0) - (a.resignations ?? 0) ||
          (b.resignationsWhileNotLosing ?? 0) - (a.resignationsWhileNotLosing ?? 0) ||
          (b.wonByResignation ?? 0) - (a.wonByResignation ?? 0) ||
          a.modelId.localeCompare(b.modelId),
      ),
  );

  /**
   * Models with a resignation between models on either side, most opponents talked out first, then most
   * times talked out themselves.
   */
  persuasionRows = computed(() =>
    (this.comparison()?.models ?? [])
      .flatMap((row) => (row.persuasion ? [{ modelId: row.modelId, ...row.persuasion }] : []))
      .filter(
        (row) =>
          row.asPersuaderMindGames.resignations +
            row.asPersuaderOtherLevels.resignations +
            row.asPersuaded.resignations >
          0,
      )
      .sort(
        (a, b) =>
          b.asPersuaderMindGames.talkedOut +
            b.asPersuaderOtherLevels.talkedOut -
            (a.asPersuaderMindGames.talkedOut + a.asPersuaderOtherLevels.talkedOut) ||
          b.asPersuaded.talkedOut - a.asPersuaded.talkedOut ||
          a.modelId.localeCompare(b.modelId),
      ),
  );

  /** The persuasion definition with the backend's threshold, window and sample minimum. */
  persuasionExplanation = computed(() => {
    const rules = this.comparison()?.persuasionRules;
    if (!rules) return null;
    const moves = rules.messageWindowMoves === 1 ? 'move' : 'moves';
    return (
      `Talked out of the game: in a finished model-vs-model game, the resigning model was not lost by Stockfish's evaluation of the position it gave up (better than ${this.formatPawns(rules.lostThresholdCp)} pawns from its own view, no forced mate against it) ` +
      `and its opponent sent at least one chat message on its last ${rules.messageWindowMoves} ${moves} before the resignation. ` +
      `The persuader's level is the banter level of its last move. Counts are of evaluated resignations; fewer than ${rules.lowSampleResignations} is a low sample.`
    );
  });

  /** Centipawns as signed pawns with a typographic minus, e.g. "−2.00". */
  formatPawns(cp: number): string {
    const pawns = Math.abs(cp / 100).toFixed(2);
    return cp < 0 ? `−${pawns}` : `+${pawns}`;
  }

  resignationsExplicitlyZero = computed(() =>
    this.comparison()?.models.every(
      ({ resignations, wonByResignation }) => resignations === 0 && wonByResignation === 0,
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

  reliabilityInsufficientCount = computed(() => {
    return (this.reliability()?.models ?? []).filter((m) => m.insufficientData).length;
  });

  private static readonly REPORT_TIMEOUT_MS = 60_000;

  private readonly subscriptions = new Subscription();
  private reports = new Subscription();
  private refreshTimer: ReturnType<typeof setInterval> | null = null;

  // Only a pending section navigation can move focus. It is consumed once the
  // heading is ready, or discarded when the user interacts or navigates again.
  private readonly pendingJump = signal<CostSection | null>(null);
  private readonly costHeadings =
    viewChildren<ElementRef<HTMLHeadingElement>>('costSectionHeading');
  private readonly costTabIndex = this.tabIds.indexOf('cost');
  private readonly tabTransitioning = signal(false);

  readonly costJumpLinks = computed(() => {
    const current =
      this.router.lastSuccessfulNavigation()?.finalUrl ?? this.router.parseUrl(this.router.url);
    return COST_SECTIONS.map(({ id, label }) => {
      const url = new UrlTree(current.root, { ...current.queryParams, tab: 'cost' }, id);
      return {
        id,
        label,
        url,
        href: this.location.prepareExternalUrl(this.router.serializeUrl(url)),
      };
    });
  });

  constructor(
    private api: ApiService,
    private route: ActivatedRoute,
    private router: Router,
    private location: Location,
  ) {
    // The router creates this component before NavigationEnd, which can precede ngOnInit.
    this.subscriptions.add(
      this.router.events.subscribe((event) => {
        if (event instanceof NavigationStart || event instanceof NavigationSkipped) {
          this.pendingJump.set(null);
        } else if (event instanceof NavigationEnd) {
          this.pendingJump.set(this.costSectionForNavigation(event));
        }
      }),
    );
    afterRenderEffect(() => this.fulfilPendingJump());
  }

  ngOnInit(): void {
    let initialTab = true;
    this.subscriptions.add(
      this.route.queryParamMap.subscribe((params) => {
        const index = Math.max(
          0,
          this.tabIds.findIndex((tab) => tab === params.get('tab')),
        );
        if (!initialTab && index !== this.selectedTabIndex()) this.tabTransitioning.set(true);
        this.selectedTabIndex.set(index);
        initialTab = false;
      }),
    );
    this.loadTournaments();
    this.refreshAll();
    this.startAutoRefresh();
  }

  ngOnDestroy(): void {
    this.reports.unsubscribe();
    this.subscriptions.unsubscribe();
    if (this.refreshTimer !== null) {
      clearInterval(this.refreshTimer);
    }
  }

  onTabChange(index: number): void {
    if (index === this.selectedTabIndex()) return;
    this.pendingJump.set(null);
    this.selectedTabIndex.set(index);
    this.tabTransitioning.set(true);
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { tab: this.tabIds[index] },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }

  onTabAnimationDone(): void {
    this.tabTransitioning.set(false);
  }

  followCostJumpLink(event: MouseEvent, url: UrlTree): void {
    if (event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) {
      return;
    }
    event.preventDefault();
    void this.router.navigateByUrl(url, { replaceUrl: true, onSameUrlNavigation: 'reload' });
  }

  onDocumentFocusIn(event: FocusEvent): void {
    const section = this.pendingJump();
    if (section && !this.costHeading(section)?.contains(event.target as Node)) {
      this.pendingJump.set(null);
    }
  }

  private costSectionForNavigation(event: NavigationEnd): CostSection | null {
    const url = this.router.parseUrl(event.urlAfterRedirects);
    const section = COST_SECTIONS.find(({ id }) => id === url.fragment);
    if (url.queryParamMap.get('tab') !== 'cost' || !section) return null;

    // A query-only change carries the existing section along. An exact URL revisit is a jump.
    const previous = this.router.lastSuccessfulNavigation()?.previousNavigation?.finalUrl;
    if (
      previous?.queryParamMap.get('tab') === 'cost' &&
      this.router.serializeUrl(previous) !== event.urlAfterRedirects &&
      isActive(previous, this.router, {
        paths: 'exact',
        matrixParams: 'exact',
        fragment: 'exact',
        queryParams: 'ignored',
      })()
    ) {
      return null;
    }
    return section.id;
  }

  private fulfilPendingJump(): void {
    const section = this.pendingJump();
    if (!section || this.tabTransitioning() || this.selectedTabIndex() !== this.costTabIndex) {
      return;
    }

    // Reports above the destination can shift the heading while they first load; a background
    // refresh keeps the current data in place, so it does not hold a jump back.
    if (
      (section !== 'spend' && this.healthLoading() && !this.health()) ||
      (section === 'reliability' && this.comparisonLoading() && !this.comparison())
    ) {
      return;
    }

    const heading = this.costHeading(section);
    if (!heading?.isConnected) return;

    this.pendingJump.set(null);
    heading.scrollIntoView?.({ block: 'center' });
    heading.focus({ preventScroll: true });
  }

  private costHeading(section: CostSection): HTMLHeadingElement | undefined {
    return this.costHeadings().find(({ nativeElement }) => nativeElement.id === section)
      ?.nativeElement;
  }

  onCommonFiltersChanged(): void {
    this.pendingJump.set(null);
    this.comparison.set(null);
    this.health.set(null);
    this.reliability.set(null);
    this.lastUpdated.set(null);
    this.refreshAll();
  }

  manualRefresh(): void {
    // A refresh is a user action: it supersedes a jump that is still waiting for reports.
    this.pendingJump.set(null);
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

  /** Resignations whose final position had no evaluation. */
  unevaluatedResignations(row: AnalyticsComparisonModel): number | null {
    const { resignations, resignationsEvaluated } = row;
    return resignations == null || resignationsEvaluated == null
      ? null
      : resignations - resignationsEvaluated;
  }

  /** Resignations per game played, e.g. "2 (10%)". */
  formatResignations(row: AnalyticsComparisonModel): string | null {
    const resignations = row.resignations;
    if (resignations == null) return null;
    return resignations === 0 || row.gamesPlayed === 0
      ? `${resignations}`
      : `${resignations} (${this.percent(resignations / row.gamesPlayed)})`;
  }

  formatTalkRate(talk: ChatCounts | undefined): string | null {
    return talk?.moves ? this.formatChatRate(talk.messages, talk.moves) : null;
  }

  formatChatRate(messages: number, moves: number): string {
    if (messages > 0 && messages * 1000 < moves) return '<0.1%';
    const silentMoves = moves - messages;
    if (silentMoves > 0 && silentMoves * 1000 < moves) return '>99.9%';
    return this.percent(messages / moves);
  }

  formatMs(value: number | null): string {
    if (value === null || value === undefined) {
      return 'n/a';
    }
    return `${Math.round(value).toLocaleString('en-US')} ms`;
  }

  formatSeconds(milliseconds: number | null | undefined): string {
    return milliseconds == null
      ? 'n/a'
      : `${formatNumber(milliseconds / 1000, 'en-US', '1.1-1')} s`;
  }

  private refreshAll(): void {
    this.reports.unsubscribe();
    this.reports = new Subscription();
    this.refreshIdleReports();
  }

  private startAutoRefresh(): void {
    this.refreshTimer = setInterval(() => {
      this.refreshIdleReports();
    }, 15000);
  }

  private refreshIdleReports(): void {
    if (!this.comparisonLoading()) this.reports.add(this.loadComparison());
    if (!this.healthLoading()) this.reports.add(this.loadHealth());
    if (!this.reliabilityLoading()) this.reports.add(this.loadReliability());
  }

  private currentTournamentFilter(): string | null {
    return this.selectedTournamentId.trim().length > 0 ? this.selectedTournamentId : null;
  }

  private loadTournaments(): void {
    this.subscriptions.add(
      this.api.getTournaments().subscribe({
        next: (tournaments) => this.tournaments.set(tournaments),
        error: () => this.tournaments.set([]),
      }),
    );
  }

  private loadHealth(): Subscription {
    this.healthLoading.set(true);
    this.healthError.set(null);

    return this.api
      .getAnalyticsHealth({
        days: this.selectedDays,
        tournamentId: this.currentTournamentFilter(),
        ...(this.selectedLegalMoveHints === 'all'
          ? {}
          : { legalMoveHints: this.selectedLegalMoveHints }),
      })
      .pipe(
        timeout(AnalyticsDashboard.REPORT_TIMEOUT_MS),
        finalize(() => this.healthLoading.set(false)),
      )
      .subscribe({
        next: (response) => {
          this.health.set(response);
          this.lastUpdated.set(new Date());
        },
        error: () => {
          this.healthError.set('Failed to load engine health metrics.');
        },
      });
  }

  private loadComparison(): Subscription {
    this.comparisonLoading.set(true);
    this.comparisonError.set(null);

    return this.api
      .getAnalyticsComparison({
        days: this.selectedDays,
        tournamentId: this.currentTournamentFilter(),
        ...(this.selectedLegalMoveHints === 'all'
          ? {}
          : { legalMoveHints: this.selectedLegalMoveHints }),
        minGames: Math.max(0, this.minGames),
        ...(this.selectedReasoningEffort && { reasoningEffort: this.selectedReasoningEffort }),
      })
      .pipe(
        timeout(AnalyticsDashboard.REPORT_TIMEOUT_MS),
        finalize(() => this.comparisonLoading.set(false)),
      )
      .subscribe({
        next: (response) => {
          this.comparison.set(response);
          this.lastUpdated.set(new Date());
        },
        error: () => {
          this.comparisonError.set('Failed to load model comparison metrics.');
        },
      });
  }

  private loadReliability(): Subscription {
    this.reliabilityLoading.set(true);
    this.reliabilityError.set(null);

    return this.api
      .getAnalyticsReliability({
        days: this.selectedDays,
        tournamentId: this.currentTournamentFilter(),
        ...(this.selectedLegalMoveHints === 'all'
          ? {}
          : { legalMoveHints: this.selectedLegalMoveHints }),
        minGames: Math.max(0, this.minGames),
      })
      .pipe(
        timeout(AnalyticsDashboard.REPORT_TIMEOUT_MS),
        finalize(() => this.reliabilityLoading.set(false)),
      )
      .subscribe({
        next: (response) => {
          this.reliability.set(response);
          this.lastUpdated.set(new Date());
        },
        error: () => {
          this.reliabilityError.set('Failed to load model reliability metrics.');
        },
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

/** Highest exact rate first, followed by reported zero-move samples and then omitted data. */
function compareChatRates(a: ChatCounts | undefined, b: ChatCounts | undefined): number {
  if (!a || !b) return Number(!!b) - Number(!!a);
  if (a.moves === 0 || b.moves === 0) return Number(b.moves > 0) - Number(a.moves > 0);
  return Number(BigInt(b.messages) * BigInt(a.moves) - BigInt(a.messages) * BigInt(b.moves));
}
