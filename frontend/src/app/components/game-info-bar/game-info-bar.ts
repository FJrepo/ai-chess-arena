import { Component, EventEmitter, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatChipsModule } from '@angular/material/chips';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { Game, GameResignation, SideQuality } from '../../models/tournament.model';
import { CurrencyPipe } from '@angular/common';
import { ProviderLogoComponent } from '../provider-logo/provider-logo';
import { gameStatusLabel, resultReasonLabel } from '../../utils/status-labels';
import { qualityCounts, sideQualityTooltip } from '../../utils/move-quality';
import {
  ReasoningEffort,
  reasoningEffortLabel,
  suggestedLowerEffort,
} from '../../utils/reasoning-effort';

/**
 * The reasoning effort of the side whose answer was cut off, for the pause banner: its participant, the level it
 * asks for now and the levels to offer.
 */
export interface TruncationEffortChoice {
  participantId: string;
  playerName: string;
  /** The level the side asks for now (its override, else the tournament's); null for the model default. */
  current: ReasoningEffort | null;
  /** The tournament's level, which "Tournament setting" falls back to; null for the model default. */
  tournamentLevel: ReasoningEffort | null;
  /** The levels to offer, from least to most; empty when the model offers no effort levels. */
  options: ReasoningEffort[];
  /** Whether `options` are the model's own levels (from the catalog) or every level (catalog unavailable). */
  fromCatalog: boolean;
}

/** What "Save and resume" asks for: a changed limit and, when offered, the participant's new override. */
export interface RaiseLimitRequest {
  /** Absent when the field still contains the current tournament limit. */
  maxOutputTokens?: number;
  /** Absent when no reasoning-effort choice was offered; null follows the tournament's level. */
  reasoningEffort?: ReasoningEffort | null;
}

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
    MatSelectModule,
    CurrencyPipe,
    ProviderLogoComponent,
  ],
  templateUrl: './game-info-bar.html',
  styleUrl: './game-info-bar.scss',
})
export class GameInfoBar {
  private currentGame!: Game;

  @Input()
  set game(value: Game) {
    if (this.currentGame && (this.currentGame.id !== value.id || value.status !== 'PAUSED')) {
      this.outputLimitEdited = false;
      this.effortDraftParticipantId = null;
      if (this.currentGame.id !== value.id) {
        this.effortChoice = null;
        this.prefillOutputLimit();
      }
    }
    this.currentGame = value;
  }

  get game(): Game {
    return this.currentGame;
  }
  @Input() retryInfo: string | null = null;
  /** Saving a raised output-token limit is in progress. */
  @Input() saving = false;
  /** Emits the new output-token limit (and reasoning effort); the parent saves them and resumes the game. */
  @Output() raiseLimitAndResume = new EventEmitter<RaiseLimitRequest>();

  /** Reader-facing status label for the chip. */
  readonly statusLabel = gameStatusLabel;
  readonly qualityCounts = qualityCounts;
  readonly sideQualityTooltip = sideQualityTooltip;

  static readonly MIN_OUTPUT_TOKENS = 128;
  static readonly MAX_OUTPUT_TOKENS = 262144;

  /** Suggested limit, bounded by the model maximum but never below the current tournament limit. */
  newMaxOutputTokens: number | null = null;
  private currentLimit: number | null = null;
  private outputLimitEdited = false;

