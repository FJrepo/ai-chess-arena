import { Component, Injectable } from '@angular/core';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatSnackBar } from '@angular/material/snack-bar';
import { OpenRouterSettings } from '../components/openrouter-settings/openrouter-settings';
import { OpenRouterClient } from './openrouter-client';

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
export class ConnectOpenRouterDialog {}

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

  withKey(action: () => void) {
    this.client.status().subscribe({
      next: (status) => {
        if (status.key.source !== 'none') action();
        else this.dialog.open(ConnectOpenRouterDialog, { width: '560px', maxWidth: '95vw' });
      },
      error: () => this.snackbar.open('Cannot check OpenRouter connection. Try again.', 'Dismiss'),
    });
  }
}
