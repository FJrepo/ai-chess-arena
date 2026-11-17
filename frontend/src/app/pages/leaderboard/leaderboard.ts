import { DatePipe } from '@angular/common';
import { Component, OnDestroy, OnInit, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { ProviderLogoComponent } from '../../components/provider-logo/provider-logo';
import { LeaderboardResponse, RatingHistoryPoint } from '../../models/leaderboard.model';
import { ApiService } from '../../services/api.service';

@Component({
  selector: 'app-leaderboard',
  imports: [
    DatePipe,
    MatButtonModule,
    MatCardModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatSlideToggleModule,
    ProviderLogoComponent,
    RouterLink,
  ],
  templateUrl: './leaderboard.html',
  styleUrl: './leaderboard.scss',
})
export class Leaderboard implements OnInit, OnDestroy {
  readonly includeForfeits = signal(true);
  readonly response = signal<LeaderboardResponse | null>(null);
  readonly loading = signal(false);
  readonly error = signal(false);
  private request?: Subscription;

  constructor(private readonly api: ApiService) {}

  ngOnInit(): void {
    this.refresh();
  }

  ngOnDestroy(): void {
    this.request?.unsubscribe();
  }

  setIncludeForfeits(include: boolean): void {
    this.includeForfeits.set(include);
    this.refresh();
  }

  refresh(): void {
    this.request?.unsubscribe();
    this.response.set(null);
    this.error.set(false);
    this.loading.set(true);
    this.request = this.api.getLeaderboard(this.includeForfeits()).subscribe({
      next: (response) => {
        this.response.set(response);
        this.loading.set(false);
      },
      error: () => {
        this.error.set(true);
        this.loading.set(false);
      },
    });
  }

  sparklinePoints(history: RatingHistoryPoint[]): string {
    if (history.length < 2) return '2,14 98,14';
    const ratings = history.map((point) => point.rating);
    const min = Math.min(...ratings);
    const range = Math.max(...ratings) - min;
    return ratings
      .map((rating, index) => {
        const x = 2 + (index / (ratings.length - 1)) * 96;
        const y = range === 0 ? 14 : 25 - ((rating - min) / range) * 22;
        return `${x.toFixed(1)},${y.toFixed(1)}`;
      })
      .join(' ');
  }
}
