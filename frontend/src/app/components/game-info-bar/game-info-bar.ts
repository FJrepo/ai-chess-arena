import { Component, Input } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatIconModule } from '@angular/material/icon';
import { Game } from '../../models/tournament.model';
import { CurrencyPipe } from '@angular/common';
import { ProviderLogoComponent } from '../provider-logo/provider-logo';

@Component({
  selector: 'app-game-info-bar',
  imports: [MatCardModule, MatChipsModule, MatIconModule, CurrencyPipe, ProviderLogoComponent],
  templateUrl: './game-info-bar.html',
  styleUrl: './game-info-bar.scss',
})
export class GameInfoBar {
  @Input() game!: Game;
  @Input() retryInfo: string | null = null;

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
