import { DatePipe } from '@angular/common';
import { Component, OnDestroy, OnInit, computed, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { Router } from '@angular/router';
import { Observable, finalize, map, tap } from 'rxjs';
import { OpenRouterClient, OpenRouterStatus } from '../../services/openrouter-client';
import { openRouterConnected, openRouterValidation } from '../../utils/openrouter-status';
import { rememberOpenRouterReturnUrl } from '../../utils/openrouter-return-url';

@Component({
  selector: 'app-openrouter-settings',
  imports: [DatePipe, FormsModule, MatButtonModule, MatFormFieldModule, MatInputModule],
  templateUrl: './openrouter-settings.html',
  styleUrl: './openrouter-settings.scss',
})
export class OpenRouterSettings implements OnInit, OnDestroy {
  status = signal<OpenRouterStatus | null>(null);
  connected = computed(() => openRouterConnected(this.status()?.key));
  validation = computed(() => openRouterValidation(this.status()?.key));
  error = signal<string | null>(null);
  busy = signal(false);
  manualExpanded = signal(false);
  manualKey = '';

  constructor(
    private client: OpenRouterClient,
    private router: Router,
  ) {}
  ngOnInit() {
    this.refresh();
  }
  refresh() {
    if (this.busy()) return;
    this.update(
      this.client.status().pipe(
        // The shared client rejects key metadata from reads that a successful write superseded.
        map((status) => ({ ...status, key: this.client.keyStatus() ?? status.key })),
        tap({ error: () => this.status.set(null) }),
      ),
    );
  }
  ngOnDestroy() {
    this.clearManualEntry();
  }
  toggleManualEntry() {
    if (this.manualExpanded()) this.clearManualEntry();
    else this.manualExpanded.set(true);
  }
  private clearManualEntry() {
    this.manualKey = '';
    this.manualExpanded.set(false);
  }
  managed() {
    return ['env', 'file'].includes(this.status()?.key.source ?? '');
  }
  update(request: Observable<OpenRouterStatus>) {
    this.busy.set(true);
    this.error.set(null);
    request.pipe(finalize(() => this.busy.set(false))).subscribe({
      next: (status) => this.status.set(status),
      error: (err) => this.error.set(err?.error?.error ?? 'OpenRouter settings are unavailable.'),
    });
  }
  connect() {
    this.clearManualEntry();
    this.busy.set(true);
    this.error.set(null);
    this.client
      .start()
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({
        next: ({ authUrl }) => {
          rememberOpenRouterReturnUrl(this.router.url);
          window.location.assign(authUrl);
        },
        error: (err) =>
          this.error.set(err?.error?.error ?? 'Could not start OpenRouter connection.'),
      });
  }
  save() {
    const key = this.manualKey;
    this.manualKey = '';
    this.update(this.client.manual(key));
  }
  test() {
    this.update(this.client.test());
  }
  delete() {
    this.update(this.client.delete());
  }
}
