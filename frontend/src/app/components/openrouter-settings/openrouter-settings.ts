import { DatePipe } from '@angular/common';
import { Component, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { Observable, finalize } from 'rxjs';
import { OpenRouterClient, OpenRouterStatus } from '../../services/openrouter-client';

@Component({
  selector: 'app-openrouter-settings',
  imports: [DatePipe, FormsModule, MatButtonModule, MatFormFieldModule, MatInputModule],
  templateUrl: './openrouter-settings.html',
})
export class OpenRouterSettings implements OnInit {
  status = signal<OpenRouterStatus | null>(null);
  error = signal<string | null>(null);
  busy = signal(false);
  manualKey = '';

  constructor(private client: OpenRouterClient) {}
  ngOnInit() {
    this.update(this.client.status());
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
    this.busy.set(true);
    this.error.set(null);
    this.client
      .start()
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({
        next: ({ authUrl }) => window.location.assign(authUrl),
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
