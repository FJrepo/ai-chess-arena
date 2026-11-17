import { OpenRouterAccess } from '../../services/openrouter-access';
import { Component, OnDestroy, OnInit, computed, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatInputModule } from '@angular/material/input';
import { Observable } from 'rxjs';
import { MatSnackBar } from '@angular/material/snack-bar';
import { CurrencyPipe } from '@angular/common';
import { ApiService } from '../../services/api.service';
import { BracketDrawMode, Tournament, Game } from '../../models/tournament.model';
import { TournamentCostSummary } from '../../models/analytics.model';
import { BracketDisplay } from '../../components/bracket-display/bracket-display';
import { tournamentStatusLabel } from '../../utils/status-labels';
import { downloadBlob } from '../../utils/download';

@Component({
  selector: 'app-tournament-bracket',
  imports: [
    FormsModule,
    MatInputModule,
    MatFormFieldModule,
    MatSelectModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatExpansionModule,
    CurrencyPipe,
    BracketDisplay,
  ],
  templateUrl: './tournament-bracket.html',
  styleUrl: './tournament-bracket.scss',
})
export class TournamentBracket implements OnInit, OnDestroy {
  tournament = signal<Tournament | null>(null);
  costSummary = signal<TournamentCostSummary | null>(null);
  costSummaryLoading = signal(false);
  costSummaryError = signal<string | null>(null);
  tournamentId = '';
  generating = signal(false);
  cancelling = signal(false);
  runBusy = signal(false);
  exporting = signal(false);
  readonly exportFormats = ['pgn', 'csv', 'json'] as const;
  runConcurrency = 2;
  runBudget = '';
  runDraftDirty = false;
  readonly concurrencyOptions = [1, 2, 3, 4, 5, 6, 7, 8];
  readonly runningGames = computed(
    () => this.tournament()?.games.filter((g) => g.status === 'IN_PROGRESS').length ?? 0,
  );
  readonly attentionGames = computed(
    () => this.tournament()?.games.filter((g) => g.status === 'PAUSED').length ?? 0,
  );
  readonly runSpend = computed(
    () => this.tournament()?.games.reduce((sum, g) => sum + (g.totalCostUsd ?? 0), 0) ?? 0,
  );
  readonly canRun = computed(
    () => this.tournament()?.status === 'IN_PROGRESS' && !this.cancelling() && !this.runBusy(),
  );
  readonly hasActiveRun = computed(() => {
    const status = this.tournament()?.autoRunStatus;
    return status === 'RUNNING' || status === 'PAUSED';
  });

  get runSettingsChanged(): boolean {
    const t = this.tournament();
    return (
      this.runConcurrency !== (t?.maxConcurrentGames ?? 2) ||
      this.runBudgetUsd !== (t?.budgetUsd ?? null)
    );
  }

  get validRunSettings(): boolean {
    return (
      Number.isInteger(this.runConcurrency) &&
      this.runConcurrency >= 1 &&
      this.runConcurrency <= 8 &&
      this.validRunBudget
    );
  }

  get validRunBudget(): boolean {
    return (
      this.runBudget === '' ||
      (/^(?:\d+(?:\.\d{0,2})?|\.\d{1,2})$/.test(this.runBudget) &&
        Number.isFinite(Number(this.runBudget)) &&
        Number(this.runBudget) > 0)
    );
  }

  get runBudgetUsd(): number | null {
    return this.runBudget === '' ? null : Number(this.runBudget);
  }

