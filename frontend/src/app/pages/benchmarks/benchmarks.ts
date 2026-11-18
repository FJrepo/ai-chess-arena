import {
  CurrencyPipe,
  DatePipe,
  DecimalPipe,
  NgTemplateOutlet,
  TitleCasePipe,
  formatCurrency,
} from '@angular/common';
import { Component, DestroyRef, LOCALE_ID, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { EMPTY, Observable, Subject, catchError, finalize, startWith, switchMap } from 'rxjs';
import type {
  BenchmarkColor,
  BenchmarkGame,
  BenchmarkModelResult,
  BenchmarkPair,
  BenchmarkRerunChanges,
  BenchmarkRun,
  BenchmarkRunSettings,
  BenchmarkSummary,
  CreateBenchmarkRequest,
  RerunBenchmarkRequest,
} from '../../models/benchmark.model';
import { ApiService } from '../../services/api.service';
import { REASONING_EFFORTS, reasoningEffortLabel } from '../../utils/reasoning-effort';
import { newRequestId } from '../../utils/request-id';
import { gameStatusLabel, resultReasonLabel } from '../../utils/status-labels';
import { canonicalBudget, isBudgetDecimal } from '../../utils/tournament-budget';

const pendingSubmissionsKey = 'benchmark-pending-submissions';
const pendingRerunsKey = 'benchmark-pending-reruns';
const promptChatSuffix = { OFF: '-silent', OPTIONAL: '', MANDATORY: '-chat-required' };
const noHintsSuffix = '-no-hints';

@Component({
  selector: 'app-benchmarks',
  imports: [
    CurrencyPipe,
    DatePipe,
    DecimalPipe,
    NgTemplateOutlet,
    TitleCasePipe,
    FormsModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatSlideToggleModule,
    MatTooltipModule,
    RouterLink,
  ],
  templateUrl: './benchmarks.html',
  styleUrl: './benchmarks.scss',
})
export class Benchmarks {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  private readonly locale = inject(LOCALE_ID);
  private readonly reload = new Subject<void>();
  private readonly pendingSubmissions = signal(this.readPendingSubmissions());
  private readonly pendingReruns = signal(this.readPendingReruns());

  readonly pendingRerun = computed(() => {
    const id = this.runId();
    return id === null ? null : (this.pendingReruns().get(id) ?? null);
  });

  readonly runId = signal<string | null>(null);
  readonly runs = signal<BenchmarkSummary[] | null>(null);
  readonly report = signal<BenchmarkRun | null>(null);
  readonly loading = signal(true);
  readonly busy = signal(false);
  readonly loadError = signal('');
  readonly actionError = signal('');
  readonly hasPendingSubmission = computed(() =>
    [...this.pendingSubmissions().keys()].some((key) => !key.startsWith('rerun:')),
  );
  readonly rerunOpen = signal(false);
  readonly rerunSettings = {
    modelId: 'Model ID',
    reasoningEffort: 'Requested effort',
    legalMoveHints: 'Legal move hints',
    chatMode: 'Chat mode',
    budgetUsd: 'Budget (USD)',
  };
  readonly rerunSettingKeys = Object.keys(this.rerunSettings) as (keyof BenchmarkRerunChanges)[];
  readonly efforts = REASONING_EFFORTS;
  readonly effortLabel = reasoningEffortLabel;
  readonly reasonLabel = resultReasonLabel;
  readonly openings = computed(
    () => new Map(this.report()?.definition.openings.map((opening) => [opening.id, opening])),
  );
  readonly hasPausedGames = computed(
    () =>
      this.report()?.models.some((model) =>
        model.pairs.some((pair) => pair.games.some((game) => game.status === 'PAUSED')),
      ) ?? false,
  );

  name = '';
  modelsText = '';
  lowerStrength: number | null = 1500;
  higherStrength: number | null = 2000;
  legalMoveHints = true;
  reasoningEffort: CreateBenchmarkRequest['reasoningEffort'] = 'default';
  chatMode: CreateBenchmarkRequest['chatMode'] = 'OFF';
  budget = '';
  concurrency: number | null = 2;
  rerunSetting: keyof BenchmarkRerunChanges | '' = '';
  rerunModelFrom = '';
  rerunModelTo = '';
  rerunEffort: BenchmarkRun['reasoningEffort'] = 'default';
  rerunChat: BenchmarkRun['chatMode'] = 'OFF';
  rerunBudget = '';
  resumeBudget = '';
  resumeConcurrency: number | null = null;

  constructor() {
    this.route.paramMap
      .pipe(
        switchMap((params) => {
          const id = params.get('id');
          this.runId.set(id);
          this.report.set(null);
          this.actionError.set('');
          this.rerunOpen.set(false);
          return this.reload.pipe(
            startWith(undefined),
            switchMap(() => {
              this.loading.set(true);
              this.loadError.set('');
              const request: Observable<BenchmarkRun | BenchmarkSummary[]> = id
                ? this.api.getBenchmark(id)
                : this.api.listBenchmarks();
              return request.pipe(
                catchError((error) => {
                  this.loadError.set(error?.error?.error ?? 'Could not load benchmarks.');
                  return EMPTY;
                }),
                finalize(() => this.loading.set(false)),
              );
            }),
          );
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((data) => {
        if (Array.isArray(data)) this.runs.set(data);
        else this.showReport(data, this.report() == null);
      });
  }

  refresh(): void {
    this.reload.next();
  }

  get modelIds(): string[] {
    return this.modelsText.trim().split(/\s+/).filter(Boolean);
  }

  get modelError(): string {
    const ids = this.modelIds;
    if (ids.length === 0 || ids.length > 8) return 'Enter one to eight model IDs.';
    if (ids.some((id) => !this.validModelId(id))) {
      return 'Use provider/model IDs.';
    }
    return new Set(ids).size !== ids.length ? 'Each model ID must be different.' : '';
  }

  private validModelId(id: string): boolean {
    return id.length <= 255 && /^~?[\w.-]+\/[\w.:-]+$/.test(id);
  }

  private validStrength(value: number | null): value is number {
    return value != null && Number.isInteger(value) && value >= 1320 && value <= 3190;
  }

  private runSettings(budget: string, slots: number | null): BenchmarkRunSettings | null {
    if (
      slots == null ||
      !Number.isInteger(slots) ||
      slots < 1 ||
      slots > 8 ||
      !isBudgetDecimal(budget) ||
      canonicalBudget(budget) === '0'
    ) {
      return null;
    }
    return { budgetUsd: canonicalBudget(budget), maxConcurrentGames: slots };
  }

  private readPendingSubmissions(): Map<string, string> {
    try {
      const stored: unknown = JSON.parse(sessionStorage.getItem(pendingSubmissionsKey) ?? '{}');
      if (!stored || typeof stored !== 'object' || Array.isArray(stored)) return new Map();
      return new Map(
        Object.entries(stored).filter(
          (entry): entry is [string, string] => typeof entry[1] === 'string',
        ),
      );
    } catch {
      return new Map();
    }
  }

  private setPendingSubmission(fingerprint: string, requestId: string | null): void {
    const pending = new Map(this.pendingSubmissions());
    if (requestId === null) pending.delete(fingerprint);
    else pending.set(fingerprint, requestId);
    this.pendingSubmissions.set(pending);
    try {
      if (pending.size) {
        sessionStorage.setItem(pendingSubmissionsKey, JSON.stringify(Object.fromEntries(pending)));
      } else {
        sessionStorage.removeItem(pendingSubmissionsKey);
      }
    } catch {
      // In-memory retries still work when storage is unavailable.
    }
  }

  create(): void {
    if (this.busy()) return;
    this.actionError.set('');
    const name = this.name.trim();
    const low = this.lowerStrength;
    const high = this.higherStrength;
    const settings = this.runSettings(this.budget.trim(), this.concurrency);
    if (!name || name.length > 120) {
      this.actionError.set('Enter a name of up to 120 characters.');
    } else if (this.modelError) {
      this.actionError.set(this.modelError);
    } else if (!this.validStrength(low) || !this.validStrength(high) || low === high) {
      this.actionError.set('Enter two different whole-number targets from 1320 to 3190.');
    } else if (!settings) {
      this.actionError.set('Enter a positive USD budget to the cent and 1–8 concurrent games.');
    } else {
      const details: Omit<CreateBenchmarkRequest, 'clientRequestId'> = {
        name,
        modelIds: this.modelIds,
        targetStrengths: [low, high],
        legalMoveHints: this.legalMoveHints,
        reasoningEffort: this.reasoningEffort,
        chatMode: this.chatMode,
        runSettings: settings,
      };
      const fingerprint = JSON.stringify(details);
      const pendingId = this.pendingSubmissions().get(fingerprint);
      const clientRequestId = pendingId ?? newRequestId();
      if (!pendingId) this.setPendingSubmission(fingerprint, clientRequestId);
      const clearPending = () => this.setPendingSubmission(fingerprint, null);
      this.submitCreation(
        this.api.createBenchmark({ ...details, clientRequestId }),
        clearPending,
        (id) => void this.router.navigate(['/benchmarks', id]),
        clearPending,
      );
    }
  }

  openRerun(source: BenchmarkRun): void {
    this.rerunSetting = '';
    this.rerunModelFrom = source.definition.modelIds[0];
    this.rerunModelTo = '';
    this.rerunEffort = source.reasoningEffort;
    this.rerunChat = source.chatMode;
    this.rerunBudget = source.budgetUsd;
    this.actionError.set('');
    this.rerunOpen.set(true);
  }

  rerunChange(source: BenchmarkRun): Partial<BenchmarkRerunChanges> {
    switch (this.rerunSetting) {
      case 'modelId':
        return { modelId: { from: this.rerunModelFrom, to: this.rerunModelTo.trim() } };
      case 'reasoningEffort':
        return { reasoningEffort: this.rerunEffort };
      case 'legalMoveHints':
        return { legalMoveHints: !source.legalMoveHints };
      case 'chatMode':
        return { chatMode: this.rerunChat };
      case 'budgetUsd':
        return { budgetUsd: canonicalBudget(this.rerunBudget.trim()) };
      default:
        return {};
    }
  }

  rerunValidation(source: BenchmarkRun, change: Partial<BenchmarkRerunChanges>): string {
    const { modelId, reasoningEffort, chatMode, budgetUsd } = change;
    if (modelId) {
      if (!this.validModelId(modelId.to)) return 'Enter a provider/model ID.';
      if (source.definition.modelIds.includes(modelId.to)) {
        return 'Choose a model ID outside the source lineup.';
      }
    }
    if (budgetUsd !== undefined && (!isBudgetDecimal(budgetUsd) || budgetUsd === '0')) {
      return 'Enter a positive USD budget to the cent.';
    }
    if (
      reasoningEffort === source.reasoningEffort ||
      chatMode === source.chatMode ||
      budgetUsd === canonicalBudget(source.budgetUsd)
    ) {
      return 'Choose a different value or select No change.';
    }
    return '';
  }

  rerunPreview(source: BenchmarkRun, change: Partial<BenchmarkRerunChanges>) {
    const definition = source.definition;
    const hints = change.legalMoveHints ?? source.legalMoveHints;
    const chat = change.chatMode ?? source.chatMode;
    const sourcePromptSuffix =
      promptChatSuffix[source.chatMode] + (source.legalMoveHints ? '' : noHintsSuffix);
    const promptFamily = sourcePromptSuffix
      ? definition.promptVersion.slice(0, -sourcePromptSuffix.length)
      : definition.promptVersion;
    const promptVersion = promptFamily + promptChatSuffix[chat] + (hints ? '' : noHintsSuffix);
    const budget = canonicalBudget(source.budgetUsd);
    const models = definition.modelIds.map((id) =>
      id === change.modelId?.from ? change.modelId.to : id,
    );
    return [
      { setting: 'Model IDs', before: definition.modelIds.join(', '), after: models.join(', ') },
      {
        setting: 'Requested effort',
        before: this.effortLabel(source.reasoningEffort),
        after: this.effortLabel(change.reasoningEffort ?? source.reasoningEffort),
      },
      {
        setting: 'Legal move hints',
        before: source.legalMoveHints ? 'On' : 'Off',
        after: hints ? 'On' : 'Off',
      },
      { setting: 'Chat mode', before: source.chatMode, after: chat },
      { setting: 'Budget (USD)', before: budget, after: change.budgetUsd ?? budget },
      { setting: 'Prompt version', before: definition.promptVersion, after: promptVersion },
      ...[
        ['Opening suite', definition.suite],
        ['Stockfish levels', definition.targetStrengths.join(' / ')],
        ['Model colors', definition.modelColors.join(' / ')],
        ['Stockfish executable SHA-256', definition.engineSha256],
        [
          'Stockfish NNUE weights',
          definition.engineNetworks.map((nnue) => nnue.name + ' ' + nnue.sha256).join(', '),
        ],
        ['Search nodes', definition.searchNodes],
        ['Threads', definition.threads],
        ['Hash (MiB)', definition.hashMiB],
        ['Concurrent games', source.maxConcurrentGames],
      ].map(([setting, value]) => ({ setting, before: value, after: value })),
    ];
  }

  createRerun(source: BenchmarkRun): void {
    if (this.busy() || this.loading() || this.pendingReruns().has(source.run.id)) return;
    const change = this.rerunChange(source);
    const validation = this.rerunValidation(source, change);
    this.actionError.set(validation);
    if (validation) return;
    const request: RerunBenchmarkRequest = {
      clientRequestId: newRequestId(),
      change,
      expectedRunSettings: {
        budgetUsd: canonicalBudget(source.budgetUsd),
        maxConcurrentGames: source.maxConcurrentGames,
      },
    };
    this.setPendingRerun(source.run.id, request);
    this.recoverRerun(source.run.id);
  }

  recoverRerun(sourceId: string): void {
    if (this.busy() || this.loading()) return;
    const request = this.pendingReruns().get(sourceId);
    if (!request) return;
    this.actionError.set('');
    this.submitCreation(
      this.api.rerunBenchmark(sourceId, request),
      () => this.setPendingRerun(sourceId, null),
      (id) =>
        void this.router.navigate(['/benchmarks', 'compare'], {
          queryParams: { base: sourceId, candidate: id },
        }),
    );
  }

  startSeparateRerun(source: BenchmarkRun): void {
    if (this.busy() || this.loading()) return;
    this.setPendingRerun(source.run.id, null);
    this.openRerun(source);
  }

  private readPendingReruns(): Map<string, RerunBenchmarkRequest> {
    try {
      const stored = JSON.parse(sessionStorage.getItem(pendingRerunsKey) ?? '{}') as Record<
        string,
        RerunBenchmarkRequest
      >;
      return new Map(
        Object.entries(stored).filter(
          ([, request]) =>
            typeof request?.clientRequestId === 'string' &&
            request.change !== null &&
            typeof request.change === 'object' &&
            typeof request.expectedRunSettings?.budgetUsd === 'string' &&
            Number.isInteger(request.expectedRunSettings.maxConcurrentGames),
        ),
      );
    } catch {
      return new Map();
    }
  }

  private setPendingRerun(sourceId: string, request: RerunBenchmarkRequest | null): void {
    const pending = new Map(this.pendingReruns());
    if (request === null) pending.delete(sourceId);
    else pending.set(sourceId, request);
    this.pendingReruns.set(pending);
    try {
      if (pending.size) {
        sessionStorage.setItem(pendingRerunsKey, JSON.stringify(Object.fromEntries(pending)));
      } else {
        sessionStorage.removeItem(pendingRerunsKey);
      }
    } catch {
      // In-memory retries still work when storage is unavailable.
    }
  }

  private submitCreation(
    request: Observable<BenchmarkRun>,
    clearPending: () => void,
    onCreated: (id: string) => void,
    clearOnClientError?: () => void,
  ): void {
    this.busy.set(true);
    request
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.busy.set(false)),
      )
      .subscribe({
        next: ({ run }) => {
          clearPending();
          onCreated(run.id);
        },
        error: (error) => {
          const status = error?.status;
          if (status >= 400 && status < 500 && status !== 408) clearOnClientError?.();
          this.actionError.set(error?.error?.error ?? 'Could not create the benchmark.');
        },
      });
  }

  changeRun(run: BenchmarkSummary, action: 'pause' | 'resume' | 'cancel'): void {
    if (this.busy()) return;
    this.actionError.set('');
    let request: Observable<BenchmarkRun>;
    if (action === 'cancel') {
      if (run.status !== 'RUNNING' && run.status !== 'PAUSED') return;
      if (
        !window.confirm(
          'Cancel this benchmark? Unfinished games will end. Results and spend will be kept.',
        )
      ) {
        return;
      }
      request = this.api.cancelBenchmark(run.id);
    } else if (action === 'resume') {
      const settings = this.runSettings(this.resumeBudget.trim(), this.resumeConcurrency);
      if (!settings) {
        this.actionError.set('Enter a positive USD budget to the cent and 1–8 concurrent games.');
        return;
      }
      request = this.api.resumeBenchmark(run.id, settings);
    } else {
      request = this.api.pauseBenchmark(run.id);
    }
    this.busy.set(true);
    request
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.busy.set(false)),
      )
      .subscribe({
        next: (report) => {
          if (this.runId() === run.id) this.showReport(report, true);
        },
        error: (error) =>
          this.actionError.set(error?.error?.error ?? 'Could not change the benchmark run.'),
      });
  }

  private showReport(report: BenchmarkRun, resetControls: boolean): void {
    this.report.set(report);
    if (resetControls) {
      this.resumeBudget = report.budgetUsd;
      this.resumeConcurrency = report.maxConcurrentGames;
    }
  }

  splitScore(model: BenchmarkModelResult, target: number, color: BenchmarkColor) {
    return model.splits.find((row) => row.targetStrength === target && row.color === color)?.score;
  }

  pairGame(pair: BenchmarkPair, color: BenchmarkColor) {
    return pair.games.find((game) => game.color === color);
  }

  formatSpend(amount: number | null | undefined, maxDecimals: 2 | 4 = 2): string {
    if (amount == null) return '';
    const minimum = 1 / 10 ** maxDecimals;
    const belowMinimum = amount > 0 && amount < minimum;
    const value = formatCurrency(
      belowMinimum ? minimum : amount,
      this.locale,
      '$',
      'USD',
      `1.2-${maxDecimals}`,
    );
    return belowMinimum ? `<${value}` : value;
  }

  gameLabel(game: BenchmarkGame): string {
    if (game.result === 'DRAW') return 'Draw';
    if (game.result === `${game.color}_FORFEIT`) return 'Forfeit loss';
    if (game.result?.includes('FORFEIT')) return 'Win by forfeit';
    if (game.result) return game.result === `${game.color}_WINS` ? 'Win' : 'Loss';
    return gameStatusLabel(game.status);
  }
}
