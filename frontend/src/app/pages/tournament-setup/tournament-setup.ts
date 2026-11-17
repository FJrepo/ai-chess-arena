import { OpenRouterAccess } from '../../services/openrouter-access';
import { OpenRouterKeyStatus } from '../../services/openrouter-client';
import { openRouterConnected, openRouterValidation } from '../../utils/openrouter-status';
import { CurrencyPipe } from '@angular/common';
import {
  AfterViewInit,
  Component,
  ElementRef,
  OnDestroy,
  OnInit,
  ViewChild,
  computed,
  signal,
} from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { canonicalBudget, isBudgetDecimal } from '../../utils/tournament-budget';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ApiService, PromptTemplateResponse } from '../../services/api.service';
import {
  BracketDrawMode,
  ChatMode,
  BanterLevel,
  Opening,
  OpeningMode,
  Participant,
  Tournament,
} from '../../models/tournament.model';
import { OpenRouterModelOption } from '../../models/openrouter.model';
import { ProviderLogoComponent } from '../../components/provider-logo/provider-logo';
import { ModelPicker } from '../../components/model-picker/model-picker';
import { ModelDetails } from '../../components/model-details/model-details';
import {
  REASONING_EFFORT_HINT,
  REASONING_EFFORTS,
  ReasoningEffort,
  reasoningEffortLabel,
  supportedEfforts,
} from '../../utils/reasoning-effort';

/**
 * Prompt tokens one model call sends: system prompt, the last six prior messages and the current turn (the
 * backend bounds the history). Measured by PromptServiceTest over a 70-ply game: about 4,500 characters on
 * average (about 1,600 at the first move, about 5,000 late in the game), roughly 1,400 tokens.
 */
const PROMPT_TOKENS_PER_MOVE = 1400;
/** Completion tokens of a typical non-reasoning answer (JSON move plus a short message). */
const COMPLETION_TOKENS_PER_MOVE = 140;
/**
 * Typical seconds per model answer for the runtime estimate: fast models answer in seconds, reasoning models often
 * take about a minute. A time limit per move only bounds the slow end, so the likely runtime never exceeds the one
 * without a limit; without a limit runtime is open-ended.
 */
export const TYPICAL_MOVE_SECONDS = 30;
/**
 * Below this limit the setup page warns that reasoning models may run out of time, and the pace reads "Tight".
 * Running out of time forfeits the game.
 */
export const SHORT_MOVE_TIMEOUT_SECONDS = 90;
/** Moves (plies) per game assumed by the estimate. */
const ESTIMATED_MOVES_PER_GAME = 70;
const MAX_TOURNAMENT_NAME_LENGTH = 255;
const REMATCH_SUFFIX = ' (rematch)';
const MAX_PARTICIPANT_SEED = 2147483647;

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
    RouterLink,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatIconModule,
    MatSlideToggleModule,
    MatButtonToggleModule,
    MatSelectModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
    CurrencyPipe,
    ProviderLogoComponent,
    ModelPicker,
    ModelDetails,
  ],
  templateUrl: './tournament-setup.html',
  styleUrl: './tournament-setup.scss',
})
export class TournamentSetup implements OnInit, AfterViewInit, OnDestroy {
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
  /** Default rules from the backend (OPTIONAL) and, from 1.4 on, the rules of every chat mode. */
  private promptTemplates: PromptTemplateResponse | null = null;
  sharedCustomInstructions = '';
  /** Off: no time limit per move (sent as moveTimeoutSeconds 0). */
  private readonly limitMoveTimeValue = signal(false);
  get limitMoveTime(): boolean {
    return this.limitMoveTimeValue();
  }
  set limitMoveTime(value: boolean) {
    this.limitMoveTimeValue.set(value);
  }
  readonly typicalMoveSeconds = TYPICAL_MOVE_SECONDS;
  /** Seconds per move (10-3600) while limitMoveTime is on. */
  private readonly moveTimeoutValue = signal(120);
  get moveTimeout(): number {
    return this.moveTimeoutValue();
  }
  set moveTimeout(value: number) {
    this.moveTimeoutValue.set(value);
  }
  readonly maxRetries = signal<number | null>(3);
  private readonly validMaxRetries = computed(() => {
    const attempts = this.maxRetries();
    return attempts !== null && Number.isInteger(attempts) && attempts >= 1 && attempts <= 20
      ? attempts
      : null;
  });
  /** Cap on generated tokens per answer (128-262144); includes reasoning tokens. */
  private readonly maxOutputTokensValue = signal(16384);
  get maxOutputTokens(): number {
    return this.maxOutputTokensValue();
  }
  set maxOutputTokens(value: number) {
    this.maxOutputTokensValue.set(value);
  }
  /**
   * Reasoning effort for every model unless a participant overrides it; null is each model's default (the field
   * is then left out of the request, so nothing is sent to OpenRouter).
   */
  reasoningEffort: ReasoningEffort | null = null;
  readonly reasoningEffortOptions = REASONING_EFFORTS.map((value) => ({
    value,
    label: reasoningEffortLabel(value),
  }));
  readonly riskBandLabels = { Safe: 'Low risk', Moderate: 'Moderate risk', Expensive: 'High cost' };
  readonly openingCaptions: Record<OpeningMode, string> = {
    STANDARD: 'Start from the standard position.',
    RANDOM_BOOK: 'Random line per pairing.',
    FIXED: 'Use the selected book line.',
  };
  readonly chatModeCaptions: Record<ChatMode, string> = {
    OFF: 'Models stay silent.',
    OPTIONAL: 'Models may send a message.',
    MANDATORY: 'Messages requested; never penalized.',
  };
  readonly reasoningEffortHint = REASONING_EFFORT_HINT;
  readonly reasoningEffortLabel = reasoningEffortLabel;
  /** Whether models talk; sent as chatMode. */
  chatMode: ChatMode = 'OPTIONAL';
  banterLevel: BanterLevel | null = null;
  readonly banterOptions: ReadonlyArray<{ value: BanterLevel; label: string }> = [
    { value: 'POLITE', label: 'Polite' },
    { value: 'PLAYFUL', label: 'Playful' },
    { value: 'SPICY', label: 'Spicy' },
  ];
  readonly banterHints: Record<BanterLevel, string> = {
    POLITE: 'Friendly, respectful and sporting.',
    PLAYFUL: 'Light, good-natured teasing about the game.',
    SPICY: 'Bold chess trash talk; no slurs, harassment or real-world insults.',
  };

