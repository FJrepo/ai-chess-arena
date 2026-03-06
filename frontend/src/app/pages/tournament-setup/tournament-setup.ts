import { Component, OnDestroy, OnInit, signal } from '@angular/core';
import { Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatListModule } from '@angular/material/list';
import { MatDividerModule } from '@angular/material/divider';
import { MatSelectModule } from '@angular/material/select';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ApiService, PromptTemplateResponse } from '../../services/api.service';
import { Participant, Tournament } from '../../models/tournament.model';
import { OpenRouterModelOption } from '../../models/openrouter.model';
import { ProviderLogoComponent } from '../../components/provider-logo/provider-logo';
import { providerDisplayName as resolveProviderDisplayName } from '../../utils/provider-brand';

@Component({
  selector: 'app-tournament-setup',
  imports: [
    FormsModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatIconModule,
    MatSlideToggleModule,
    MatListModule,
    MatDividerModule,
    MatSelectModule,
    MatProgressSpinnerModule,
    ProviderLogoComponent,
  ],
  templateUrl: './tournament-setup.html',
  styleUrl: './tournament-setup.scss',
})
export class TournamentSetup implements OnInit, OnDestroy {
  private static readonly PROMPT_FALLBACK = `You are playing chess in a tournament.

Respond with valid JSON only:
{
  "move": "<SAN move>",
  "message": "<optional short message>"
}

You are playing as %s.
Your opponent is %s (%s).`;

  name = '';
  systemRulesTemplate = '';
  sharedCustomInstructions = '';
  moveTimeout = 60;
  maxRetries = 3;
  trashTalkEnabled = true;
  drawPolicy: Tournament['drawPolicy'] = 'WHITE_ADVANCES';
  matchupBestOf: Tournament['matchupBestOf'] = 1;
  finalsBestOf: Tournament['finalsBestOf'] = null;

  newPlayerName = '';
  newModelId = '';
  newCustomInstructions = '';
  modelSearch = '';
  showAllModels = false;

  participants = signal<Partial<Participant>[]>([]);
  models = signal<OpenRouterModelOption[]>([]);
  modelsLoading = signal(false);
  modelsError = signal<string | null>(null);
  openRouterReady = signal<boolean | null>(null);
  promptTemplateVersion = signal<string | null>(null);
  promptTemplateLoadError = signal<string | null>(null);
  featuredCount = signal(0);
  totalMatched = signal(0);
  drawPolicyOptions: Array<{ value: Tournament['drawPolicy']; label: string }> = [
    { value: 'WHITE_ADVANCES', label: 'White Advances' },
    { value: 'BLACK_ADVANCES', label: 'Black Advances' },
    { value: 'HIGHER_SEED_ADVANCES', label: 'Higher Seed Advances' },
    { value: 'RANDOM_ADVANCES', label: 'Random Advances' },
    { value: 'REPLAY_GAME', label: 'Replay Game' },
  ];
  bestOfOptions: Tournament['matchupBestOf'][] = [1, 3, 5, 7];

  private searchDebounceTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(
    private api: ApiService,
    private router: Router,
  ) {}

  ngOnInit(): void {
    this.loadPromptTemplate();
    this.loadOpenRouterStatus();
    this.loadModels();
  }

  ngOnDestroy(): void {
    if (this.searchDebounceTimer) {
      clearTimeout(this.searchDebounceTimer);
      this.searchDebounceTimer = null;
    }
  }

  private loadOpenRouterStatus(): void {
    this.api.checkOpenRouterStatus().subscribe({
      next: (status) => this.openRouterReady.set(status.valid),
      error: () => this.openRouterReady.set(false),
    });
  }

  private loadPromptTemplate(): void {
    this.promptTemplateLoadError.set(null);
    this.api.getPromptTemplate().subscribe({
      next: (response: PromptTemplateResponse) => {
        this.systemRulesTemplate = response.template;
        this.promptTemplateVersion.set(response.version);
      },
      error: () => {
        this.systemRulesTemplate = TournamentSetup.PROMPT_FALLBACK;
        this.promptTemplateVersion.set('fallback');
        this.promptTemplateLoadError.set(
          'Failed to load prompt template from backend. Using local fallback.',
        );
      },
    });
  }

