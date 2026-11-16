import { Component, computed, input, linkedSignal, model, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { OpenRouterModelOption } from '../../models/openrouter.model';
import {
  ModelSort,
  filterModels,
  groupByProvider,
  modelMetaParts,
  providerOptions,
  sortModels,
} from '../../utils/model-catalog';
import { ProviderLogoComponent } from '../provider-logo/provider-logo';

/**
 * One search field for the whole OpenRouter catalog: suggestions while typing (every word must match name, id
 * or provider), grouped by provider, with provider, reasoning and free filters and a sort order. Arrow keys
 * and Enter select; editing or clearing the text clears the selection.
 */
@Component({
  selector: 'app-model-picker',
  imports: [
    FormsModule,
    MatAutocompleteModule,
    MatButtonModule,
    MatChipsModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSelectModule,
    ProviderLogoComponent,
  ],
  templateUrl: './model-picker.html',
  styleUrl: './model-picker.scss',
})
export class ModelPicker {
  /** The whole catalog; filtering happens here. */
  readonly models = input<OpenRouterModelOption[]>([]);
  /** Configured featured providers in their order; listed first. */
  readonly featuredProviders = input<string[]>([]);
  readonly disabled = input(false);
  /** Id of the selected model, '' when none. */
  readonly selectedId = model('');

  readonly providerFilter = signal('');
  readonly reasoningOnly = signal(false);
  readonly freeOnly = signal(false);
  readonly sort = signal<ModelSort>('newest');
  readonly sortOptions: Array<{ value: ModelSort; label: string }> = [
    { value: 'newest', label: 'Newest' },
    { value: 'cheapest', label: 'Cheapest' },
    { value: 'intelligence', label: 'Intelligence Index' },
  ];

  readonly selectedModel = computed(
    () => this.models().find((option) => option.id === this.selectedId()) ?? null,
  );

  /**
   * What the field holds: the selected model, or the typed text. Typed text survives the selection being
   * cleared by an edit; a selection cleared from outside (after adding a participant) empties the field.
   */
  readonly inputValue = linkedSignal<OpenRouterModelOption | null, OpenRouterModelOption | string>({
    source: this.selectedModel,
    computation: (selected, previous) =>
      selected ?? (typeof previous?.value === 'string' ? previous.value : ''),
  });

  /** The search text; empty while a model is selected so reopening the list shows every option. */
  readonly query = computed(() => {
    const value = this.inputValue();
    return typeof value === 'string' ? value : '';
  });

  readonly matches = computed(() =>
    sortModels(
      filterModels(this.models(), {
        query: this.query(),
        provider: this.providerFilter(),
        reasoningOnly: this.reasoningOnly(),
        freeOnly: this.freeOnly(),
      }),
      this.sort(),
    ),
  );

  readonly groups = computed(() => groupByProvider(this.matches(), this.featuredProviders()));
  readonly providers = computed(() => providerOptions(this.models(), this.featuredProviders()));

  readonly displayModel = (value: OpenRouterModelOption | string | null): string =>
    typeof value === 'string' ? value : (value?.name ?? '');

  metaLine(option: OpenRouterModelOption): string {
    return modelMetaParts(option).join(' · ');
  }

  /** Typed text; option selections arrive through {@link onSelected}. */
  onInput(value: OpenRouterModelOption | string | null): void {
    if (typeof value !== 'string') {
      return;
    }
    this.inputValue.set(value);
    if (this.selectedId()) {
      this.selectedId.set('');
    }
  }

  onSelected(option: OpenRouterModelOption): void {
    this.inputValue.set(option);
    this.selectedId.set(option.id);
  }

  clear(): void {
    this.inputValue.set('');
    if (this.selectedId()) {
      this.selectedId.set('');
    }
  }
}