  banterHint(): string {
    return this.banterLevel
      ? this.banterHints[this.banterLevel]
      : 'Original tone; no extra guidance.';
  }

  readonly chatModeOptions: ReadonlyArray<{ value: ChatMode; label: string; hint: string }> = [
    {
      value: 'OFF',
      label: 'Off',
      hint: 'Models are told to stay silent; messages they send anyway are dropped.',
    },
    {
      value: 'OPTIONAL',
      label: 'Optional',
      hint: 'Models may send a message with a move; whether they do is part of the observation.',
    },
    {
      value: 'MANDATORY',
      label: 'Mandatory',
      hint: 'Models are asked for a short message with every move; a missing one is recorded, never penalized.',
    },
  ];
  /**
   * Whether models may resign instead of moving (from their 10th move on); sent as resignationAllowed. Off by
   * default and in every preset, so games are played to the end.
   */
  resignationAllowed = false;
  adjudicationEnabled = false;
  legalMoveHints = true;
  openingMode = signal<OpeningMode>('STANDARD');
  openingId = signal<string | null>(null);
  openings = signal<Opening[]>([]);
  openingsError = signal<string | null>(null);

  openingHint(): string {
    switch (this.openingMode()) {
      case 'STANDARD':
        return 'Start from the standard position.';
      case 'RANDOM_BOOK':
        return 'A random book line per pairing; color-reversed partners share it.';
      case 'FIXED':
        return 'Every new game starts with the selected book line.';
    }
  }

  loadOpenings(): void {
    this.openingsError.set(null);
    this.api.getOpenings().subscribe({
      next: (lines) => this.openings.set(lines),
      error: () => {
        this.openings.set([]);
        this.openingsError.set('Could not load the opening book. Retry or use Standard.');
      },
    });
  }

  readonly sourceId = signal<string | null>(null);
  readonly rematchSource = signal<Tournament | null>(null);
  readonly rematchLoadError = signal<string | null>(null);
  readonly rematchAcknowledged = signal(false);
  readonly rematchConcurrency = signal<number | null>(2);
  readonly rematchBudget = signal('');
  readonly concurrencyOptions = [1, 2, 3, 4, 5, 6, 7, 8];
  private readonly subscriptions = new Subscription();
  private sourceRequest?: Subscription;
  private createRequest?: Subscription;
  readonly rematchNameValid = computed(
    () => Array.from(this.nameValue().trim()).length <= MAX_TOURNAMENT_NAME_LENGTH,
  );
  readonly rematchTimeoutValid = computed(
    () =>
      !this.limitMoveTime ||
      (Number.isInteger(this.moveTimeout) && this.moveTimeout >= 10 && this.moveTimeout <= 3600),
  );
  readonly rematchOutputTokensValid = computed(
    () =>
      Number.isInteger(this.maxOutputTokens) &&
      this.maxOutputTokens >= 128 &&
      this.maxOutputTokens <= 262144,
  );
  readonly rematchSeedAvailable = computed(
    () =>
      this.editingParticipantIndex() !== null ||
      !this.participants().some((p) => p.seed === MAX_PARTICIPANT_SEED),
  );

  readonly validRunSettings = computed(() => {
    const concurrency = this.rematchConcurrency();
    const budget = this.rematchBudget();
    return (
      concurrency !== null &&
      this.concurrencyOptions.includes(concurrency) &&
      (budget === '' || (isBudgetDecimal(budget) && /[1-9]/.test(budget)))
    );
  });

