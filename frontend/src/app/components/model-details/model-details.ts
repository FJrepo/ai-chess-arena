import { Component, computed, input } from '@angular/core';
import { OpenRouterModelOption } from '../../models/openrouter.model';
import {
  formatIntelligenceIndex,
  formatPrice,
  formatReasoning,
  formatReasoningEfforts,
  formatReleaseDate,
  formatTokenCount,
} from '../../utils/model-catalog';
import { providerDisplayName } from '../../utils/provider-brand';
import { ProviderLogoComponent } from '../provider-logo/provider-logo';

/** Facts about one OpenRouter model for choosing a participant; "n/a" for anything the catalog lacks. */
@Component({
  selector: 'app-model-details',
  imports: [ProviderLogoComponent],
  templateUrl: './model-details.html',
  styleUrl: './model-details.scss',
})
export class ModelDetails {
  readonly model = input.required<OpenRouterModelOption>();

  readonly providerName = computed(() =>
    providerDisplayName(this.model().provider, this.model().id),
  );

  readonly facts = computed(() => {
    const model = this.model();
    return [
      { label: 'Released', value: formatReleaseDate(model.releasedAt) },
      { label: 'Input / 1M tokens', value: formatPrice(model.promptPricePerMillion) },
      { label: 'Output / 1M tokens', value: formatPrice(model.completionPricePerMillion) },
      { label: 'Cache read / 1M tokens', value: formatPrice(model.cacheReadPricePerMillion) },
      { label: 'Context window', value: formatTokenCount(model.contextLength) },
      { label: 'Max output tokens', value: formatTokenCount(model.maxCompletionTokens) },
      { label: 'Reasoning', value: formatReasoning(model) },
      { label: 'Effort levels', value: formatReasoningEfforts(model) },
      { label: 'Intelligence Index', value: formatIntelligenceIndex(model.intelligenceIndex) },
    ];
  });
}
