import { Component, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { RunCapacity } from '../../models/tournament.model';

@Component({
  selector: 'app-queued-resume',
  imports: [MatButtonModule],
  template: `
    <span role="status">
      Queued: resumes when a slot frees
      @if (capacity(); as slots) {
        ({{ slots.runningGames }}/{{ slots.maxConcurrentGames }} running)
      }
    </span>
    <button
      mat-button
      type="button"
      aria-label="Cancel queued resume"
      [disabled]="cancelDisabled()"
      (click)="cancelRequest.emit()"
    >
      Cancel
    </button>
  `,
  styles: `
    :host {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      color: var(--mat-sys-on-surface-variant);
      font: var(--mat-sys-body-small);
    }
  `,
})
export class QueuedResume {
  readonly capacity = input<RunCapacity | null | undefined>();
  readonly cancelDisabled = input(false);
  readonly cancelRequest = output<void>();
}