  private loadModels(): void {
    this.modelsLoading.set(true);
    this.modelsError.set(null);
    const limit = this.showAllModels ? 250 : 120;

    this.api
      .getModels({
        featuredOnly: !this.showAllModels,
        q: this.modelSearch,
        limit,
      })
      .subscribe({
        next: (response) => {
          const models = response.data ?? [];
          this.models.set(models);
          this.totalMatched.set(response.totalMatched ?? models.length);
          this.featuredCount.set(
            response.featuredCount ?? models.filter((model) => model.featured).length,
          );
          if (response.error) {
            this.modelsError.set(response.error);
            return;
          }

          if (models.length === 0) {
            this.modelsError.set('No models match the current filters.');
          }
        },
        error: () => {
          this.models.set([]);
          this.totalMatched.set(0);
          this.featuredCount.set(0);
          this.modelsError.set('Failed to fetch models from OpenRouter.');
          this.modelsLoading.set(false);
        },
        complete: () => this.modelsLoading.set(false),
      });
  }

  onSearchChanged(): void {
    if (this.searchDebounceTimer) {
      clearTimeout(this.searchDebounceTimer);
    }
    this.searchDebounceTimer = setTimeout(() => this.loadModels(), 250);
  }

  onShowAllModelsChanged(): void {
    this.loadModels();
  }

  visibleModels(): OpenRouterModelOption[] {
    return this.models();
  }

  featuredModelCount(): number {
    return this.featuredCount();
  }

  onModelChanged(modelId: string): void {
    this.newModelId = modelId;
    if (this.newPlayerName.trim().length > 0) {
      return;
    }

    const model = this.models().find((option) => option.id === modelId);
    if (model) {
      this.newPlayerName = model.name;
    }
  }

  selectedModel(): OpenRouterModelOption | null {
    return this.models().find((model) => model.id === this.newModelId) ?? null;
  }

  formatContextLength(contextLength: number | null): string {
    if (!contextLength) {
      return 'n/a';
    }

    if (contextLength >= 1_000_000) {
      return `${(contextLength / 1_000_000).toFixed(1)}M`;
    }

    if (contextLength >= 1_000) {
      return `${Math.round(contextLength / 1_000)}K`;
    }

    return `${contextLength}`;
  }

  formatPrice(pricePerMillion: number | null): string {
    if (!pricePerMillion) {
      return 'n/a';
    }
    return `$${pricePerMillion.toFixed(2)}/1M`;
  }

  providerDisplayName(provider?: string | null, modelId?: string | null): string {
    return resolveProviderDisplayName(provider, modelId);
  }

  canAddParticipant(): boolean {
    return this.newPlayerName.trim().length > 0 && this.newModelId.trim().length > 0;
  }

  addParticipant() {
    if (!this.canAddParticipant()) {
      return;
    }

    this.participants.update((list) => [
      ...list,
      {
        playerName: this.newPlayerName.trim(),
        modelId: this.newModelId,
        customInstructions: this.normalizeInstructions(this.newCustomInstructions),
        seed: list.length,
      },
    ]);

    this.newPlayerName = '';
    this.newModelId = '';
    this.newCustomInstructions = '';
    this.modelSearch = '';
  }

  removeParticipant(index: number) {
    this.participants.update((list) => list.filter((_, i) => i !== index));
  }

  create() {
    this.api
      .createTournament({
        name: this.name,
        sharedCustomInstructions: this.normalizeInstructions(this.sharedCustomInstructions),
        moveTimeoutSeconds: this.moveTimeout,
        maxRetries: this.maxRetries,
        matchupBestOf: this.matchupBestOf,
        finalsBestOf: this.finalsBestOf,
        trashTalkEnabled: this.trashTalkEnabled,
        drawPolicy: this.drawPolicy,
      })
      .subscribe((tournament) => {
        const participants = this.participants();
        if (participants.length === 0) {
          this.router.navigate(['/tournaments', tournament.id]);
          return;
        }

        let added = 0;
        for (const p of participants) {
          this.api.addParticipant(tournament.id, p).subscribe(() => {
            added++;
            if (added === participants.length) {
              this.router.navigate(['/tournaments', tournament.id]);
            }
          });
        }
      });
  }

  hasSharedInstructions(): boolean {
    return this.normalizeInstructions(this.sharedCustomInstructions) !== null;
  }

  participantInstructionLabel(participant: Partial<Participant>): string {
    return participant.customInstructions
      ? 'Uses participant-specific instructions'
      : this.hasSharedInstructions()
        ? 'Uses shared tournament instructions'
        : 'Uses default instructions only';
  }

  private normalizeInstructions(value: string | null | undefined): string | null {
    if (!value) {
      return null;
    }
    const trimmed = value.trim();
    return trimmed.length > 0 ? trimmed : null;
  }
}
