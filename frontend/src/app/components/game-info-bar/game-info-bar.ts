import { Component, EventEmitter, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatChipsModule } from '@angular/material/chips';
import { MatIconModule } from '@angular/material/icon';
import { Game } from '../../models/tournament.model';
import { CurrencyPipe } from '@angular/common';
import { ProviderLogoComponent } from '../provider-logo/provider-logo';

@Component({
  selector: 'app-game-info-bar',
  imports: [
    FormsModule,
    MatButtonModule,
    MatCardModule,
    MatChipsModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    CurrencyPipe,
    ProviderLogoComponent,
  ],
  templateUrl: './game-info-bar.html',
  styleUrl: './game-info-bar.scss',
})
export class GameInfoBar {
  @Input() game!: Game;
  @Input() retryInfo: string | null = null;
  /** Saving a raised output-token limit is in progress. */
  @Input() saving = false;
  /** Emits the new output-token limit; the parent saves it and resumes the game. */
  @Output() raiseLimitAndResume = new EventEmitter<number>();

  static readonly MIN_OUTPUT_TOKENS = 128;
  static readonly MAX_OUTPUT_TOKENS = 32768;

  /** Value of the raise-limit input; prefilled with double the current limit. */
  newMaxOutputTokens: number | null = null;
  private currentLimit: number | null = null;

  /**
   * The tournament's current output-token limit when the game is paused because an answer was cut off at
   * it, otherwise null. Shows the raise-limit control.
   */
  @Input()
  set truncationLimit(value: number | null) {
    if (value === this.currentLimit) {
      return;
    }
    this.currentLimit = value;
    this.newMaxOutputTokens =
      value == null ? null : Math.min(GameInfoBar.MAX_OUTPUT_TOKENS, value * 2);
  }

  get truncationLimit(): number | null {
    return this.currentLimit;
  }

  showRaiseLimit(): boolean {
    return this.game?.status === 'PAUSED' && !!this.game.statusDetail && this.currentLimit !== null;
  }

  canSaveLimit(): boolean {
    const value = this.newMaxOutputTokens;
    return (
      !this.saving &&
      value != null &&
      Number.isInteger(value) &&
      value >= GameInfoBar.MIN_OUTPUT_TOKENS &&
      value <= GameInfoBar.MAX_OUTPUT_TOKENS
    );
  }

  saveAndResume(): void {
    if (this.canSaveLimit()) {
      this.raiseLimitAndResume.emit(this.newMaxOutputTokens!);
    }
  }

  getStatusColor(): string {
    switch (this.game.status) {
      case 'IN_PROGRESS':
        return 'accent';
      case 'COMPLETED':
        return 'primary';
      case 'FORFEIT':
        return 'warn';
      default:
        return '';
    }
  }

  getResultText(): string {
    if (!this.game.result) return '';
    const reason = this.game.resultReason ? ` (${this.game.resultReason})` : '';
    switch (this.game.result) {
      case 'WHITE_WINS':
        return `${this.game.whitePlayerName} wins${reason}`;
      case 'BLACK_WINS':
        return `${this.game.blackPlayerName} wins${reason}`;
      case 'DRAW':
        return `Draw${reason}`;
      case 'WHITE_FORFEIT':
        return `${this.game.whitePlayerName} forfeits`;
      case 'BLACK_FORFEIT':
        return `${this.game.blackPlayerName} forfeits`;
      default:
        return this.game.result;
    }
  }
}
