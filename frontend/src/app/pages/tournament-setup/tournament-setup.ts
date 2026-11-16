import { CurrencyPipe } from '@angular/common';
import { Component, OnInit, computed, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSelectModule } from '@angular/material/select';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ApiService, PromptTemplateResponse } from '../../services/api.service';
import { Participant, Tournament } from '../../models/tournament.model';
import { OpenRouterModelOption } from '../../models/openrouter.model';
import { ProviderLogoComponent } from '../../components/provider-logo/provider-logo';
import { ModelPicker } from '../../components/model-picker/model-picker';
import { ModelDetails } from '../../components/model-details/model-details';
import { providerDisplayName as resolveProviderDisplayName } from '../../utils/provider-brand';

/**
 * Prompt tokens one model call sends: system prompt, the last six prior messages and the current turn (the
 * backend bounds the history). Measured by PromptServiceTest over a 70-ply game: about 4,500 characters on
 * average (about 1,600 at the first move, about 5,000 late in the game), roughly 1,400 tokens.
 */
const PROMPT_TOKENS_PER_MOVE = 1400;
/** Completion tokens of a typical non-reasoning answer (JSON move plus a short message). */
const COMPLETION_TOKENS_PER_MOVE = 140;
/**
 * Typical seconds per model answer for the runtime estimate when there is no time limit per move: fast models
 * answer in seconds, reasoning models often take about a minute. Runtime without a limit is open-ended.
 */
export const TYPICAL_MOVE_SECONDS_WITHOUT_LIMIT = 30;
/** Moves (plies) per game assumed by the estimate. */
const ESTIMATED_MOVES_PER_GAME = 70;

type SetupParticipant = Partial<Participant> & {
  promptPricePerMillion?: number | null;
  completionPricePerMillion?: number | null;
};

type TournamentConfidence = {
  participantCount: number;
  totalSeries: number;
  minGames: number;
  likelyGames: number;
  maxGames: number;
  pricingCoverage: number;
  likelyCostUsd: number | null;
  maxCostUsd: number | null;
  likelyRuntimeMinutes: number;
  /** Null when runtime is open-ended because there is no time limit per move. */
  maxRuntimeMinutes: number | null;
  paceLabel: string;
  riskBand: 'Safe' | 'Moderate' | 'Expensive';
  riskReason: string;
  drawCanExtendSeries: boolean;
};

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
    MatSelectModule,
    MatProgressSpinnerModule,
    CurrencyPipe,
    ProviderLogoComponent,
    ModelPicker,
    ModelDetails,
  ],
  templateUrl: './tournament-setup.html',
  styleUrl: './tournament-setup.scss',
})
export class TournamentSetup implements OnInit {
  private static readonly PROMPT_FALLBACK = `You are playing chess in a tournament match. This is a real competition.

Respond with valid JSON only:
{
  "move": "<SAN move>",
  "message": "<optional short message>"
}

You are playing as %s.
Your opponent is %s (%s).`;

  /** Backed by a signal so the launch readiness below tracks it; still bound with ngModel. */
  private readonly nameValue = signal('');
  get name(): string {
    return this.nameValue();
  }
  set name(value: string) {
    this.nameValue.set(value);
  }
  systemRulesTemplate = '';
  sharedCustomInstructions = '';
  /** Off: no time limit per move (sent as moveTimeoutSeconds 0). */
  limitMoveTime = true;
  readonly typicalMoveSecondsWithoutLimit = TYPICAL_MOVE_SECONDS_WITHOUT_LIMIT;
  /** Seconds per move (10-3600) while limitMoveTime is on. */
  moveTimeout = 60;
  maxRetries = 3;
  /** Cap on generated tokens per answer (128-32768); includes reasoning tokens. */
  maxOutputTokens = 4096;
  trashTalkEnabled = true;
  drawPolicy: Tournament['drawPolicy'] = 'WHITE_ADVANCES';
  matchupBestOf: Tournament['matchupBestOf'] = 1;
  finalsBestOf: Tournament['finalsBestOf'] = null;

  newPlayerName = '';
  newControlType: Participant['controlType'] = 'AI';
  newModelId = '';
  newCustomInstructions = '';

