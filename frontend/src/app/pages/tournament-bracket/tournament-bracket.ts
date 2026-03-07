import { Component, OnDestroy, OnInit, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatSnackBar } from '@angular/material/snack-bar';
import { CurrencyPipe } from '@angular/common';
import { ApiService } from '../../services/api.service';
import { Tournament, Game } from '../../models/tournament.model';
import { TournamentCostSummary } from '../../models/analytics.model';
import { BracketDisplay } from '../../components/bracket-display/bracket-display';

@Component({
  selector: 'app-tournament-bracket',
  imports: [
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatChipsModule,
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
  private refreshTimer: ReturnType<typeof setInterval> | null = null;

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private api: ApiService,
    private snackBar: MatSnackBar,
  ) {}

  ngOnInit() {
    this.tournamentId = this.route.snapshot.paramMap.get('id')!;
    this.loadTournament();
    this.loadCostSummary();
    this.startAutoRefresh();
  }

  ngOnDestroy(): void {
    if (this.refreshTimer) {
      clearInterval(this.refreshTimer);
      this.refreshTimer = null;
    }
  }

  loadTournament() {
    this.api.getTournament(this.tournamentId).subscribe({
      next: (t) => this.tournament.set(t),
      error: () => {
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
    this.api.generateBracket(this.tournamentId).subscribe(() => {
      this.loadTournament();
      this.loadCostSummary();
    });
  }

  startGame(game: Game) {
    this.api.startGame(game.id).subscribe(() => {
      this.router.navigate(['/games', game.id]);
    });
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
