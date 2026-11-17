import { Component, input, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import {
  PositionEvaluation,
  Side,
  evaluationAccessibleLabel,
  evaluationDescription,
  evaluationLabel,
  hasEvaluation,
  whiteBarShare,
} from '../../utils/position-evaluation';

@Component({
  selector: 'app-advantage-bar',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './advantage-bar.html',
  styleUrl: './advantage-bar.scss',
})
export class AdvantageBar {
  /** Centipawns from White's view; null when unknown. */
  cp = input<number | null>(null);
  /** Moves until mate from White's view; null when there is none or it is unknown. */
  mate = input<number | null>(null);
  /** The side checkmated in the shown position, if any. */
  checkmated = input<Side | null>(null);

  private evaluation = computed<PositionEvaluation>(() => ({
    cp: this.cp(),
    mate: this.mate(),
    checkmated: this.checkmated(),
  }));

  /** False when the position has no evaluation; the bar then shows a neutral, unknown state. */
  known = computed(() => hasEvaluation(this.evaluation()));

  private fullScore = computed(() => evaluationLabel(this.evaluation()));

  displayScore = computed(() => {
    const score = this.fullScore();
    if (score.length <= 6) return score;

    // Exceptional engine scores use scientific notation to keep the board width stable.
    const { cp, mate } = this.evaluation();
    if (mate != null) {
      return `${mate < 0 ? '-' : ''}M${Math.abs(mate).toExponential(0).replace('e+', 'e')}`;
    }
    if (cp == null) return score;
    return `${cp > 0 ? '+' : ''}${(cp / 100).toExponential(1).replace('e+', 'e')}`;
  });

  /** Accessible name of the bar (role "img"), in every state. */
  accessibleLabel = computed(() => evaluationAccessibleLabel(this.evaluation()));

  description = computed(() =>
    this.displayScore() === this.fullScore()
      ? evaluationDescription(this.evaluation())
      : this.accessibleLabel(),
  );

  barHeight = computed(() => whiteBarShare(this.evaluation()));
}
