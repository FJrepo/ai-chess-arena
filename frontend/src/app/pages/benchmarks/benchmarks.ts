import {
  CurrencyPipe,
  DatePipe,
  DecimalPipe,
  NgTemplateOutlet,
  TitleCasePipe,
} from '@angular/common';
import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { EMPTY, Observable, Subject, catchError, finalize, startWith, switchMap } from 'rxjs';
import type {
  BenchmarkGame,
  BenchmarkRun,
  BenchmarkRunSettings,
  BenchmarkSummary,
  CreateBenchmarkRequest,
} from '../../models/benchmark.model';
import { ApiService } from '../../services/api.service';
import { REASONING_EFFORTS, reasoningEffortLabel } from '../../utils/reasoning-effort';
import { newRequestId } from '../../utils/request-id';
import { gameStatusLabel, resultReasonLabel } from '../../utils/status-labels';
import { canonicalBudget, isBudgetDecimal } from '../../utils/tournament-budget';

const pendingSubmissionsKey = 'benchmark-pending-submissions';

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
  private readonly reload = new Subject<void>();
  private readonly pendingSubmissions = signal(this.readPendingSubmissions());

  readonly runId = signal<string | null>(null);
  readonly runs = signal<BenchmarkSummary[] | null>(null);
  readonly report = signal<BenchmarkRun | null>(null);
  readonly loading = signal(true);
  readonly busy = signal(false);
  readonly loadError = signal('');
  readonly actionError = signal('');
  readonly hasPendingSubmission = computed(() => this.pendingSubmissions().size > 0);
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
    if (ids.some((id) => id.length > 255 || !/^~?[\w.-]+\/[\w.:-]+$/.test(id))) {
      return 'Use provider/model IDs.';
    }
    return new Set(ids).size !== ids.length ? 'Each model ID must be different.' : '';
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
      this.busy.set(true);
      this.api
        .createBenchmark({ ...details, clientRequestId })
        .pipe(
          takeUntilDestroyed(this.destroyRef),
          finalize(() => this.busy.set(false)),
        )
        .subscribe({
          next: ({ run }) => {
            this.setPendingSubmission(fingerprint, null);
            void this.router.navigate(['/benchmarks', run.id]);
          },
          error: (error) => {
            const status = error?.status;
            if (status >= 400 && status < 500 && status !== 408) {
              this.setPendingSubmission(fingerprint, null);
            }
            this.actionError.set(error?.error?.error ?? 'Could not create the benchmark.');
          },
        });
    }
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

  gameLabel(game: BenchmarkGame): string {
    if (game.result === 'DRAW') return 'Draw';
    if (game.result === `${game.color}_FORFEIT`) return 'Forfeit loss';
    if (game.result?.includes('FORFEIT')) return 'Win by forfeit';
    if (game.result) return game.result === `${game.color}_WINS` ? 'Win' : 'Loss';
    return gameStatusLabel(game.status);
  }
}