  readonly unavailableModels = computed(() => {
    if (!this.sourceId() || this.modelsLoading() || this.modelsError()) return [];
    const catalog = new Set(this.models().map((model) => model.id));
    return this.participants().filter(
      (participant) => participant.controlType === 'AI' && !catalog.has(participant.modelId ?? ''),
    );
  });
  readonly rematchWarnings = computed(() => {
    const source = this.rematchSource();
    if (!source) return [];
    const warnings: string[] = [];
    if (source.legacySystemPrompt) warnings.push('Tournament-wide legacy system template');
    for (const participant of source.participants) {
      if (participant.legacySystemPrompt)
        warnings.push('Legacy system template for ' + participant.playerName);
    }
    if (new Set(source.participants.map((p) => p.seed)).size !== source.participants.length) {
      warnings.push('Original lineup has tied seeds. Exact pairings may change.');
    }
    if (source.format !== 'SINGLE_ELIMINATION') warnings.push('Tournament format ' + source.format);
    if (source.maxConcurrentGames === undefined)
      warnings.push('Original concurrency limit unavailable');
    if (source.budgetUsd === undefined) warnings.push('Original budget unavailable');
    if (source.maxOutputTokens === undefined)
      warnings.push('Original output-token limit unavailable');
    if (source.openingMode === undefined) warnings.push('Original opening policy unavailable');
    if (source.resignationAllowed === undefined)
      warnings.push('Original resignation setting unavailable');
    if (source.adjudicationEnabled === undefined)
      warnings.push('Original adjudication setting unavailable');
    return warnings;
  });

  readonly openingValid = computed(
    () =>
      this.openingMode() === 'STANDARD' ||
      (this.openings().length > 0 &&
        (this.openingMode() !== 'FIXED' ||
          this.openings().some((line) => line.id === this.openingId()))),
  );
  drawPolicy: Tournament['drawPolicy'] = 'WHITE_ADVANCES';
  drawMode: BracketDrawMode = 'RANDOM';
  matchupBestOf: Tournament['matchupBestOf'] = 1;
  finalsBestOf: Tournament['finalsBestOf'] = null;

  readonly editingParticipantIndex = signal<number | null>(null);
  newPlayerName = '';
  newControlType: Participant['controlType'] = 'AI';
  newModelId = '';
  newCustomInstructions = '';
  showParticipantInstructions = false;

  @ViewChild('participantName', { read: ElementRef })
  private participantName?: ElementRef<HTMLInputElement>;

  @ViewChild('setupAside') private setupAside!: ElementRef<HTMLElement>;
  readonly asideSticky = signal(false);
  /** Space below the sticky toolbar. Shared with the aside's CSS top offset. */
  readonly asideStickyTop = 88;
  private asideObserver?: ResizeObserver;
  private readonly updateAsideSticky = () => {
    const bottomGap = 24;
    this.asideSticky.set(
      window.innerWidth >= 1200 &&
        this.setupAside.nativeElement.offsetHeight <=
          window.innerHeight - this.asideStickyTop - bottomGap,
    );
  };

  @ViewChild('participantInstructions')
  set participantInstructions(field: ElementRef<HTMLTextAreaElement> | undefined) {
    field?.nativeElement.focus();
  }

  hintParts(text: string): [string, string] {
    const lastSpace = text.lastIndexOf(' ');
    return [text.slice(0, lastSpace), text.slice(lastSpace + 1)];
  }

  /** The new participant's override; null follows the tournament's level. Reset when the model changes. */
  newReasoningEffort: ReasoningEffort | null = null;