  readonly canCancel = computed(() => {
    const status = this.tournament()?.status;
    return (
      (status === 'CREATED' || status === 'IN_PROGRESS') && !this.cancelling() && !this.runBusy()
    );
  });
  drawMode: BracketDrawMode = 'RANDOM';
  readonly drawnParticipants = computed(() =>
    [...(this.tournament()?.participants ?? [])].sort((a, b) => a.seed - b.seed),
  );
  readonly statusLabel = tournamentStatusLabel;
  private refreshTimer: ReturnType<typeof setInterval> | null = null;
  private loadGeneration = 0;

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private api: ApiService,
    private snackBar: MatSnackBar,
    private connection: OpenRouterAccess = null!,
  ) {}

  ngOnInit() {
    this.tournamentId = this.route.snapshot.paramMap.get('id')!;
    this.loadTournament();
    this.loadCostSummary();
    this.startAutoRefresh();
  }

  ngOnDestroy(): void {
    ++this.loadGeneration;
    if (this.refreshTimer) {
      clearInterval(this.refreshTimer);
      this.refreshTimer = null;
    }
  }

  exportTournament(format: 'pgn' | 'csv' | 'json'): void {
    if (this.exporting()) return;
    this.exporting.set(true);
    this.api.getTournamentExport(this.tournamentId, format).subscribe({
      next: (blob) => {
        downloadBlob(blob, `tournament-${this.tournamentId}.${format}`);
        this.exporting.set(false);
      },
      error: () => {
        this.exporting.set(false);
        this.snackBar.open('Could not export tournament. Please try again.', 'OK', {
          duration: 5000,
        });
      },
    });
  }

  loadTournament() {
    if (this.cancelling() || this.runBusy()) return;
    const generation = ++this.loadGeneration;
    this.api.getTournament(this.tournamentId).subscribe({
      next: (t) => {
        if (generation === this.loadGeneration) {
          this.tournament.set(t);
          if (!this.runDraftDirty) this.syncRunSettings(t);
        }
      },
      error: () => {
        if (generation !== this.loadGeneration) return;
        this.snackBar.open('Failed to load tournament.', 'Dismiss', { duration: 4000 });
        this.router.navigate(['/tournaments']);
      },
    });
  }

  loadCostSummary() {
    this.costSummaryLoading.set(true);
    this.costSummaryError.set(null);
    this.api.getTournamentCostSummary(this.tournamentId).subscribe({
      next: (summary) => this.costSummary.set(summary),
      error: () => this.costSummaryError.set('Failed to load spend summary.'),
      complete: () => this.costSummaryLoading.set(false),
    });
  }

  generateBracket() {
    if (this.generating()) {
      return;
    }
    this.generating.set(true);
    this.api.generateBracket(this.tournamentId, this.drawMode).subscribe({
      next: () => {
        this.generating.set(false);
        this.loadTournament();
        this.loadCostSummary();
      },
      error: (err) => {
        this.generating.set(false);
        this.showError(err, 'Failed to generate the bracket.');
        this.loadTournament();
      },
    });
  }

  startGame(game: Game) {
    if (this.connection) this.connection.withKey(() => this.startGameWithKey(game));
    else this.startGameWithKey(game);
  }

  private startGameWithKey(game: Game) {
    this.api.startGame(game.id).subscribe({
      next: () => this.router.navigate(['/games', game.id]),
      error: (err) => {
        this.showError(err, 'Failed to start the game.');
        this.loadTournament();
      },
    });
  }

  runTournament(): void {
    if (this.connection) this.connection.withKey(() => this.runTournamentWithKey());
    else this.runTournamentWithKey();
  }

  private runTournamentWithKey(): void {
    if (!this.canRun() || !this.validRunSettings) return;
    this.changeRun(() =>
      this.api.runTournament(this.tournamentId, {
        maxConcurrentGames: this.runConcurrency,
        budgetUsd: this.runBudgetUsd,
      }),
    );
  }

  saveRunSettings(): void {
    if (
      !this.canRun() ||
      !this.hasActiveRun() ||
      !this.validRunSettings ||
      !this.runSettingsChanged
    )
      return;
    this.changeRun(() =>
      this.api.updateTournamentRun(this.tournamentId, {
        maxConcurrentGames: this.runConcurrency,
        budgetUsd: this.runBudgetUsd,
      }),
    );
  }

  pauseRun(): void {
    if (!this.canRun()) return;
    this.changeRun(() => this.api.pauseTournamentRun(this.tournamentId));
  }

  private syncRunSettings(t: Tournament): void {
    this.runConcurrency = t.maxConcurrentGames ?? 2;
    this.runBudget = t.budgetUsd == null ? '' : String(t.budgetUsd);
  }

  private changeRun(request: () => Observable<Tournament>): void {
    this.runBusy.set(true);
    ++this.loadGeneration;
    request().subscribe({
      next: (t) => {
        ++this.loadGeneration;
        this.tournament.set(t);
        this.syncRunSettings(t);
        this.runDraftDirty = false;
        this.runBusy.set(false);
        this.loadCostSummary();
      },
      error: (err) => {
        this.runBusy.set(false);
        this.showError(err, 'Failed to update the tournament run.');
        this.loadTournament();
      },
    });
  }

  cancelTournament(): void {
    if (!this.canCancel()) return;
    if (
      !window.confirm(
        'Cancel this tournament? Unfinished games end without a result. Finished games, moves, chat and costs stay. This cannot be undone.',
      )
    )
      return;
    this.cancelling.set(true);
    ++this.loadGeneration;
    this.api.cancelTournament(this.tournamentId).subscribe({
      next: (tournament) => {
        ++this.loadGeneration;
        this.tournament.set(tournament);
        this.cancelling.set(false);
        this.loadCostSummary();
      },
      error: (err) => {
        this.cancelling.set(false);
        this.showError(err, 'Failed to cancel the tournament.');
        this.loadTournament();
      },
    });
  }

  private showError(err: unknown, fallback: string): void {
    const serverError = (err as { error?: unknown } | null)?.error;
    const message =
      typeof serverError === 'string'
        ? serverError
        : ((serverError as { error?: string } | undefined)?.error ?? fallback);
    this.snackBar.open(message, 'Dismiss', { duration: 5000 });
  }

  viewGame(game: Game) {
    this.router.navigate(['/games', game.id]);
  }

  viewGameById(gameId: string): void {
    this.router.navigate(['/games', gameId]);
  }

  private startAutoRefresh(): void {
    this.refreshTimer = setInterval(() => {
      this.loadTournament();
      this.loadCostSummary();
    }, 15000);
  }
}
