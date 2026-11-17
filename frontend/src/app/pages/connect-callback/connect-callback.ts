import { Component, OnInit, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { switchMap } from 'rxjs';
import { OpenRouterClient } from '../../services/openrouter-client';

@Component({
  selector: 'app-connect-callback',
  imports: [RouterLink],
  template: `
    <h1>Connect OpenRouter</h1>
    <p role="status">{{ message() }}</p>
    <a routerLink="/system">Open settings</a>
  `,
})
export class ConnectCallback implements OnInit {
  message = signal('Finishing connection…');
  constructor(
    private route: ActivatedRoute,
    private client: OpenRouterClient,
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
        this.message.set('A setup token is required. Use the link printed in the backend log.');
        return;
      }
      this.client
        .status()
        .pipe(switchMap(() => this.client.claim(token)))
        .subscribe({
          next: () => this.message.set('Instance claimed. Open settings to connect OpenRouter.'),
          error: (err) => this.message.set(err?.error?.error ?? 'Could not claim this instance.'),
        });
    } else if (code && state) {
      this.client.complete(code, state).subscribe({
        next: () =>
          this.message.set('OpenRouter connected. Return to your tournament or open settings.'),
        error: (err) =>
          this.message.set(
            err?.error?.error ?? 'Connection failed. Open settings and connect again.',
          ),
      });
    } else {
      this.message.set(
        'Connection cancelled or callback is incomplete. Open settings to try again.',
      );
    }
  }
}
