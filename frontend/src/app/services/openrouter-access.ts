import { Component, Injectable } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatSnackBar } from '@angular/material/snack-bar';
import { OpenRouterSettings } from '../components/openrouter-settings/openrouter-settings';
import { OpenRouterClient } from './openrouter-client';
import { openRouterConnected } from '../utils/openrouter-status';

@Component({
  selector: 'app-connect-openrouter-dialog',
  imports: [MatDialogModule, MatButtonModule, OpenRouterSettings],
  template: `
    <h2 mat-dialog-title>Connect OpenRouter to continue</h2>
    <mat-dialog-content><app-openrouter-settings /></mat-dialog-content>
    <mat-dialog-actions>
      <button mat-button mat-dialog-close>Close</button>
    </mat-dialog-actions>
  `,
})
export class ConnectOpenRouterDialog {
  constructor(client: OpenRouterClient, dialog: MatDialogRef<ConnectOpenRouterDialog>) {
    client.connectionSaved.pipe(takeUntilDestroyed()).subscribe(() => dialog.close());
  }
}

@Injectable({ providedIn: 'root' })
export class OpenRouterAccess {
  constructor(
    private client: OpenRouterClient,
    private dialog: MatDialog,
    private snackbar: MatSnackBar,
  ) {}

  initialize() {
    this.client.status().subscribe({
      error: () => {
        /* A settings refresh can retry. */
      },
    });
  }

  keyStatus() {
    return this.client.keyStatus();
  }

  openConnectDialog() {
    return this.dialog.open(ConnectOpenRouterDialog, { width: '560px', maxWidth: '95vw' });
  }

  withKey(action: () => void, isCurrent: () => boolean = () => true) {
    this.client.status().subscribe({
      next: (status) => {
        if (!isCurrent()) return;
        if (openRouterConnected(status.key)) action();
        else this.openConnectDialog();
      },
      error: () => {
        if (isCurrent()) {
          this.snackbar.open('Cannot check OpenRouter connection. Try again.', 'Dismiss');
        }
      },
    });
  }
}
