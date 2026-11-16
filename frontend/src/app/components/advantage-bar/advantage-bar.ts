import { Component, input, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import {
  PositionEvaluation,
  Side,
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

  displayScore = computed(() => evaluationLabel(this.evaluation()));

  /** Title and accessible label for the unknown and checkmate states; null (no attribute) otherwise. */
  description = computed(() => evaluationDescription(this.evaluation()));

  barHeight = computed(() => whiteBarShare(this.evaluation()));
}
