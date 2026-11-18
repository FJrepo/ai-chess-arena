import { Component, computed, input } from '@angular/core';
import { MatExpansionModule } from '@angular/material/expansion';
import type { Game, MoveAttempt } from '../../models/tournament.model';
import {
  configurationDifferences,
  configurationFields,
  requestedEffortLabel,
} from '../../utils/game-configuration';
import { reasoningEffortLabel } from '../../utils/reasoning-effort';

@Component({
  selector: 'app-game-configuration-history',
  imports: [MatExpansionModule],
  templateUrl: './game-configuration-history.html',
  styleUrl: './game-configuration-history.scss',
})
export class GameConfigurationHistory {
  readonly game = input.required<Game>();
  readonly attempts = input.required<MoveAttempt[]>();

  readonly startingFields = computed(() => {
    const starting = this.game().startingConfiguration;
    return starting ? configurationFields(starting) : null;
  });

  readonly revisions = computed(() => {
    const game = this.game();
    const history = game.configurationHistory ?? [];
    return history.map((entry, index) => {
      const previous = history[index - 1]?.settings ?? game.startingConfiguration;
      return {
        revision: entry.revision,
        fields: previous
          ? configurationDifferences(previous, entry.settings)
          : configurationFields(entry.settings).map(({ setting, value }) => ({
              setting,
              before: 'Unknown',
              after: value,
            })),
      };
    });
  });

  private readonly settingsByRevision = computed(
    () =>
      new Map(
        this.game().configurationHistory?.map(({ revision, settings }) => [revision, settings]),
      ),
  );

  readonly reportingCount = computed(
    () => this.attempts().filter((attempt) => attempt.reasoningTokens != null).length,
  );

  requested(attempt: MoveAttempt): string {
    const settings = this.settingsByRevision().get(attempt.configurationRevision ?? 0);
    return requestedEffortLabel(attempt.color === 'WHITE' ? settings?.white : settings?.black);
  }

  sent(attempt: MoveAttempt): string {
    return (
      reasoningEffortLabel(attempt.reasoningEffort) ||
      (attempt.turnStatusRevision == null ? 'Unknown' : 'No explicit effort')
    );
  }
}
