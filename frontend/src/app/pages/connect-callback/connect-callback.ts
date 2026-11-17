import { Component, OnInit, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatSnackBar } from '@angular/material/snack-bar';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { switchMap } from 'rxjs';
import { OpenRouterClient } from '../../services/openrouter-client';
import { takeOpenRouterReturnUrl } from '../../utils/openrouter-return-url';

@Component({
  selector: 'app-connect-callback',
  imports: [RouterLink, MatButtonModule, MatCardModule],
  template: `
    <div class="desktop-page">
      <mat-card appearance="outlined" class="callback-card">
        <mat-card-content>
          <h1>Connect OpenRouter</h1>
          <p [attr.role]="failed() ? 'alert' : 'status'" [class.error]="failed()">
            {{ message() }}
          </p>
          @if (failed()) {
            <div class="callback-actions">
              <button mat-flat-button (click)="retry()">Try again</button>
              <a mat-button routerLink="/tournaments">Back to tournaments</a>
            </div>
          } @else if (claimed()) {
            <a mat-flat-button routerLink="/system">Open settings</a>
          }
        </mat-card-content>
      </mat-card>
    </div>
  `,
  styleUrl: './connect-callback.scss',
})
export class ConnectCallback implements OnInit {
  message = signal('Finishing connection…');
  failed = signal(false);
  claimed = signal(false);
  constructor(
    private route: ActivatedRoute,
    private client: OpenRouterClient,
    private router: Router,
    private snackbar: MatSnackBar,
  ) {}

  ngOnInit() {
    const params = this.route.snapshot.queryParamMap;
    const token = params.get('token');
    const code = params.get('code');
    const state = params.get('state');
    // Remove codes/tokens before any other navigation or provider request. Never persist them in browser storage.
    window.history.replaceState(window.history.state, '', window.location.pathname);
    if (this.route.snapshot.routeConfig?.path === 'setup') {
      if (!token) {
        this.fail('A setup token is required. Use the link printed in the backend log.');
        return;
      }
      this.client
        .status()
        .pipe(switchMap(() => this.client.claim(token)))
        .subscribe({
          next: () => {
            this.claimed.set(true);
            this.message.set('Instance claimed. Open settings to connect OpenRouter.');
          },
          error: (err) => this.fail(err?.error?.error ?? 'Could not claim this instance.'),
        });
    } else if (code && state) {
      this.client.complete(code, state).subscribe({
        next: () => {
          this.message.set('OpenRouter connected');
          void this.router.navigateByUrl(takeOpenRouterReturnUrl(), { replaceUrl: true });
          this.snackbar.open('OpenRouter connected', 'Dismiss', { duration: 5000 });
        },
        error: (err) => this.fail(err?.error?.error ?? 'Connection failed. Please try again.'),
      });
    } else {
      this.fail('Connection cancelled or callback is incomplete. Please try again.');
    }
  }

  retry() {
    this.failed.set(false);
    this.message.set('Opening OpenRouter…');
    this.client.start().subscribe({
      next: ({ authUrl }) => window.location.assign(authUrl),
      error: (err) => this.fail(err?.error?.error ?? 'Could not start OpenRouter connection.'),
    });
  }

  private fail(message: string) {
    this.message.set(message);
    this.failed.set(true);
  }
}
