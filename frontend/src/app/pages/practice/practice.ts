import { CurrencyPipe, DatePipe } from '@angular/common';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';
import type { Game } from '../../models/tournament.model';
import { gameStatusLabel } from '../../utils/status-labels';
import { ApiService } from '../../services/api.service';

@Component({
  selector: 'app-practice',
  imports: [CurrencyPipe, DatePipe, MatButtonModule, RouterLink],
  templateUrl: './practice.html',
  styleUrl: './practice.scss',
})
export class Practice {
  private readonly api = inject(ApiService);
  private readonly destroyRef = inject(DestroyRef);
  readonly games = signal<Game[] | null>(null);
  readonly failed = signal(false);

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
