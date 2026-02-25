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
import { CurrencyPipe, DatePipe, DecimalPipe } from '@angular/common';
import { ApiService } from '../../services/api.service';
import { Tournament } from '../../models/tournament.model';
import { AnalyticsHealthResponse, ModelReliabilityResponse } from '../../models/analytics.model';

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

  selectedDays = 30;
  selectedTournamentId = '';
  minGames = 0;

  tournaments = signal<Tournament[]>([]);

  health = signal<AnalyticsHealthResponse | null>(null);
  reliability = signal<ModelReliabilityResponse | null>(null);

  healthLoading = signal(false);
  reliabilityLoading = signal(false);

  healthError = signal<string | null>(null);
  reliabilityError = signal<string | null>(null);

  lastUpdated = signal<Date | null>(null);

  reliabilityAverageScore = computed(() => {
    const models = this.reliability()?.models ?? [];
    if (models.length === 0) return 0;
    return models.reduce((sum, model) => sum + model.finalScore, 0) / models.length;
  });

  reliabilityLowConfidenceCount = computed(() => {
    return (this.reliability()?.models ?? []).filter((m) => m.insufficientData).length;
  });

  private refreshTimer: any = null;

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

  formatMs(value: number | null): string {
    if (value === null || value === undefined) {
      return 'n/a';
    }
    return `${Math.round(value)} ms`;
  }

  private refreshAll(): void {
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
}