  participants = signal<SetupParticipant[]>([]);
  /** The whole OpenRouter catalog, loaded once; the picker filters it client-side. */
  models = signal<OpenRouterModelOption[]>([]);
  featuredProviders = signal<string[]>([]);
  modelsLoading = signal(false);
  modelsError = signal<string | null>(null);
  openRouterReady = signal<boolean | null>(null);
  promptTemplateVersion = signal<string | null>(null);
  promptTemplateLoadError = signal<string | null>(null);
  drawPolicyOptions: Array<{ value: Tournament['drawPolicy']; label: string }> = [
    { value: 'WHITE_ADVANCES', label: 'White Advances' },
    { value: 'BLACK_ADVANCES', label: 'Black Advances' },
    { value: 'HIGHER_SEED_ADVANCES', label: 'Higher Seed Advances' },
    { value: 'RANDOM_ADVANCES', label: 'Random Advances' },
    { value: 'REPLAY_GAME', label: 'Replay Game' },
  ];
  bestOfOptions: Tournament['matchupBestOf'][] = [1, 3, 5, 7];

  private readonly presets: Record<
    string,
    {
      name: string;
      moveTimeout: number;
      maxRetries: number;
      maxOutputTokens: number;
      drawPolicy: Tournament['drawPolicy'];
      matchupBestOf: Tournament['matchupBestOf'];
      finalsBestOf: Tournament['finalsBestOf'];
      trashTalkEnabled: boolean;
      sharedCustomInstructions: string;
    }
  > = {
    quickstart: {
      name: 'Quick Start Arena',
      moveTimeout: 30,
      maxRetries: 2,
      maxOutputTokens: 4096,
      drawPolicy: 'WHITE_ADVANCES',
      matchupBestOf: 1,
      finalsBestOf: null,
      trashTalkEnabled: true,
      sharedCustomInstructions: '',
    },
    balanced: {
      name: 'Balanced Ladder',
      moveTimeout: 45,
      maxRetries: 3,
      maxOutputTokens: 4096,
      drawPolicy: 'HIGHER_SEED_ADVANCES',
      matchupBestOf: 3,
      finalsBestOf: null,
      trashTalkEnabled: true,
      sharedCustomInstructions: 'Stay practical. Avoid unnecessary risks when ahead.',
    },
    showcase: {
      name: 'Series Showcase',
      moveTimeout: 60,
      maxRetries: 3,
      maxOutputTokens: 8192,
      drawPolicy: 'REPLAY_GAME',
      matchupBestOf: 3,
      finalsBestOf: 5,
      trashTalkEnabled: true,
      sharedCustomInstructions: 'Play your strongest chess and keep messages concise.',
    },
  };

  constructor(
    private api: ApiService,
    private router: Router,
    private route: ActivatedRoute,
  ) {}

