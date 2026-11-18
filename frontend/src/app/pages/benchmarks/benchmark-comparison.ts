import {
  CurrencyPipe,
  DatePipe,
  DecimalPipe,
  NgTemplateOutlet,
  PercentPipe,
  TitleCasePipe,
} from '@angular/common';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { EMPTY, Subject, catchError, finalize, startWith, switchMap } from 'rxjs';
import type {
  BenchmarkComparison,
  BenchmarkComparisonGameSide,
  BenchmarkSummary,
} from '../../models/benchmark.model';
import { ApiService } from '../../services/api.service';
import { gameStatusLabel, resultReasonLabel } from '../../utils/status-labels';

@Component({
  selector: 'app-benchmark-comparison',
  imports: [
    CurrencyPipe,
    DatePipe,
    DecimalPipe,
    NgTemplateOutlet,
    PercentPipe,
    TitleCasePipe,
    FormsModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    RouterLink,
  ],
  templateUrl: './benchmark-comparison.html',
  styleUrls: ['./benchmarks.scss', './benchmark-comparison.scss'],
})
export class BenchmarkComparisonPage {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  private readonly reload = new Subject<void>();

  readonly report = signal<BenchmarkComparison | null>(null);
  readonly runs = signal<BenchmarkSummary[]>([]);
  readonly loading = signal(false);
  readonly reportError = signal('');
  readonly listError = signal('');
  readonly reasonLabel = resultReasonLabel;

  baseId = '';
  candidateId = '';

  constructor() {
    this.api
      .listBenchmarks()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (runs) => this.runs.set(runs),
        error: (error) =>
          this.listError.set(error?.error?.error ?? 'Could not load benchmark runs.'),
      });
    this.route.queryParamMap
      .pipe(
        switchMap((params) => {
          const base = params.get('base') ?? '';
          const candidate = params.get('candidate') ?? '';
          this.baseId = base;
          this.candidateId = candidate;
          this.report.set(null);
          this.reportError.set('');
          if (!base || !candidate) return EMPTY;
          return this.reload.pipe(
            startWith(undefined),
            switchMap(() => {
              this.reportError.set('');
              this.loading.set(true);
              return this.api.compareBenchmarks(base, candidate).pipe(
                catchError((error) => {
                  this.reportError.set(error?.error?.error ?? 'Could not load the comparison.');
                  return EMPTY;
                }),
                finalize(() => this.loading.set(false)),
              );
            }),
          );
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((report) => this.report.set(report));
  }

  compare(): void {
    if (!this.baseId || !this.candidateId || this.baseId === this.candidateId) return;
    if (
      this.route.snapshot.queryParamMap.get('base') === this.baseId &&
      this.route.snapshot.queryParamMap.get('candidate') === this.candidateId
    ) {
      this.reload.next();
      return;
    }
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { base: this.baseId, candidate: this.candidateId },
    });
  }

  resultLabel(game: BenchmarkComparisonGameSide): string {
    if (game.forfeit) return 'Forfeit';
    if (!game.gameId) return 'Missing game';
    return game.outcome
      ? game.outcome[0] + game.outcome.slice(1).toLowerCase()
      : gameStatusLabel(game.status);
  }
}