  participants = signal<SetupParticipant[]>([]);
  /** The whole OpenRouter catalog, loaded once; the picker filters it client-side. */
  models = signal<OpenRouterModelOption[]>([]);
  featuredProviders = signal<string[]>([]);
  modelsLoading = signal(false);
  modelsError = signal<string | null>(null);
  openRouterKey = signal<OpenRouterKeyStatus | null>(null);
  currentOpenRouterKey = computed(() => this.connection?.keyStatus() ?? this.openRouterKey());
  openRouterReady = computed(() =>
    this.currentOpenRouterKey() === null ? null : openRouterConnected(this.currentOpenRouterKey()),
  );
  openRouterValidation = computed(() => openRouterValidation(this.currentOpenRouterKey()));
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
      chatMode: ChatMode;
      sharedCustomInstructions: string;
    }
  > = {
    quickstart: {
      name: 'Quick Start Arena',
      moveTimeout: 120,
      maxRetries: 2,
      maxOutputTokens: 16384,
      drawPolicy: 'WHITE_ADVANCES',
      matchupBestOf: 1,
      finalsBestOf: null,
      chatMode: 'OPTIONAL',
      sharedCustomInstructions: '',
    },
    balanced: {
      name: 'Balanced Ladder',
      moveTimeout: 120,
      maxRetries: 3,
      maxOutputTokens: 16384,
      drawPolicy: 'HIGHER_SEED_ADVANCES',
      matchupBestOf: 3,
      finalsBestOf: null,
      chatMode: 'OPTIONAL',
      sharedCustomInstructions: 'Stay practical. Avoid unnecessary risks when ahead.',
    },
    showcase: {
      name: 'Series Showcase',
      moveTimeout: 120,
      maxRetries: 3,
      maxOutputTokens: 32768,
      drawPolicy: 'REPLAY_GAME',
      matchupBestOf: 3,
      finalsBestOf: 5,
      chatMode: 'OPTIONAL',
      sharedCustomInstructions: 'Play your strongest chess and keep messages concise.',
    },
  };

  constructor(
    private api: ApiService,
    private router: Router,
    private route: ActivatedRoute,
    protected connection: OpenRouterAccess = null!,
  ) {}

  ngOnInit(): void {
    const routeSource = this.route.snapshot?.paramMap?.get('sourceId');
    if (routeSource) {
      this.subscriptions.add(
        this.route.paramMap.subscribe((params) => {
          this.sourceId.set(params.get('sourceId'));
          this.loadRematch();
        }),
      );
    } else {
      this.subscriptions.add(
        this.route.queryParamMap.subscribe((params) => {
          this.applyPreset(params.get('preset'));
        }),
      );
    }
    this.loadPromptTemplate();
    this.loadOpenRouterStatus();
    this.loadModels();
    this.loadOpenings();
  }

  loadRematch(): void {
    const id = this.sourceId();
    if (!id) return;

    this.createRequest?.unsubscribe();
    this.creating.set(false);
    this.rematchSource.set(null);
    this.rematchLoadError.set(null);
    this.rematchAcknowledged.set(false);
    this.pendingSubmission = null;
    this.createError.set(null);
    this.resetParticipantForm();
    this.sourceRequest?.unsubscribe();
    this.sourceRequest = this.api.getTournament(id).subscribe({
      next: (source) => {
        if (this.sourceId() !== id) return;
        this.name =
          Array.from(source.name.trim())
            .slice(0, MAX_TOURNAMENT_NAME_LENGTH - REMATCH_SUFFIX.length)
            .join('')
            .trimEnd() + REMATCH_SUFFIX;
        this.sharedCustomInstructions = source.sharedCustomInstructions ?? '';
        this.limitMoveTime = source.moveTimeoutSeconds != null;
        this.moveTimeout = source.moveTimeoutSeconds ?? 120;
        this.maxRetries.set(Math.min(20, Math.max(1, source.maxRetries)));
        this.maxOutputTokens = source.maxOutputTokens ?? 16384;
        this.reasoningEffort =
          source.reasoningEffort === 'default' ? null : (source.reasoningEffort ?? null);
        this.chatMode = source.chatMode ?? (source.trashTalkEnabled ? 'OPTIONAL' : 'OFF');
        this.banterLevel = source.banterLevel ?? null;
        this.resignationAllowed = source.resignationAllowed ?? false;
        this.adjudicationEnabled = source.adjudicationEnabled ?? false;
        this.legalMoveHints = source.legalMoveHints ?? true;
        this.openingMode.set(source.openingMode ?? 'STANDARD');
        this.openingId.set(source.openingId ?? null);
        this.drawMode = 'ORDER';
        this.drawPolicy = source.drawPolicy;
        this.matchupBestOf = source.matchupBestOf;
        this.finalsBestOf = source.finalsBestOf ?? null;
        this.rematchConcurrency.set(source.maxConcurrentGames ?? 2);
        this.rematchBudget.set(
          source.budgetUsdExact ?? (source.budgetUsd == null ? '' : String(source.budgetUsd)),
        );
        const ordered = [...source.participants].sort((a, b) => a.seed - b.seed);
        this.participants.set(
          ordered.map(
            ({ playerName, controlType, modelId, customInstructions, seed, reasoningEffort }) => ({
              playerName,
              controlType,
              modelId,
              customInstructions,
              seed,
              reasoningEffort,
            }),
          ),
        );
        this.rematchSource.set({ ...source, participants: ordered });
      },
      error: (err) => {
        if (this.sourceId() !== id) return;
        this.rematchLoadError.set(
          err?.status === 404
            ? 'Source tournament not found.'
            : 'Could not load the source tournament.',
        );
      },
    });
  }

  rematchChanges(source: Tournament): { setting: string; before: string; after: string }[] {
    const changes: { setting: string; before: string; after: string }[] = [];
    const compare = (
      setting: string,
      before: unknown,
      after: unknown,
      label: (value: unknown) => string = String,
    ) => {
      if (before !== after) changes.push({ setting, before: label(before), after: label(after) });
    };
    const nullable = (value: unknown) =>
      value === undefined ? 'Unavailable' : String(value ?? 'None');
    const toggle = (value: unknown) => (value === undefined ? 'Unavailable' : value ? 'On' : 'Off');
    const draw = (value: unknown) =>
      value === 'ORDER'
        ? 'Stored seed order'
        : value === 'RANDOM'
          ? 'Random draw'
          : 'Original draw unavailable';
    compare('Name', source.name, this.name.trim());
    compare('Format', source.format, 'SINGLE_ELIMINATION', (value) =>
      value === 'SINGLE_ELIMINATION' ? 'Single elimination' : String(value),
    );
    compare('Auto-run state', source.autoRunStatus ?? 'OFF', 'OFF');
    compare('Bracket draw', source.creationDrawMode, this.drawMode, draw);
    compare(
      'Draw policy',
      source.drawPolicy,
      this.drawPolicy,
      (value) =>
        this.drawPolicyOptions.find((option) => option.value === value)?.label ?? String(value),
    );
    compare('Matchup length', source.matchupBestOf, this.matchupBestOf);
    compare('Final length', source.finalsBestOf ?? null, this.finalsBestOf, (value) =>
      String(value ?? 'Same as matchups'),
    );
    compare(
      'Opening policy',
      source.openingMode === 'FIXED' ? (source.openingId ?? 'Missing opening') : source.openingMode,
      this.openingMode() === 'FIXED'
        ? (this.openingId() ?? 'Choose an opening')
        : this.openingMode(),
      (value) =>
        this.openings().find((line) => line.id === value)?.name ??
        (value === 'STANDARD'
          ? 'Standard'
          : value === 'RANDOM_BOOK'
            ? 'Random book'
            : nullable(value)),
    );
    compare(
      'Move time limit',
      source.moveTimeoutSeconds ?? null,
      this.limitMoveTime ? this.moveTimeout : null,
      (value) => (value == null ? 'No limit' : value + ' s'),
    );
    compare('Attempts per move', source.maxRetries, this.maxRetries());
    compare('Output-token cap', source.maxOutputTokens, this.maxOutputTokens, nullable);
    compare(
      'Chat',
      source.chatMode ?? (source.trashTalkEnabled ? 'OPTIONAL' : 'OFF'),
      this.chatMode,
    );
    compare('Banter tone', source.banterLevel ?? null, this.banterLevel, (value) =>
      String(value ?? 'Original tone'),
    );
    compare('Resignation', source.resignationAllowed, this.resignationAllowed, toggle);
    compare('Adjudication', source.adjudicationEnabled, this.adjudicationEnabled, toggle);
    compare('Legal move hints', source.legalMoveHints ?? true, this.legalMoveHints, toggle);
    compare(
      'Reasoning effort',
      source.reasoningEffort === 'default' ? null : (source.reasoningEffort ?? null),
      this.reasoningEffort,
      (value) => String(value ?? 'Model default'),
    );
    compare(
      'Maximum concurrent games',
      source.maxConcurrentGames,
      this.rematchConcurrency(),
      nullable,
    );
    const beforeBudget = source.budgetUsdExact ?? source.budgetUsd;
    compare(
      'Budget',
      beforeBudget === undefined ? undefined : canonicalBudget(String(beforeBudget ?? '')),
      canonicalBudget(this.rematchBudget()),
      (value) => (value === undefined ? 'Unavailable' : value === '' ? 'No cap' : '$' + value),
    );
    const instructions = this.normalizeInstructions(this.sharedCustomInstructions);
    if ((source.sharedCustomInstructions ?? null) !== instructions) {
      changes.push({
        setting: 'Shared instructions',
        before: source.sharedCustomInstructions ? 'Custom instructions' : 'None',
        after: instructions
          ? source.sharedCustomInstructions
            ? 'Edited instructions'
            : 'Custom instructions'
          : 'None',
      });
    }
    const before = source.participants;
    const after = this.participants();
    const player = (p: SetupParticipant | undefined) =>
      p
        ? [
            p.playerName,
            this.drawMode === 'ORDER' ? 'Seed ' + p.seed : null,
            p.controlType === 'HUMAN' ? 'Human' : p.modelId,
            p.reasoningEffort,
            p.customInstructions ? 'Custom instructions' : null,
          ]
            .filter(Boolean)
            .join(' · ')
        : 'None';
    for (let i = 0; i < Math.max(before.length, after.length); i++) {
      const original = before[i];
      const draft = after[i];
      const previous = player(original);
      const current = player(draft);
      if (previous !== current) compare('Lineup position ' + (i + 1), previous, current);
      else if ((original?.customInstructions ?? null) !== (draft?.customInstructions ?? null)) {
        changes.push({
          setting: 'Instructions for ' + draft?.playerName,
          before: 'Custom instructions',
          after: 'Edited instructions',
        });
      }
    }
    return changes;
  }

  ngAfterViewInit(): void {
    if (typeof window === 'undefined' || typeof ResizeObserver === 'undefined') {
      return;
    }
    // The observer's initial notification also measures the first rendered lineup.
    this.asideObserver = new ResizeObserver(this.updateAsideSticky);
    this.asideObserver.observe(this.setupAside.nativeElement);
    window.addEventListener('resize', this.updateAsideSticky);
  }

  ngOnDestroy(): void {
    this.sourceRequest?.unsubscribe();
    this.createRequest?.unsubscribe();
    this.subscriptions.unsubscribe();
    this.asideObserver?.disconnect();
    if (typeof window !== 'undefined') {
      window.removeEventListener('resize', this.updateAsideSticky);
    }
  }

  private loadOpenRouterStatus(): void {
    this.api.checkOpenRouterStatus().subscribe({
      next: (status) => this.openRouterKey.set(status.key),
      error: () => this.openRouterKey.set(null),
    });
  }

  private loadPromptTemplate(): void {
    this.promptTemplateLoadError.set(null);
    this.api.getPromptTemplate().subscribe({
      next: (response: PromptTemplateResponse) => {
        this.promptTemplates = response;
        this.promptTemplateVersion.set(response.version);
      },
      error: () => {
        this.promptTemplates = null;
        this.promptTemplateVersion.set('fallback');
        this.promptTemplateLoadError.set(
          'Failed to load prompt template from backend. Using local fallback.',
        );
      },
    });
  }

  /**
   * The rules variant for the selected chat mode and resignation setting; undefined for an older backend
   * without it (then the chat mode's rules, or the default rules, are shown).
   */
  private selectedRules(): { template: string; version: string } | undefined {
    const templates = this.promptTemplates;
    if (this.chatMode !== 'OFF' && this.banterLevel) {
      const variants = this.resignationAllowed
        ? templates?.resignationBanterChatModes
        : templates?.banterChatModes;
      const variant = variants?.[this.banterLevel]?.[this.chatMode];
      if (variant) return variant;
    }
    if (this.resignationAllowed && templates?.resignationChatModes?.[this.chatMode]) {
      return templates.resignationChatModes[this.chatMode];
    }
    return templates?.chatModes?.[this.chatMode];
  }

  /** The system rules for the selected chat mode and resignation setting (default rules from an older backend). */
  get systemRulesTemplate(): string {
    const templates = this.promptTemplates;
    if (!templates) {
      return this.promptTemplateVersion() === 'fallback' ? TournamentSetup.PROMPT_FALLBACK : '';
    }
    const rules = this.selectedRules()?.template ?? templates.template;
    return !this.legalMoveHints && templates.noHints
      ? rules + '\n\n' + templates.noHints.rule
      : rules;
  }

  /** The prompt version of the rules shown, e.g. v2-chat-required for MANDATORY, v2-resign with resignation. */
  systemRulesVersion(): string | null {
    const version = this.selectedRules()?.version ?? this.promptTemplateVersion();
    const suffix = this.promptTemplates?.noHints?.versionSuffix;
    return version && !this.legalMoveHints && suffix ? version + suffix : version;
  }

  /** One line under the resignation switch. */
  resignationHint(): string {
    return this.resignationAllowed
      ? 'Both models may resign instead of moving from their 10th move on, and are told so.'
      : 'Every game is played to the end; a model that tries to resign must send a move instead.';
  }

  /** Whether the time limit per move is short enough that reasoning models are likely to forfeit on time. */
  showShortMoveTimeoutWarning(): boolean {
    return (
      this.limitMoveTime &&
      typeof this.moveTimeout === 'number' &&
      this.moveTimeout < SHORT_MOVE_TIMEOUT_SECONDS
    );
  }

  chatModeHint(): string {
    return this.chatModeOptions.find((option) => option.value === this.chatMode)?.hint ?? '';
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
    if (modelId !== this.newModelId) {
      // The levels differ from model to model.
      this.newReasoningEffort = null;
    }
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

  /** The levels the selected model offers, in order; empty without a model or without effort control. */
  selectedModelEfforts(): ReasoningEffort[] {
    return supportedEfforts(this.selectedModel());
  }

  /** E.g. "Tournament setting (Low)" or "Tournament setting (model default)". */
  tournamentEffortOptionLabel(): string {
    const level = this.reasoningEffort
      ? reasoningEffortLabel(this.reasoningEffort)
      : 'model default';
    return `Tournament setting (${level})`;
  }

  canAddParticipant(): boolean {
    if (
      (this.sourceId() && !this.rematchSeedAvailable()) ||
      this.newPlayerName.trim().length === 0
    ) {
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

    const editing = this.editingParticipantIndex();
    this.participants.update((list) => {
      const previous = editing === null ? undefined : list[editing];
      const participant: SetupParticipant = {
        playerName: this.newPlayerName.trim(),
        controlType: this.newControlType,
        modelId: this.newControlType === 'AI' ? this.newModelId : null,
        customInstructions:
          this.newControlType === 'AI' || previous?.controlType === 'HUMAN'
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
        seed:
          previous?.seed ??
          (this.sourceId() ? Math.max(-1, ...list.map((p) => p.seed ?? 0)) + 1 : list.length),
        reasoningEffort:
          this.newControlType === 'AI' &&
          this.newReasoningEffort &&
          (this.selectedModelEfforts().includes(this.newReasoningEffort) ||
            (previous?.modelId === this.newModelId &&
              previous.reasoningEffort === this.newReasoningEffort))
            ? this.newReasoningEffort
            : null,
      };
      return editing === null
        ? [...list, participant]
        : list.map((p, i) => (i === editing ? participant : p));
    });

    this.resetParticipantForm();
  }

  editParticipant(index: number): void {
    const participant = this.participants()[index];
    this.editingParticipantIndex.set(index);
    this.newPlayerName = participant.playerName ?? '';
    this.newControlType = participant.controlType ?? 'AI';
    this.newModelId = participant.modelId ?? '';
    this.newReasoningEffort = participant.reasoningEffort ?? null;
    this.newCustomInstructions = participant.customInstructions ?? '';
    this.showParticipantInstructions = !!participant.customInstructions;
    this.participantName?.nativeElement.focus();
  }

  resetParticipantForm(): void {
    this.editingParticipantIndex.set(null);
    this.newPlayerName = '';
    this.newControlType = 'AI';
    this.newModelId = '';
    this.newCustomInstructions = '';
    this.showParticipantInstructions = false;
    this.newReasoningEffort = null;
  }

  removeParticipant(index: number) {
    this.participants.update((list) => list.filter((_, i) => i !== index));
    const editing = this.editingParticipantIndex();
    if (editing === index) this.resetParticipantForm();
    else if (editing !== null && editing > index) this.editingParticipantIndex.set(editing - 1);
  }

  creating = signal(false);
  createError = signal<string | null>(null);

  /** One model for the launch checklist and the Create button: a name, at least two participants, not already submitting. */
  readonly readiness = computed(() => {
    const named = !!this.nameValue().trim();
    const count = this.participants().length;
    const participantsOk = count >= 2;
    const creating = this.creating();
    return {
      named,
      count,
      participantsOk,
      creating,
      canCreate:
        named &&
        participantsOk &&
        !creating &&
        this.rematchReady() &&
        this.openingValid() &&
        this.validMaxRetries() !== null,
    };
  });
  private readonly rematchReady = computed(() => {
    if (!this.sourceId()) return true;
    const hasModels = this.participants().some((p) => p.controlType === 'AI');
    return (
      this.rematchSource() !== null &&
      this.rematchNameValid() &&
      this.rematchTimeoutValid() &&
      this.rematchOutputTokensValid() &&
      this.editingParticipantIndex() === null &&
      (!hasModels ||
        (!this.modelsLoading() && !this.modelsError() && !this.unavailableModels().length)) &&
      this.validRunSettings() &&
      (!this.rematchWarnings().length || this.rematchAcknowledged())
    );
  });
  /**
   * Request id bound to exactly the submitted details: retrying the same details reuses it (so a request that
   * reached the server but lost its response creates nothing twice), while edited details get a new id.
   */
  private pendingSubmission: { key: string; requestId: string } | null = null;

  create() {
    if (this.sourceId() && !this.readiness().canCreate) return;
    const source = this.rematchSource();
    const submit = () => {
      if (!this.subscriptions.closed && this.rematchSource() === source) this.createWithKey();
    };
    if (this.connection) this.connection.withKey(submit);
    else submit();
  }

  private createWithKey() {
    const maxRetries = this.validMaxRetries();
    if (
      this.creating() ||
      !this.openingValid() ||
      maxRetries === null ||
      (this.sourceId() && !this.readiness().canCreate)
    ) {
      return;
    }
    this.creating.set(true);
    this.createError.set(null);

    const concurrency = this.rematchConcurrency();
    const details = {
      name: this.sourceId() ? this.name.trim() : this.name,
      sharedCustomInstructions: this.normalizeInstructions(this.sharedCustomInstructions),
      // 0 means no time limit per move.
      moveTimeoutSeconds: this.limitMoveTime ? this.moveTimeout : 0,
      maxRetries,
      maxOutputTokens: this.maxOutputTokens,
      matchupBestOf: this.matchupBestOf,
      finalsBestOf: this.finalsBestOf,
      chatMode: this.chatMode,
      banterLevel: this.banterLevel,
      resignationAllowed: this.resignationAllowed,
      adjudicationEnabled: this.adjudicationEnabled,
      legalMoveHints: this.legalMoveHints,
      openingMode: this.openingMode(),
      ...(this.openingMode() === 'FIXED' ? { openingId: this.openingId() } : {}),
      // Left out for the model default, so nothing is sent to OpenRouter.
      ...(this.reasoningEffort ? { reasoningEffort: this.reasoningEffort } : {}),
      drawPolicy: this.drawPolicy,
      drawMode: this.drawMode,
      generateBracket: true,
      ...(this.sourceId() && concurrency !== null
        ? {
            runSettings: {
              maxConcurrentGames: concurrency,
              budgetUsd: canonicalBudget(this.rematchBudget()) || null,
            },
          }
        : {}),
      participants: this.participants().map((p) => ({
        playerName: p.playerName,
        controlType: p.controlType,
        modelId: p.modelId,
        customInstructions: p.customInstructions ?? null,
        seed: p.seed,
        reasoningEffort: p.reasoningEffort ?? null,
      })),
    };
    const key = JSON.stringify(details);
    if (this.pendingSubmission?.key !== key) {
      this.pendingSubmission = { key, requestId: newRequestId() };
    }

    this.createRequest = this.api
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
          if (
            this.sourceId() &&
            status === 400 &&
            message?.startsWith('Model is not allowed by backend policy:')
          ) {
            this.loadModels();
          }
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
      // A limit is a ceiling, not a pace: models answer in their typical time unless a short limit cuts them off
      // (a quarter of the limit), and slow games use about half of the limit per move.
      const likelyMoveSeconds = Math.min(TYPICAL_MOVE_SECONDS, this.moveTimeout * 0.25);
      const slowMoveSeconds = this.moveTimeout * 0.5;
      const likelyMinutesPerGame = Math.max(
        6,
        Math.round((ESTIMATED_MOVES_PER_GAME * likelyMoveSeconds) / 60),
      );
      const slowMinutesPerGame = Math.max(
        10,
        likelyMinutesPerGame,
        Math.round((ESTIMATED_MOVES_PER_GAME * slowMoveSeconds) / 60),
      );
      likelyRuntimeMinutes = likelyGames * likelyMinutesPerGame;
      maxRuntimeMinutes = maxGames * slowMinutesPerGame;
    } else {
      // No time limit per move: a typical answer time for the estimate, and no upper bound.
      likelyRuntimeMinutes =
        likelyGames * Math.round((ESTIMATED_MOVES_PER_GAME * TYPICAL_MOVE_SECONDS) / 60);
      maxRuntimeMinutes = null;
    }

    const participantsWithPricing = this.participants()
      .map((participant) => {
        if (participant.controlType !== 'AI' || !this.sourceId()) return participant;
        const model = this.models().find((option) => option.id === participant.modelId);
        return {
          ...participant,
          promptPricePerMillion: model?.promptPricePerMillion,
          completionPricePerMillion: model?.completionPricePerMillion,
        };
      })
      .filter(
        (participant) =>
          participant.promptPricePerMillion != null &&
          participant.completionPricePerMillion != null,
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
      // No limit is the default; size, series length and pricing determine the run's risk.
      (this.limitMoveTime ? (this.moveTimeout >= 300 ? 2 : this.moveTimeout >= 180 ? 1 : 0) : 0) +
      (drawCanExtendSeries ? 1 : 0) +
      (likelyRuntimeMinutes >= 360 ? 2 : likelyRuntimeMinutes >= 120 ? 1 : 0) +
      (likelyCostUsd != null ? (likelyCostUsd >= 5 ? 2 : likelyCostUsd >= 1 ? 1 : 0) : 0);

    const riskBand = riskScore >= 8 ? 'Expensive' : riskScore >= 5 ? 'Moderate' : 'Safe';
    const riskReason = !this.limitMoveTime
      ? `${riskBand === 'Safe' ? 'Low cost risk' : riskBand === 'Moderate' ? 'Moderate cost risk' : 'High cost'}; runtime is open-ended without a move time limit.`
      : riskBand === 'Expensive'
        ? 'A larger field, longer series, or pricier models make this a heavier run.'
        : riskBand === 'Moderate'
          ? 'Longer series or higher costs may need a closer look.'
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
        : this.moveTimeout < SHORT_MOVE_TIMEOUT_SECONDS
          ? 'Tight'
          : this.moveTimeout <= 180
            ? 'Balanced'
            : 'Generous',
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
    this.limitMoveTime = false;
    this.moveTimeout = preset.moveTimeout;
    this.maxRetries.set(preset.maxRetries);
    this.maxOutputTokens = preset.maxOutputTokens;
    this.drawPolicy = preset.drawPolicy;
    this.matchupBestOf = preset.matchupBestOf;
    this.finalsBestOf = preset.finalsBestOf;
    this.chatMode = preset.chatMode;
    this.banterLevel = null;
    // Presets play every game to the end, with each model's default reasoning effort.
    this.resignationAllowed = false;
    this.adjudicationEnabled = false;
    this.legalMoveHints = true;
    this.openingMode.set('STANDARD');
    this.openingId.set(null);
    this.reasoningEffort = null;
    this.sharedCustomInstructions = preset.sharedCustomInstructions;
  }

  hasHumanParticipant(): boolean {
    return this.participants().some(
      (participant, index) =>
        participant.controlType === 'HUMAN' && index !== this.editingParticipantIndex(),
    );
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
