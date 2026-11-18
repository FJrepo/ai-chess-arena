import { CurrencyPipe, DatePipe } from '@angular/common';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { Router, RouterLink } from '@angular/router';
import type { Game } from '../../models/tournament.model';
import { gameStatusLabel } from '../../utils/status-labels';
import { ApiService } from '../../services/api.service';

@Component({
  selector: 'app-practice',
  imports: [
    CurrencyPipe,
    DatePipe,
    FormsModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    RouterLink,
  ],
  templateUrl: './practice.html',
  styleUrl: './practice.scss',
})
export class Practice {
  private readonly api = inject(ApiService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly router = inject(Router);
  readonly games = signal<Game[] | null>(null);
  readonly failed = signal(false);
  readonly creating = signal(false);
  readonly createError = signal<string | null>(null);
  side: 'WHITE' | 'BLACK' = 'WHITE';
  elo: number | null = 1500;

  validElo(): boolean {
    return this.elo != null && Number.isInteger(this.elo) && this.elo >= 1320 && this.elo <= 3190;
  }

  createStockfish(): void {
    const elo = this.elo;
    if (this.creating() || elo == null || !this.validElo()) return;
    this.creating.set(true);
    this.createError.set(null);
    this.api
      .createStockfishPractice(this.side, elo)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (game) => void this.router.navigate(['/games', game.id]),
        error: (err) => {
          this.creating.set(false);
          this.createError.set(
            err?.error?.error ?? 'Could not create the Stockfish practice game.',
          );
        },
      });
  }

  constructor() {
    this.load();
  }

  load(): void {
    this.failed.set(false);
    this.games.set(null);
    this.api
      .getPracticeGames()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (games) => this.games.set(games),
        error: () => this.failed.set(true),
      });
  }

  readonly statusLabel = gameStatusLabel;
}