  ngOnInit(): void {
    this.route.queryParamMap.subscribe((params) => {
      this.applyPreset(params.get('preset'));
    });
    this.loadPromptTemplate();
    this.loadOpenRouterStatus();
    this.loadModels();
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

  /** Loads the whole catalog (every text-output model, no cap) once; the picker filters it client-side. */
  loadModels(): void {
    this.modelsLoading.set(true);
    this.modelsError.set(null);

    this.api.getModels({ featuredOnly: false, limit: 0 }).subscribe({
      next: (response) => {
        const models = response.data ?? [];
        this.models.set(models);
        this.featuredProviders.set(response.featuredProviders ?? []);
        if (response.error) {
          this.modelsError.set(response.error);
        } else if (models.length === 0) {
          this.modelsError.set('OpenRouter returned no models.');
        }
        this.modelsLoading.set(false);
      },
      error: () => {
        this.models.set([]);
        this.modelsError.set('Failed to fetch models from OpenRouter.');
        this.modelsLoading.set(false);
      },
    });
  }

  /** Selection from the picker ('' when cleared); fills an empty display name with the model's name. */
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

  providerDisplayName(provider?: string | null, modelId?: string | null): string {
    return resolveProviderDisplayName(provider, modelId);
  }

  canAddParticipant(): boolean {
    if (this.newPlayerName.trim().length === 0) {
      return false;
    }
    if (this.newControlType === 'HUMAN') {
      return !this.hasHumanParticipant();
    }
    return this.newModelId.trim().length > 0;
  }

  addParticipant() {
    if (!this.canAddParticipant()) {
      return;
    }

    this.participants.update((list) => [
      ...list,
      {
        playerName: this.newPlayerName.trim(),
        controlType: this.newControlType,
        modelId: this.newControlType === 'AI' ? this.newModelId : null,
        customInstructions:
          this.newControlType === 'AI'
            ? this.normalizeInstructions(this.newCustomInstructions)
            : null,
        promptPricePerMillion:
          this.newControlType === 'AI'
            ? (this.selectedModel()?.promptPricePerMillion ?? null)
            : null,
        completionPricePerMillion:
          this.newControlType === 'AI'
            ? (this.selectedModel()?.completionPricePerMillion ?? null)
            : null,
        seed: list.length,
      },
    ]);

    this.newPlayerName = '';
    this.newControlType = 'AI';
    this.newModelId = '';
    this.newCustomInstructions = '';
  }

  removeParticipant(index: number) {
    this.participants.update((list) => list.filter((_, i) => i !== index));
  }

  creating = signal(false);
  createError = signal<string | null>(null);

  /** One model for the launch checklist and the Create button: a name, at least two participants, not already submitting. */
  readonly readiness = computed(() => {
    const named = !!this.nameValue();
    const count = this.participants().length;
    const participantsOk = count >= 2;
    const creating = this.creating();
    return {
      named,
      count,
      participantsOk,
      creating,
      canCreate: named && participantsOk && !creating,
    };
  });
  /**
   * Request id bound to exactly the submitted details: retrying the same details reuses it (so a request that
   * reached the server but lost its response creates nothing twice), while edited details get a new id.
   */
  private pendingSubmission: { key: string; requestId: string } | null = null;

  create() {
    if (this.creating()) {
      return;
    }
    this.creating.set(true);
    this.createError.set(null);

    const details = {
      name: this.name,
      sharedCustomInstructions: this.normalizeInstructions(this.sharedCustomInstructions),
      // 0 means no time limit per move.
      moveTimeoutSeconds: this.limitMoveTime ? this.moveTimeout : 0,
      maxRetries: this.maxRetries,
      maxOutputTokens: this.maxOutputTokens,
      matchupBestOf: this.matchupBestOf,
      finalsBestOf: this.finalsBestOf,
      trashTalkEnabled: this.trashTalkEnabled,
      drawPolicy: this.drawPolicy,
      participants: this.participants().map((p) => ({
        playerName: p.playerName,
        controlType: p.controlType,
        modelId: p.modelId,
        customInstructions: p.customInstructions ?? null,
        seed: p.seed,
      })),
    };
    const key = JSON.stringify(details);
    if (this.pendingSubmission?.key !== key) {
      this.pendingSubmission = { key, requestId: newRequestId() };
    }

    this.api
      .createTournament({ ...details, clientRequestId: this.pendingSubmission.requestId })
      .subscribe({
        next: (tournament) => {
          this.pendingSubmission = null;
          this.creating.set(false);
          this.router.navigate(['/tournaments', tournament.id]);
        },
        error: (err) => {
          const serverError = err?.error;
          const status = typeof err?.status === 'number' ? err.status : 0;
          const message =
            typeof serverError === 'string' ? serverError : (serverError?.error ?? null);
          this.createError.set(
            status === 0 || status >= 500 || !message
              ? 'The server did not confirm the tournament. Retry with the same details: if it was ' +
                  'saved after all, you will get that tournament instead of a duplicate.'
              : message,
          );
          this.creating.set(false);
        },
      });
  }

  hasSharedInstructions(): boolean {
    return this.normalizeInstructions(this.sharedCustomInstructions) !== null;
  }

  participantInstructionLabel(participant: Partial<Participant>): string {
    if (participant.controlType === 'HUMAN') {
      return 'Human-controlled participant';
    }
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

  confidenceSummary(): TournamentConfidence | null {
    const participantCount = this.participants().length;
    if (participantCount < 2) {
      return null;
    }

    // A knockout bracket with n participants has n - 1 series; the last one is the final.
    const totalSeries = participantCount - 1;
    const openingSeries = totalSeries - 1;
    const finalBestOf = this.finalsBestOf ?? this.matchupBestOf;
    const minGames =
      openingSeries * this.winsRequired(this.matchupBestOf) + this.winsRequired(finalBestOf);
    const maxGames = openingSeries * this.matchupBestOf + finalBestOf;
    const likelyGames = Math.max(minGames, Math.round(minGames + (maxGames - minGames) * 0.4));

    let likelyRuntimeMinutes: number;
    let maxRuntimeMinutes: number | null;
    if (this.limitMoveTime) {
      const fastMinutesPerGame = Math.max(6, Math.round(this.moveTimeout * 0.22));
      const slowMinutesPerGame = Math.max(10, Math.round(this.moveTimeout * 0.42));
      likelyRuntimeMinutes =
        likelyGames * Math.round((fastMinutesPerGame + slowMinutesPerGame) / 2);
      maxRuntimeMinutes = maxGames * slowMinutesPerGame;
    } else {
      // No time limit per move: a typical answer time for the estimate, and no upper bound.
      likelyRuntimeMinutes =
        likelyGames *
        Math.round((ESTIMATED_MOVES_PER_GAME * TYPICAL_MOVE_SECONDS_WITHOUT_LIMIT) / 60);
      maxRuntimeMinutes = null;
    }

    const participantsWithPricing = this.participants().filter(
      (participant) =>
        participant.promptPricePerMillion != null && participant.completionPricePerMillion != null,
    );
    const pricingCoverage = participantsWithPricing.length;

    let averageMoveCostUsd: number | null = null;
    if (participantsWithPricing.length > 0) {
      const totalMoveCost = participantsWithPricing.reduce((sum, participant) => {
        const promptCost =
          ((participant.promptPricePerMillion ?? 0) * PROMPT_TOKENS_PER_MOVE) / 1_000_000;
        const completionCost =
          ((participant.completionPricePerMillion ?? 0) * COMPLETION_TOKENS_PER_MOVE) / 1_000_000;
        return sum + promptCost + completionCost;
      }, 0);
      averageMoveCostUsd = totalMoveCost / participantsWithPricing.length;
    }

    const estimatedMovesPerGame = ESTIMATED_MOVES_PER_GAME;
    const drawCanExtendSeries = this.drawPolicy === 'REPLAY_GAME';
    const likelyCostUsd =
      averageMoveCostUsd == null ? null : likelyGames * estimatedMovesPerGame * averageMoveCostUsd;
    // With REPLAY_GAME a level match continues until a decisive game, so the maximum is open-ended.
    const maxCostUsd =
      averageMoveCostUsd == null ? null : maxGames * estimatedMovesPerGame * averageMoveCostUsd;

    const riskScore =
      (participantCount >= 16 ? 3 : participantCount >= 8 ? 2 : 1) +
      (this.matchupBestOf >= 5 ? 2 : this.matchupBestOf >= 3 ? 1 : 0) +
      (this.finalsBestOf != null && this.finalsBestOf > this.matchupBestOf ? 1 : 0) +
      (!this.limitMoveTime || this.moveTimeout >= 90
        ? 3
        : this.moveTimeout >= 60
          ? 2
          : this.moveTimeout >= 45
            ? 1
            : 0) +
      (drawCanExtendSeries ? 1 : 0) +
      (likelyRuntimeMinutes >= 360 ? 2 : likelyRuntimeMinutes >= 120 ? 1 : 0) +
      (likelyCostUsd != null ? (likelyCostUsd >= 5 ? 2 : likelyCostUsd >= 1 ? 1 : 0) : 0);

    const riskBand = riskScore >= 8 ? 'Expensive' : riskScore >= 5 ? 'Moderate' : 'Safe';
    const riskReason =
      riskBand === 'Expensive'
        ? 'High timeout, longer series, or pricier models make this a heavier run.'
        : riskBand === 'Moderate'
          ? 'This should be manageable, but it is no longer a quick smoke test.'
          : 'Good fit for a first pass or low-risk validation run.';

    return {
      participantCount,
      totalSeries,
      minGames,
      likelyGames,
      maxGames,
      pricingCoverage,
      likelyCostUsd,
      maxCostUsd,
      likelyRuntimeMinutes,
      maxRuntimeMinutes,
      paceLabel: !this.limitMoveTime
        ? 'No limit'
        : this.moveTimeout <= 30
          ? 'Fast'
          : this.moveTimeout <= 60
            ? 'Balanced'
            : 'Slow',
      riskBand,
      riskReason,
      drawCanExtendSeries,
    };
  }

  formatDuration(minutes: number): string {
    const hours = Math.floor(minutes / 60);
    const remainder = minutes % 60;
    if (hours === 0) {
      return `${remainder}m`;
    }
    if (remainder === 0) {
      return `${hours}h`;
    }
    return `${hours}h ${remainder}m`;
  }

  pricingCoverageLabel(summary: TournamentConfidence): string {
    return `${summary.pricingCoverage}/${summary.participantCount} models priced`;
  }

  private winsRequired(bestOf: number): number {
    return Math.floor(bestOf / 2) + 1;
  }

  private applyPreset(presetKey: string | null): void {
    if (!presetKey) {
      return;
    }

    const preset = this.presets[presetKey];
    if (!preset) {
      return;
    }

    this.name = preset.name;
    this.limitMoveTime = true;
    this.moveTimeout = preset.moveTimeout;
    this.maxRetries = preset.maxRetries;
    this.maxOutputTokens = preset.maxOutputTokens;
    this.drawPolicy = preset.drawPolicy;
    this.matchupBestOf = preset.matchupBestOf;
    this.finalsBestOf = preset.finalsBestOf;
    this.trashTalkEnabled = preset.trashTalkEnabled;
    this.sharedCustomInstructions = preset.sharedCustomInstructions;
  }

  hasHumanParticipant(): boolean {
    return this.participants().some((participant) => participant.controlType === 'HUMAN');
  }
}

function newRequestId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}
