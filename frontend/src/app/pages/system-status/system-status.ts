import { OpenRouterSettings } from '../../components/openrouter-settings/openrouter-settings';
import { DatePipe } from '@angular/common';
import { Component, OnInit, computed, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { finalize } from 'rxjs';
import { ApiService, SystemStatusResponse } from '../../services/api.service';
import { openRouterConnected, openRouterValidation } from '../../utils/openrouter-status';

/** Health of a dependency; `unknown` when the backend could not be asked. */
export type StatusTone = 'healthy' | 'degraded' | 'unknown';

@Component({
  selector: 'app-system-status',
  standalone: true,
  imports: [
    OpenRouterSettings,
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

  backendReachable = computed(() => this.status() !== null && !this.error());
  backendVersion = computed(() => this.status()?.backendVersion ?? 'unknown');

  /** Connection is independent of validation; only a known-invalid key needs attention. */
  openRouterTone = computed(() => {
    const status = this.status();
    if (!status) return 'unknown';
    return openRouterConnected(status.key) && openRouterValidation(status.key) !== 'invalid'
      ? 'healthy'
      : 'degraded';
  });
  stockfishTone = computed(() => this.dependencyTone(this.status()?.stockfishAvailable));

  constructor(private api: ApiService) {}

  ngOnInit(): void {
    this.refresh();
  }

  refresh(): void {
    this.loading.set(true);
    this.error.set(null);

    this.api
      .getSystemStatus()
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: (status) => this.status.set(status),
        error: () => {
          this.status.set(null);
          this.error.set('Backend system status is unavailable.');
        },
      });
  }

  statusTone(ok: boolean): StatusTone {
    return ok ? 'healthy' : 'degraded';
  }

  statusLabel(ok: boolean, positiveLabel = 'Online', negativeLabel = 'Offline'): string {
    return ok ? positiveLabel : negativeLabel;
  }

  /** Card heading for a backend-checked dependency: "Unknown" while the backend is unreachable. */
  dependencyLabel(tone: StatusTone, positiveLabel: string, negativeLabel: string): string {
    if (tone === 'unknown') return 'Unknown';
    return tone === 'healthy' ? positiveLabel : negativeLabel;
  }

  stockfishSummary(): string {
    const status = this.status();
    if (!status) return 'Evaluation status unknown: the backend is unreachable.';
    if (status.stockfishAvailable) return 'Stockfish is ready for live evaluations.';
    return status.stockfishReason || 'Stockfish evaluation is unavailable.';
  }

  openRouterSummary(): string {
    const status = this.status();
    if (!status) return 'Provider status unknown: the backend is unreachable.';
    if (!openRouterConnected(status.key)) return 'Connect OpenRouter to use a provider key.';
    switch (status.key.validation) {
      case 'valid':
        return 'OpenRouter API key validated successfully at the last check.';
      case 'invalid':
        return 'OpenRouter key failed its last validation. Test or replace it in settings.';
      case 'unavailable':
        return 'OpenRouter is connected. The last key check was unavailable; validation is unknown.';
      default:
        return 'OpenRouter is connected. This key has not been tested yet.';
    }
  }

  private dependencyTone(ok: boolean | undefined): StatusTone {
    if (this.status() === null || ok === undefined) return 'unknown';
    return ok ? 'healthy' : 'degraded';
  }
}
