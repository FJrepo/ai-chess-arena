import { Component, EventEmitter, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatChipsModule } from '@angular/material/chips';
import { MatIconModule } from '@angular/material/icon';
import { Game, GameResignation, SideQuality } from '../../models/tournament.model';
import { CurrencyPipe } from '@angular/common';
import { ProviderLogoComponent } from '../provider-logo/provider-logo';
import { gameStatusLabel, resultReasonLabel } from '../../utils/status-labels';
import { qualityCounts, sideQualityTooltip } from '../../utils/move-quality';

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

  /** Reader-facing status label for the chip. */
  readonly statusLabel = gameStatusLabel;
  readonly qualityCounts = qualityCounts;
  readonly sideQualityTooltip = sideQualityTooltip;

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

  /** A side's move-quality summary once at least one of its moves has been judged; null before. */
  sideQuality(color: 'WHITE' | 'BLACK'): SideQuality | null {
    const summary = this.game?.qualitySummary;
    if (!summary) {
      return null;
    }
    const side = color === 'WHITE' ? summary.white : summary.black;
    return side && side.analyzedMoves > 0 ? side : null;
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

  /** The name of a side, falling back to its colour. */
  private sideName(color: 'WHITE' | 'BLACK'): string {
    return color === 'WHITE'
      ? this.game.whitePlayerName || 'White'
      : this.game.blackPlayerName || 'Black';
  }

  /** The resignation details of a game that ended by one; null otherwise. */
  resignation(): GameResignation | null {
    return this.game?.resultReason === 'RESIGNATION' ? (this.game.resignation ?? null) : null;
  }

  /**
   * E.g. "Black Bot resigned instead of playing move 23, at 46% winning chances (not losing)". The winning
   * chance is left out while Stockfish's evaluation of the position is unknown.
   */
  resignationText(resignation: GameResignation): string {
    const who = `${this.sideName(resignation.color)} resigned instead of playing move ${resignation.moveNumber}`;
    if (resignation.winChance == null) {
      return who;
    }
    const chance = `${Math.round(resignation.winChance * 100)}% winning chances`;
    return `${who}, at ${chance}${resignation.notLosing ? ' (not losing)' : ''}`;
  }

  /** E.g. "Last message from White Bot (move 22)". */
  opponentMessageLabel(resignation: GameResignation): string {
    const opponent = this.sideName(resignation.color === 'WHITE' ? 'BLACK' : 'WHITE');
    return resignation.opponentMessageMoveNumber != null
      ? `Last message from ${opponent} (move ${resignation.opponentMessageMoveNumber})`
      : `Last message from ${opponent}`;
  }

  getResultText(): string {
    if (!this.game.result) return '';
    if (this.game.resultReason === 'RESIGNATION') {
      // "Black Bot resigned; White Bot wins"
      if (this.game.result === 'WHITE_WINS') {
        return `${this.sideName('BLACK')} resigned; ${this.sideName('WHITE')} wins`;
      }
      if (this.game.result === 'BLACK_WINS') {
        return `${this.sideName('WHITE')} resigned; ${this.sideName('BLACK')} wins`;
      }
    }
    const phrase = resultReasonLabel(this.game.resultReason);
    const reason = phrase ? ` ${phrase}` : '';
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
