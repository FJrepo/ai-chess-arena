import { Component, input, computed } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-advantage-bar',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './advantage-bar.html',
  styleUrl: './advantage-bar.scss',
})
export class AdvantageBar {
  cp = input<number | null>(0);
  mate = input<number | null>(null);

  displayScore = computed(() => {
    const cpVal = this.cp();
    const mateVal = this.mate();

    if (mateVal != null) {
      return mateVal > 0 ? `M${mateVal}` : `-M${Math.abs(mateVal)}`;
    }
    if (cpVal != null) {
      const val = cpVal / 100;
      return val > 0 ? `+${val.toFixed(1)}` : val.toFixed(1);
    }
    return '0.0';
  });

  barHeight = computed(() => {
    const cpVal = this.cp();
    const mateVal = this.mate();

    if (mateVal != null) {
      return mateVal > 0 ? 100 : 0;
    }
    if (cpVal == null) return 50;

    const val = cpVal / 100;
    const percentage = 50 + 50 * (Math.atan(val / 1.5) / (Math.PI / 2));
    return Math.max(5, Math.min(95, percentage));
  });
}
