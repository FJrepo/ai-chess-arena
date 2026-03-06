import { DatePipe } from '@angular/common';
import { Component, OnInit, computed, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ApiService, SystemStatusResponse } from '../../services/api.service';

@Component({
  selector: 'app-system-status',
  standalone: true,
  imports: [
    MatButtonModule,
    MatCardModule,
    MatChipsModule,
    MatIconModule,
    MatProgressSpinnerModule,
    DatePipe,
  ],
  templateUrl: './system-status.html',
  styleUrl: './system-status.scss',
})
export class SystemStatus implements OnInit {
  status = signal<SystemStatusResponse | null>(null);
  loading = signal(false);
  error = signal<string | null>(null);
  lastCheckedAt = signal<Date | null>(null);

  backendReachable = computed(() => this.status() !== null && !this.error());
  backendVersion = computed(() => this.status()?.backendVersion ?? 'unknown');

  constructor(private api: ApiService) {}

  ngOnInit(): void {
    this.refresh();
  }

  refresh(): void {
    this.loading.set(true);
    this.error.set(null);

    this.api.getSystemStatus().subscribe({
      next: (status) => {
        this.status.set(status);
        this.lastCheckedAt.set(new Date(status.checkedAt));
      },
      error: () => {
        this.status.set(null);
        this.error.set('Backend system status is unavailable.');
        this.lastCheckedAt.set(new Date());
      },
      complete: () => this.loading.set(false),
    });
  }

  statusTone(ok: boolean): 'healthy' | 'degraded' {
    return ok ? 'healthy' : 'degraded';
  }

  statusLabel(ok: boolean, positiveLabel = 'Online', negativeLabel = 'Offline'): string {
    return ok ? positiveLabel : negativeLabel;
  }

  stockfishSummary(): string {
    const status = this.status();
    if (!status) return 'Evaluation status unavailable.';
    if (status.stockfishAvailable) return 'Stockfish is ready for live evaluations.';
    return status.stockfishReason || 'Stockfish evaluation is unavailable.';
  }

  openRouterSummary(): string {
    const status = this.status();
    if (!status) return 'Provider validation status unavailable.';
    return status.openRouterValid
      ? 'OpenRouter API key validated successfully.'
      : 'OpenRouter API key is invalid or unavailable.';
  }
}