  editOutputLimit(value: number | null): void {
    this.newMaxOutputTokens = value;
    this.outputLimitEdited = true;
  }

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
    this.prefillOutputLimit();
  }

  get truncationLimit(): number | null {
    return this.currentLimit;
  }

  private modelMaximum: number | null = null;

  @Input()
  set truncationModelMaximum(value: number | null) {
    if (value !== this.modelMaximum) {
      this.modelMaximum = value;
      this.prefillOutputLimit();
    }
  }

  /** Bound new suggestions by the model maximum while allowing the current tournament limit. */
  allowedOutputMaximum(): number {
    return Math.min(
      GameInfoBar.MAX_OUTPUT_TOKENS,
      Math.max(this.currentLimit ?? 0, this.modelMaximum ?? GameInfoBar.MAX_OUTPUT_TOKENS),
    );
  }

  outputLimitHint(): string {
    const range = `Currently ${this.currentLimit}; 128–${this.allowedOutputMaximum()}, includes reasoning tokens`;
    return this.modelMaximum != null &&
      this.currentLimit != null &&
      this.modelMaximum < this.currentLimit
      ? `${range}. This model is capped at ${this.modelMaximum}; keeping the field unchanged preserves the tournament limit.`
      : range;
  }

  private prefillOutputLimit(): void {
    if (this.outputLimitEdited) return;
    this.newMaxOutputTokens =
      this.currentLimit == null
        ? null
        : Math.min(this.allowedOutputMaximum(), this.currentLimit * 2);
  }

  readonly reasoningEffortLabel = reasoningEffortLabel;
  /** Value of the reasoning-effort select; pre-selects a lower level (low) for the cut-off side. */
  newReasoningEffort: ReasoningEffort | null = null;
  private effortChoice: TruncationEffortChoice | null = null;
  private effortDraftParticipantId: string | null = null;

  editReasoningEffort(value: ReasoningEffort | null): void {
    this.newReasoningEffort = value;
    this.effortDraftParticipantId = this.effortChoice?.participantId ?? null;
  }

  /** The cut-off side's reasoning effort to offer next to the limit; null hides the select. */
  @Input()
  set truncationEffort(value: TruncationEffortChoice | null) {
    const previous = this.effortChoice;
    this.effortChoice = value;
    if (this.effortDraftParticipantId) {
      if (
        !value ||
        (value.participantId === this.effortDraftParticipantId &&
          (this.newReasoningEffort === null || value.options.includes(this.newReasoningEffort)))
      )
        return;
      this.effortDraftParticipantId = null;
    }
    const sameChoice =
      previous != null &&
      value != null &&
      previous.participantId === value.participantId &&
      previous.current === value.current &&
      previous.options.join() === value.options.join();
    if (!sameChoice) {
      this.newReasoningEffort = value ? suggestedLowerEffort(value.current, value.options) : null;
    }
  }

  get truncationEffort(): TruncationEffortChoice | null {
    return this.effortChoice;
  }

  /** Whether the pause banner offers a reasoning-effort select (the model offers levels). */
  showEffortSelect(): boolean {
    return this.showRaiseLimit() && (this.effortChoice?.options.length ?? 0) > 0;
  }

  /** E.g. "Tournament setting (Low)" or "Tournament setting (model default)". */
  tournamentEffortOptionLabel(): string {
    const level = this.effortChoice?.tournamentLevel;
    return `Tournament setting (${level ? reasoningEffortLabel(level) : 'model default'})`;
  }

  /** One line under the select: the current level and what the choice does. */
  effortHint(): string {
    const choice = this.effortChoice;
    if (!choice) {
      return '';
    }
    const current = choice.current ? reasoningEffortLabel(choice.current) : 'model default';
    return choice.fromCatalog
      ? `Currently ${current}; saved for ${choice.playerName} from the next turn`
      : `Currently ${current}; sent as the nearest level the model supports`;
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
      value <= this.allowedOutputMaximum()
    );
  }

  saveAndResume(): void {
    if (!this.canSaveLimit()) {
      return;
    }
    const request: RaiseLimitRequest = {};
    if (this.newMaxOutputTokens !== this.currentLimit) {
      request.maxOutputTokens = this.newMaxOutputTokens!;
    }
    if (this.showEffortSelect()) {
      request.reasoningEffort = this.newReasoningEffort;
    }
    this.raiseLimitAndResume.emit(request);
  }

  /** The reasoning effort a side asks for, e.g. "low"; null for the model default and human sides. */
  sideEffort(color: 'WHITE' | 'BLACK'): ReasoningEffort | null {
    const effort =
      color === 'WHITE' ? this.game?.whiteReasoningEffort : this.game?.blackReasoningEffort;
    return effort ?? null;
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

  adjudicationText(): string | null {
    const evidence = this.game?.adjudication;
    if (this.game?.resultReason !== 'ADJUDICATION' || !evidence) {
      return null;
    }
    const color = evidence.color === 'WHITE' ? 'White' : 'Black';
    const evaluation =
      evidence.evaluationMate != null
        ? `mate in ${evidence.evaluationMate}`
        : evidence.evaluationCp != null
          ? `+${(evidence.evaluationCp / 100).toFixed(1)}`
          : null;
    return `Adjudicated: decisive for ${color} over the last ${evidence.plies} plies${evaluation ? ` (${evaluation})` : ''}`;
  }

  getResultText(): string {
    if (!this.game.result) return '';
    if (this.game.resultReason === 'ADJUDICATION') {
      if (this.game.result === 'WHITE_WINS') return 'White wins by adjudication';
      if (this.game.result === 'BLACK_WINS') return 'Black wins by adjudication';
    }
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
