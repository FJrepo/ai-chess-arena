import { OpenRouterAccess } from '../../services/openrouter-access';
import { BoardThemeService } from '../../services/board-theme.service';
import { MatMenuModule } from '@angular/material/menu';
import { DatePipe } from '@angular/common';
import { Component, OnInit, OnDestroy, signal, computed, ViewChild } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { ApiService } from '../../services/api.service';
import { downloadBlob } from '../../utils/download';
import { WebSocketService } from '../../services/websocket.service';
import {
  Game,
  Move,
  ChatMessage,
  ChatMode,
  BanterLevel,
  DEFAULT_MOVE_TIMEOUT_SECONDS,
  MoveAttempt,
  MoveWsMessage,
  GameStatusWsMessage,
  RetryWsMessage,
  WsMessage,
} from '../../models/tournament.model';
import { ChessboardComponent, BoardMoveAttempt } from '../../components/chessboard/chessboard';
import { MoveListComponent } from '../../components/move-list/move-list';
import { ChatPanel } from '../../components/chat-panel/chat-panel';
import { QueuedResume } from '../../components/queued-resume/queued-resume';
import {
  GameInfoBar,
  RaiseLimitRequest,
  TruncationEffortChoice,
} from '../../components/game-info-bar/game-info-bar';
import { forkJoin, Observable, of, Subscription, switchMap, tap, timer } from 'rxjs';
import { deriveCapturedMaterial } from '../../utils/captured-material';
import { pieceImage, pieceName } from '../../utils/piece-assets';
import { sortMovesInPlayOrder } from '../../utils/move-order';
import { AdvantageBar } from '../../components/advantage-bar/advantage-bar';
import { GameTurnStateService } from '../../services/game-turn-state.service';
import { LastMove, lastMoveSquares } from '../../utils/last-move';
import { PositionEvaluation, evaluationAt } from '../../utils/position-evaluation';
import {
  QualityBadge,
  applyPlyQualities,
  qualityBadge,
  qualityTooltip,
} from '../../utils/move-quality';
import {
  latestAttemptFor,
  nextPly,
  notableAttemptsByPly,
  plyKey,
  summarizeAttempts,
} from '../../utils/move-attempts';
import {
  REASONING_EFFORTS,
  ReasoningEffort,
  isReasoningEffort,
  supportedEfforts,
} from '../../utils/reasoning-effort';

type TimelineEvent = {
  id: string;
  type: 'start' | 'move' | 'attempts' | 'chat' | 'result';
  label: string;
  detail: string;
  /** Failed or cut-off answers for the ply, shown as their own line; null when there were none. */
  attemptSummary: string | null;
  tone: 'neutral' | 'move' | 'chat' | 'warning' | 'success';
  /** ?! / ? / ?? annotation of a move judged an error, with its tooltip; null otherwise. */
  quality: { badge: QualityBadge; tooltip: string | null } | null;
  icon: string;
  createdAtMs: number;
  createdAtLabel: string;
  moveIndex: number | null;
};

const START_FEN = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1';

/** Backend default for a tournament's output-token limit. */
const DEFAULT_MAX_OUTPUT_TOKENS = 16384;

@Component({
  selector: 'app-game-view',
  standalone: true,
  providers: [GameTurnStateService],
  imports: [
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatMenuModule,
    MatChipsModule,
    MatFormFieldModule,
    MatInputModule,
    MatExpansionModule,
    MatSnackBarModule,
    MatSlideToggleModule,
    DatePipe,
    RouterLink,
    ChessboardComponent,
    MoveListComponent,
    ChatPanel,
    GameInfoBar,
    QueuedResume,
    AdvantageBar,
  ],
  templateUrl: './game-view.html',
  styleUrl: './game-view.scss',
})
export class GameView implements OnInit, OnDestroy {
  game = signal<Game | null>(null);
  moves = signal<Move[]>([]);
  chatMessages = signal<ChatMessage[]>([]);
  /** Every model answer of the game, including rejected ones. */
  attempts = signal<MoveAttempt[]>([]);
  /** The tournament's output-token limit, once loaded. */
  tournamentMaxOutputTokens = signal<number | null>(null);
  /** The tournament's chat mode, once loaded (shown in the chat panel). */
  tournamentChatMode = signal<ChatMode | null>(null);
  tournamentBanterLevel = signal<BanterLevel | null>(null);
  /** The tournament's reasoning effort, once loaded; null for each model's default. */
  tournamentReasoningEffort = signal<ReasoningEffort | null>(null);
  /**
   * The effort levels of models looked up for the pause banner, by model id: the model's levels (empty when it
   * offers none), or null when the catalog could not tell (then every level is offered).
   */
  modelEfforts = signal<Record<string, ReasoningEffort[] | null>>({});
  savingLimit = signal(false);
  /** Keep the pause controls hidden until current participant and tournament settings arrive. */
  private refreshingPauseSettings = signal(false);
  private pauseSettingsSub?: Subscription;
  private settingsGeneration = 0;
  /**
   * The side whose latest answer for the ply to be played was cut off at the output-token limit (outcome
   * TRUNCATED) while the game is paused; null otherwise.
   */
  truncatedSide = computed<'WHITE' | 'BLACK' | null>(() => {
    const game = this.game();
    if (!game || game.status !== 'PAUSED' || !game.statusDetail || !game.tournamentId) {
      return null;
    }
    const ply = nextPly(this.moves());
    const latest = latestAttemptFor(this.attempts(), ply.moveNumber, ply.color);
    return latest?.outcome === 'TRUNCATED' ? ply.color : null;
  });
  /** The current output-token limit when the game is paused by a cut-off answer; null otherwise. */
  truncationPauseLimit = computed<number | null>(() =>
    !this.refreshingPauseSettings() && this.truncatedSide()
      ? (this.tournamentMaxOutputTokens() ?? DEFAULT_MAX_OUTPUT_TOKENS)
      : null,
  );
  /** The model maximum captured on the cut-off request, independent of later catalog changes. */
  truncationModelMaximum = computed<number | null>(() => {
    if (!this.truncatedSide()) {
      return null;
    }
    const ply = nextPly(this.moves());
    return (
      latestAttemptFor(this.attempts(), ply.moveNumber, ply.color)?.modelMaxCompletionTokens ?? null
    );
  });
  /**
   * The cut-off side's reasoning effort for the pause banner: its participant, the level it asks for and the
   * levels its model offers (every level until, or unless, the catalog tells). Null without a cut-off answer,
   * for a side without a participant and for a human side.
   */
  truncationEffort = computed<TruncationEffortChoice | null>(() => {
    const side = this.truncatedSide();
    const game = this.game();
    if (!side || !game || this.refreshingPauseSettings()) {
      return null;
    }
    const white = side === 'WHITE';
    const participantId = white ? game.whiteParticipantId : game.blackParticipantId;
    const controlType = white ? game.whiteControlType : game.blackControlType;
    if (!participantId || controlType === 'HUMAN') {
      return null;
    }
    const modelId = white ? game.whiteModelId : game.blackModelId;
    const known = modelId ? this.modelEfforts()[modelId] : undefined;
    const current = white ? game.whiteReasoningEffort : game.blackReasoningEffort;
    return {
      participantId,
      playerName:
        (white ? game.whitePlayerName : game.blackPlayerName) || (white ? 'White' : 'Black'),
      current: current ?? null,
      tournamentLevel: this.tournamentReasoningEffort(),
      options: known ?? [...REASONING_EFFORTS],
      fromCatalog: known != null,
    };
  });
  @ViewChild(MoveListComponent) private moveList?: MoveListComponent;
  currentMoveIndex = signal(-1);
  flipped = signal(false);
  overrideMove = '';
  humanMove = '';
  humanMessage = '';
  retryInfo = signal<string | null>(null);
  stockfishAvailable = signal(true);
  stockfishReason = signal<string | null>(null);
  includeChatInFeed = signal(false);
  humanMoveSubmitting = signal(false);
  pendingPromotion = signal<{ from: string; to: string } | null>(null);

  boardInteractiveColor = computed(() => {
    if (!this.isAwaitingHumanMove()) return null;
    const moveList = this.moves();
    if (moveList.length > 0 && this.currentMoveIndex() !== moveList.length - 1) return null;
    return this.activeColor();
  });

  private gameId = '';
  private wsSub?: Subscription;

  private reconnectSub?: Subscription;
  private queueRefreshSub?: Subscription;
  /**
   * Only the latest game load counts: a newer load cancels the older request, and a response from a
   * superseded load is ignored. Live moves that arrive while it is in flight are buffered and merged afterwards.
   */
  private loadGeneration = 0;
  private loadInFlight = false;
  private loadSub?: Subscription;
  private bufferedLiveMessages: WsMessage[] = [];
  private attemptsGeneration = 0;
  private attemptsSub?: Subscription;
  /** The highest status revision seen in a status update or a loaded game; null before any. */
  private knownStatusRevision: number | null = null;
  /** Reloads in a row that returned an outdated status (see keepAnnouncedResult). */
  private staleReloads = 0;
  private staleReloadTimer?: ReturnType<typeof setTimeout>;
  static readonly STALE_RELOAD_DELAY_MS = 1000;
  static readonly MAX_STALE_RELOADS = 5;

  /** The position shown on the board: the start position before the first move, else the selected move. */
  currentFen = computed(() => {
    const idx = this.currentMoveIndex();
    const moveList = this.moves();
    if (moveList.length === 0) {
      return this.game()?.currentFen || START_FEN;
    }
    if (idx < 0) {
      return START_FEN;
    }
    return moveList[idx]?.fen || this.game()?.currentFen || START_FEN;
  });

  /**
   * The stored evaluation of the position being viewed; unknown (not 0.0) when that move has not been
   * evaluated, and for the start position. A final checkmate is shown as a win for the mating side.
   */
  currentEvaluation = computed<PositionEvaluation>(() =>
    evaluationAt(this.moves(), this.currentMoveIndex(), this.game()),
  );

  /** From/to squares of the move being viewed, for the board's last-move highlight. */
  lastMove = computed<LastMove | null>(() => {
    const idx = this.currentMoveIndex();
    const moveList = this.moves();
    const move = idx >= 0 ? moveList[idx] : undefined;
    if (!move) {
      return null;
    }
    const previousFen = idx === 0 ? START_FEN : moveList[idx - 1].fen;
    return lastMoveSquares(previousFen, move.fen);
  });

  isLive!: () => boolean;
  overrideAllowed = computed(() => this.game()?.status === 'PAUSED');
  activeColor!: () => 'WHITE' | 'BLACK' | null;
  moveTimerSeconds!: () => number | null;
  moveTimerDisplay!: () => string;
  moveTimerWarning!: () => boolean;
  /** The tournament has no time limit per move, so the header shows whose turn it is without a countdown. */
  noMoveTimeLimit!: () => boolean;
  isAwaitingHumanMove = computed(() => {
    const game = this.game();
    const activeColor = this.activeColor();
    if (!game || game.status !== 'IN_PROGRESS' || !activeColor) {
      return false;
    }
    return activeColor === 'WHITE'
      ? game.whiteControlType === 'HUMAN'
      : game.blackControlType === 'HUMAN';
  });
  activeHumanPlayerLabel = computed(() => {
    const game = this.game();
    const activeColor = this.activeColor();
    if (!game || !activeColor) {
      return 'Human';
    }
    return activeColor === 'WHITE'
      ? (game.whitePlayerName ?? 'White')
      : (game.blackPlayerName ?? 'Black');
  });

  capturedMaterial = computed(() => deriveCapturedMaterial(this.moves(), this.currentMoveIndex()));
  showEvaluationBar = computed(() => this.stockfishAvailable());
  evaluationStatusMessage = computed(() =>
    this.stockfishAvailable() ? null : 'Evaluation unavailable',
  );
  evaluationStatusLabel = computed(() =>
    this.stockfishAvailable() ? 'Eval online' : 'Eval unavailable',
  );
  evaluationStatusIcon = computed(() => (this.stockfishAvailable() ? 'monitoring' : 'warning'));
  evaluationStatusTooltip = computed(() =>
    this.stockfishAvailable()
      ? 'Stockfish evaluation is online for this session.'
      : this.stockfishReason() || 'Stockfish evaluation is currently unavailable.',
  );

  pieceImage = pieceImage;
  pieceName = pieceName;

  whiteMaterialEdge = computed(() => Math.max(0, this.capturedMaterial().materialDelta));
  blackMaterialEdge = computed(() => Math.max(0, -this.capturedMaterial().materialDelta));
  timelineEvents = computed<TimelineEvent[]>(() => {
    const events: TimelineEvent[] = [];
    const moves = this.moves();
    const chats = this.chatMessages();
    const game = this.game();
    const attemptsByPly = notableAttemptsByPly(this.attempts());

    if (game?.startedAt) {
      events.push({
        id: `start-${game.id}`,
        type: 'start',
        quality: null,
        label: 'Game Started',
        detail: `${game.whitePlayerName || 'White'} vs ${game.blackPlayerName || 'Black'}`,
        tone: 'neutral',
        icon: 'flag',
        attemptSummary: null,
        createdAtMs: Date.parse(game.startedAt),
        createdAtLabel: game.startedAt,
        moveIndex: moves.length > 0 ? 0 : null,
      });
    }

    moves.forEach((move, index) => {
      const attemptsSummary = summarizeAttempts(
        attemptsByPly.get(plyKey(move.moveNumber, move.color)) ?? [],
      );
      attemptsByPly.delete(plyKey(move.moveNumber, move.color));
      // Without loaded attempts, fall back to the retry count stored with the move.
      const attemptSummary =
        attemptsSummary ??
        (move.retryCount > 0
          ? `${move.retryCount} retr${move.retryCount === 1 ? 'y' : 'ies'}`
          : null);
      const overrideSuffix = move.isOverride ? ' (override)' : '';
      const badge = move.isBook ? null : qualityBadge(move.quality);
      events.push({
        id: `move-${move.id || `${move.moveNumber}-${move.color}`}`,
        type: 'move',
        quality: badge ? { badge, tooltip: qualityTooltip(move.quality) } : null,
        label: `${move.color === 'WHITE' ? 'White' : 'Black'} played ${move.san}`,
        detail: move.isBook
          ? 'Book move'
          : `${this.timelinePlayerName(move.color, move.modelId)}${overrideSuffix}`,
        attemptSummary,
        tone: move.isOverride ? 'warning' : 'move',
        icon: move.isOverride ? 'edit' : 'arrow_right_alt',
        createdAtMs: Date.parse(move.createdAt),
        createdAtLabel: move.createdAt,
        moveIndex: index,
      });
    });

    // Plies whose attempts did not end in a move (forfeit, or a pause after a cut-off answer).
    attemptsByPly.forEach((plyAttempts) => {
      const last = plyAttempts[plyAttempts.length - 1];
      events.push({
        id: `attempts-${plyKey(last.moveNumber, last.color)}`,
        type: 'attempts',
        quality: null,
        label: `${last.color === 'WHITE' ? 'White' : 'Black'} move ${last.moveNumber} not played`,
        detail: this.timelinePlayerName(last.color, last.modelId),
        attemptSummary: summarizeAttempts(plyAttempts),
        tone: 'warning',
        icon: 'error_outline',
        createdAtMs: Date.parse(last.createdAt),
        createdAtLabel: last.createdAt,
        moveIndex: moves.length > 0 ? moves.length - 1 : null,
      });
    });

    chats.forEach((message) => {
      const relatedMoveIndex = this.findTimelineMoveIndex(message.moveNumber, message.senderColor);
      events.push({
        id: `chat-${message.id || `${message.moveNumber}-${message.senderColor}-${message.createdAt}`}`,
        type: 'chat',
        quality: null,
        label: `${message.senderColor === 'WHITE' ? 'White' : 'Black'} chat`,
        detail: `${this.timelinePlayerName(message.senderColor, message.senderModel)}: ${message.message}`,
        tone: 'chat',
        icon: 'chat',
        attemptSummary: null,
        createdAtMs: Date.parse(message.createdAt),
        createdAtLabel: message.createdAt,
        moveIndex: relatedMoveIndex,
      });
    });

    if (game && isFinishedStatus(game.status)) {
      const resignedColor = resigningColor(game);
      const resultLabel = resignedColor
        ? `${this.timelinePlayerName(resignedColor, null)} resigned`
        : game.resultReason ||
          game.result?.replaceAll('_', ' ').toLowerCase() ||
          game.status.toLowerCase();
      events.push({
        id: `result-${game.id}`,
        type: 'result',
        quality: null,
        label:
          game.status === 'CANCELLED'
            ? 'Cancelled'
            : game.status === 'FORFEIT'
              ? 'Game Forfeit'
              : 'Game Complete',
        detail: resultLabel.charAt(0).toUpperCase() + resultLabel.slice(1),
        tone:
          game.status === 'CANCELLED'
            ? 'neutral'
            : game.status === 'FORFEIT'
              ? 'warning'
              : 'success',
        attemptSummary: null,
        icon:
          game.status === 'CANCELLED'
            ? 'cancel'
            : game.status === 'FORFEIT'
              ? 'gpp_bad'
              : 'emoji_events',
        createdAtMs: game.completedAt ? Date.parse(game.completedAt) : Date.now(),
        createdAtLabel: game.completedAt || new Date().toISOString(),
        moveIndex: moves.length > 0 ? moves.length - 1 : null,
      });
    }

    return events
      .filter((event) => !Number.isNaN(event.createdAtMs))
      .sort((left, right) => right.createdAtMs - left.createdAtMs);
  });
  feedEvents = computed(() =>
    this.timelineEvents().filter((event) => this.includeChatInFeed() || event.type !== 'chat'),
  );

  constructor(
    private route: ActivatedRoute,
    private api: ApiService,
    private ws: WebSocketService,
    private router: Router,
    private snackbar: MatSnackBar,
    private turnState: GameTurnStateService,
    public boardTheme: BoardThemeService,
    private connection: OpenRouterAccess = null!,
  ) {
    this.isLive = this.turnState.isLive;
    this.activeColor = this.turnState.activeColor;
    this.moveTimerSeconds = this.turnState.moveTimerSeconds;
    this.moveTimerDisplay = this.turnState.moveTimerDisplay;
    this.moveTimerWarning = this.turnState.moveTimerWarning;
    this.noMoveTimeLimit = this.turnState.noMoveTimeLimit;
    this.turnState.bind(
      () => this.game(),
      () => this.moves(),
      // The live position, not the one selected in the move history.
      () => this.game()?.currentFen || START_FEN,
    );
  }

  ngOnInit() {
    this.gameId = this.route.snapshot.paramMap.get('id')!;
    this.loadGame();
    this.loadSystemStatus();
    this.turnState.startTicker();

    this.ws.connect();
    this.ws.subscribeToGame(this.gameId);

    this.wsSub = this.ws.getMessages(this.gameId).subscribe((msg) => this.handleWsMessage(msg));
    this.reconnectSub = this.ws.reconnected().subscribe(() => this.loadGame());
    this.queueRefreshSub = timer(15000, 15000).subscribe(() => {
      if (this.game()?.resumeRequestedAt && !this.loadInFlight) this.loadGame(true);
    });
  }

  ngOnDestroy() {
    this.ws.unsubscribeFromGame(this.gameId);
    this.wsSub?.unsubscribe();
    this.reconnectSub?.unsubscribe();
    this.queueRefreshSub?.unsubscribe();
    this.loadSub?.unsubscribe();
    this.attemptsSub?.unsubscribe();
    this.pauseSettingsSub?.unsubscribe();
    clearTimeout(this.staleReloadTimer);
    this.turnState.stopTicker();
  }

  loadGame(queuePoll = false) {
    const generation = ++this.loadGeneration;
    const polledRevision = this.knownStatusRevision;
    if (!queuePoll) {
      ++this.settingsGeneration;
      this.pauseSettingsSub?.unsubscribe();
      this.refreshingPauseSettings.set(true);
    }
    const settingsGeneration = this.settingsGeneration;
    this.loadInFlight = true;
    this.loadSub?.unsubscribe();
    this.loadSub = this.api.getGame(this.gameId).subscribe({
      next: (game) => {
        if (generation !== this.loadGeneration) {
          return;
        }
        this.loadInFlight = false;
        if (
          queuePoll &&
          (game.statusRevision == null ||
            game.statusRevision <= (polledRevision ?? -1) ||
            game.statusRevision < (this.knownStatusRevision ?? -1))
        ) {
          if (game.statusRevision === this.knownStatusRevision) {
            this.applyResumeSnapshot(game);
          }
          this.replayBufferedLiveMessages();
          return;
        }
        if (queuePoll && game.statusRevision !== this.knownStatusRevision) {
          this.turnState.clearServerTurnState();
        }
        // Respect navigation while the request was in flight; only a live-following viewer advances.
        const selectedMoveIndex = this.currentMoveIndex();
        const wasAtLatest = this.game() === null || selectedMoveIndex === this.moves().length - 1;
        const sorted = sortMovesInPlayOrder(game.moves || []);
        const moves = keepLiveEvaluations(sorted, this.moves());
        const keptLive = moves.some((move, index) => move !== sorted[index]);
        const liveSummary = this.game()?.qualitySummary;
        let reconciled = this.keepAnnouncedResult(game);
        if (settingsGeneration !== this.settingsGeneration && this.game()) {
          // A pause refresh started after this snapshot request: keep its newer effort settings.
          reconciled = {
            ...reconciled,
            whiteReasoningEffort: this.game()?.whiteReasoningEffort,
            blackReasoningEffort: this.game()?.blackReasoningEffort,
          };
        }
        // The live summary belongs to the evaluations kept from live updates.
        this.game.set(
          keptLive && liveSummary ? { ...reconciled, qualitySummary: liveSummary } : reconciled,
        );
        if (isFinishedStatus(reconciled.status)) this.pendingPromotion.set(null);
        this.moves.set(moves);
        this.chatMessages.set(game.chatMessages || []);
        this.currentMoveIndex.set(
          wasAtLatest ? moves.length - 1 : Math.min(selectedMoveIndex, moves.length - 1),
        );
        // A status change announced before the first snapshot arrived is newer than the snapshot: the replay
        // below applies it, and the stored game is read again for the details that only it carries.
        const announced = highestStatusRevision(this.bufferedLiveMessages);
        if (announced != null && (game.statusRevision == null || announced > game.statusRevision)) {
          this.scheduleStaleReload();
        }
        this.replayBufferedLiveMessages();
        this.loadAttempts();

        if (queuePoll) {
          if (this.game()?.status === 'PAUSED') this.refreshPauseSettings();
          return;
        }
        if (game.tournamentId) {
          this.api.getTournament(game.tournamentId).subscribe({
            next: (tournament) => {
              if (settingsGeneration !== this.settingsGeneration) {
                return;
              }
              // null: the tournament has no time limit per move, so there is no countdown.
              this.turnState.setMoveTimeoutSeconds(tournament.moveTimeoutSeconds ?? null);
              this.tournamentMaxOutputTokens.set(
                tournament.maxOutputTokens ?? DEFAULT_MAX_OUTPUT_TOKENS,
              );
              this.tournamentChatMode.set(
                tournament.chatMode ?? (tournament.trashTalkEnabled === false ? 'OFF' : 'OPTIONAL'),
              );
              this.tournamentBanterLevel.set(tournament.banterLevel ?? null);
              this.tournamentReasoningEffort.set(
                isReasoningEffort(tournament.reasoningEffort) ? tournament.reasoningEffort : null,
              );
              this.refreshingPauseSettings.set(false);
            },
            // Limit unknown: only a deadline sent by the server shows a countdown.
            error: () => {
              if (settingsGeneration === this.settingsGeneration) {
                this.turnState.moveTimeoutSeconds.set(null);
              }
            },
          });
        } else {
          if (settingsGeneration === this.settingsGeneration) {
            this.refreshingPauseSettings.set(false);
          }
          // Games outside a tournament use the backend's default limit.
          this.turnState.setMoveTimeoutSeconds(DEFAULT_MOVE_TIMEOUT_SECONDS);
        }
      },
      error: () => {
        if (generation !== this.loadGeneration) {
          return;
        }
        this.loadInFlight = false;
        this.replayBufferedLiveMessages();
        if (queuePoll) return;
        this.snackbar.open('Failed to load the game.', 'Dismiss', {
          duration: 4000,
          horizontalPosition: 'right',
          verticalPosition: 'top',
        });
        void this.router.navigate(['/tournaments']);
      },
    });
  }

  /**
   * A reload can be older than a status update already shown: its status revision is lower than one seen, or
   * (from a backend without revisions) it shows a finished game as running. Then keep the status already
   * announced and ask again a little later (a few times at most), so the details only the stored game carries
   * (e.g. a resignation's) arrive too.
   */
  private keepAnnouncedResult(loaded: Game): Game {
    const previous = this.game();
    const known = this.knownStatusRevision;
    const olderRevision =
      loaded.statusRevision != null && known != null && loaded.statusRevision < known;
    const reopened =
      !!previous && isFinishedStatus(previous.status) && !isFinishedStatus(loaded.status);
    if (!previous || previous.id !== loaded.id || !(olderRevision || reopened)) {
      this.staleReloads = 0;
      if (loaded.statusRevision != null) {
        this.knownStatusRevision = Math.max(known ?? loaded.statusRevision, loaded.statusRevision);
      }
      return loaded;
    }
    this.scheduleStaleReload();
    return {
      ...loaded,
      status: previous.status,
      result: previous.result,
      resultReason: previous.resultReason,
      statusDetail: previous.statusDetail ?? null,
      resumeRequestedAt: previous.resumeRequestedAt,
      runCapacity: previous.runCapacity,
      completedAt: previous.completedAt ?? loaded.completedAt,
      statusRevision: previous.statusRevision ?? known ?? loaded.statusRevision,
    };
  }

  /** Reads the game again a little later, at most {@link GameView.MAX_STALE_RELOADS} times in a row. */
  private scheduleStaleReload() {
    if (this.staleReloads < GameView.MAX_STALE_RELOADS) {
      this.staleReloads++;
      clearTimeout(this.staleReloadTimer);
      this.staleReloadTimer = setTimeout(() => this.loadGame(), GameView.STALE_RELOAD_DELAY_MS);
    }
  }

  /**
   * (Re)loads the game's attempts. A newer request supersedes an older one: the older one is cancelled and
   * its late answer ignored, so an outdated list cannot hide a newer cut-off answer (and "Save and resume").
   */
  loadAttempts() {
    const generation = ++this.attemptsGeneration;
    this.attemptsSub?.unsubscribe();
    this.attemptsSub = this.api.getGameAttempts(this.gameId).subscribe({
      next: (attempts) => {
        if (generation === this.attemptsGeneration) {
          this.attempts.set(attempts);
          this.lookUpTruncatedModelEfforts();
        }
      },
      // The feed falls back to the retry counts stored with each move.
      error: () => {
        if (generation === this.attemptsGeneration) {
          this.attempts.set([]);
        }
      },
    });
  }

  loadSystemStatus() {
    this.api.getSystemStatus().subscribe({
      next: (status) => {
        this.stockfishAvailable.set(status.stockfishAvailable);
        this.stockfishReason.set(status.stockfishReason);
      },
      error: () => {
        this.stockfishAvailable.set(false);
        this.stockfishReason.set('System status unavailable');
      },
    });
  }

  handleWsMessage(msg: WsMessage) {
    if (this.loadInFlight && (msg.type === 'move' || msg.type === 'evaluationUpdate')) {
      // The loaded game replaces the move list; apply these on top of it once it arrives.
      this.bufferedLiveMessages.push(msg);
      return;
    }
    if (
      this.loadInFlight &&
      this.game() === null &&
      (msg.type === 'gameStatus' || msg.type === 'forfeit')
    ) {
      // Before the first snapshot there is no game to apply a status change to. Keep it (with its revision)
      // and replay it on the snapshot; the revision check then keeps whichever is newer.
      this.bufferedLiveMessages.push(msg);
      return;
    }
    switch (msg.type) {
      case 'attemptsUpdated':
        // The attempt and any added cost are committed, including answers discarded after cancellation.
        // loadGame also reloads attempts and supersedes any earlier snapshot still in flight.
        this.loadGame();
        break;

      case 'move':
        this.applyLiveMove(msg);
        break;

      case 'evaluationUpdate': {
        const evaluated = this.moves().map((m) =>
          m.moveNumber === msg.moveNumber && m.color === msg.color
            ? { ...m, evaluationCp: msg.evaluationCp, evaluationMate: msg.evaluationMate }
            : m,
        );
        // The evaluation decides this move's quality and the next one's; the backend sends both.
        this.moves.set(applyPlyQualities(evaluated, msg.qualities));
        if (msg.qualitySummary) {
          const qualitySummary = msg.qualitySummary;
          this.game.update((g) => (g ? { ...g, qualitySummary } : g));
        }
        // The evaluation of the position a side resigned in may arrive after the resignation: reload so the
        // resignation shows its winning chance.
        const resignation = this.game()?.resignation;
        const last = this.moves()[this.moves().length - 1];
        if (
          resignation &&
          resignation.winChance == null &&
          last?.moveNumber === msg.moveNumber &&
          last.color === msg.color
        ) {
          this.loadGame();
        }
        break;
      }

      case 'chat':
        const newChat: ChatMessage = {
          id: '',
          moveNumber: msg.moveNumber,
          senderModel: msg.senderModel,
          senderColor: msg.senderColor,
          message: msg.message || '',
          createdAt: new Date().toISOString(),
        };
        this.chatMessages.update((list) => [...list, newChat]);
        break;

      case 'gameStatus':
        if (msg.statusRevision == null) {
          // A per-turn update (whose turn it is, its deadline, the cost so far): never a status change.
          this.applyTurnUpdate(msg);
          break;
        }
        // Status changes are sent after their commit and can arrive out of order: ignore an older one.
        if (this.knownStatusRevision != null && msg.statusRevision < this.knownStatusRevision) {
          break;
        }
        this.knownStatusRevision = msg.statusRevision;
        const status = msg.status;
        const previous = this.game();
        const queueChangedWhilePaused =
          status === 'PAUSED' &&
          previous?.status === 'PAUSED' &&
          (previous.resumeRequestedAt || msg.resumeRequestedAt);
        if (status && isFinishedStatus(status)) this.pendingPromotion.set(null);
        this.game.update((g) =>
          g
            ? {
                ...g,
                status: status || g.status,
                result: status === 'CANCELLED' ? null : msg.result || g.result,
                resultReason: status === 'CANCELLED' ? null : msg.resultReason || g.resultReason,
                statusDetail: status
                  ? status === 'PAUSED'
                    ? (msg.detail ?? null)
                    : null
                  : g.statusDetail,
                totalCostUsd: msg.totalCostUsd ?? g.totalCostUsd,
                resumeRequestedAt: msg.resumeRequestedAt ?? null,
                runCapacity: msg.runCapacity ?? null,
                statusRevision: msg.statusRevision ?? g.statusRevision,
                startedAt:
                  status === 'IN_PROGRESS'
                    ? (g.startedAt ?? new Date().toISOString())
                    : g.startedAt,
              }
            : g,
        );
        this.turnState.applyStatusUpdate(
          status,
          msg.activeColor,
          msg.turnStartedAt,
          msg.turnDeadlineAt,
        );
        // A mode changed during play shows up with the next model turn.
        if (msg.tournamentChatMode) {
          this.tournamentChatMode.set(msg.tournamentChatMode);
        }
        if (msg.tournamentBanterLevel !== undefined) {
          this.tournamentBanterLevel.set(msg.tournamentBanterLevel);
        }
        if (
          status === 'COMPLETED' &&
          (msg.resultReason === 'RESIGNATION' || msg.resultReason === 'ADJUDICATION')
        ) {
          // Result evidence is carried by the game details; reload it and the attempts.
          this.loadGame();
        } else if (
          !queueChangedWhilePaused &&
          (status === 'PAUSED' || (status && isFinishedStatus(status)))
        ) {
          if (status === 'PAUSED') {
            this.refreshPauseSettings();
          }
          this.loadAttempts();
        }
        break;

      case 'retry':
        this.retryInfo.set(retryText(msg));
        break;

      case 'forfeit':
        this.pendingPromotion.set(null);
        this.turnState.clearServerTurnState();
        this.game.update((g) => (g ? { ...g, status: 'FORFEIT' } : g));
        this.loadAttempts();
        break;
    }
  }

  /**
   * A status update without a revision is a per-turn update of a running game (the backend sends every status
   * change with its revision). It never changes the status: it updates the turn (side to move, timer), the cost
   * and the chat mode only while the game shown is running, so a delayed one can never turn a paused or finished
   * game back into a running one. The same rule holds live and when updates buffered during the first load are
   * replayed onto the snapshot.
   */
  private applyTurnUpdate(msg: GameStatusWsMessage) {
    // The tournament's chat mode is a setting, not part of the game's status: a mode changed during play shows
    // up with the next model turn.
    if (msg.tournamentChatMode) {
      this.tournamentChatMode.set(msg.tournamentChatMode);
    }
    if (msg.tournamentBanterLevel !== undefined) {
      this.tournamentBanterLevel.set(msg.tournamentBanterLevel);
    }
    const game = this.game();
    if (!game || game.status !== 'IN_PROGRESS') {
      return;
    }
    this.game.update((g) =>
      g
        ? {
            ...g,
            totalCostUsd: msg.totalCostUsd ?? g.totalCostUsd,
            startedAt: g.startedAt ?? new Date().toISOString(),
          }
        : g,
    );
    this.turnState.applyStatusUpdate(
      game.status,
      msg.activeColor,
      msg.turnStartedAt,
      msg.turnDeadlineAt,
    );
  }

  private applyLiveMove(msg: MoveWsMessage) {
    const known = this.moves().some(
      (move) => move.moveNumber === msg.moveNumber && move.color === msg.color,
    );
    if (known) {
      return;
    }
    const newMove: Move = {
      id: '',
      moveNumber: msg.moveNumber,
      color: msg.color,
      san: msg.san,
      fen: msg.fen,
      modelId: msg.modelId,
      promptVersion: null,
      promptHash: null,
      promptTokens: null,
      completionTokens: null,
      costUsd: null,
      responseTimeMs: msg.responseTimeMs || 0,
      retryCount: msg.retryCount || 0,
      isOverride: msg.isOverride ?? msg.modelId === 'admin-override',
      evaluationCp: msg.evaluationCp,
      evaluationMate: msg.evaluationMate,
      createdAt: new Date().toISOString(),
    };
    const wasAtLatest = this.currentMoveIndex() === this.moves().length - 1;
    this.moves.update((list) => [...list, newMove]);
    if (wasAtLatest) {
      this.currentMoveIndex.set(this.moves().length - 1);
    }
    this.game.update((g) => (g ? { ...g, currentFen: msg.fen, pgn: msg.pgn ?? g.pgn } : g));
    this.retryInfo.set(null);
    const plyHadAttempts = this.attempts().some(
      (a) => a.moveNumber === msg.moveNumber && a.color === msg.color,
    );
    if ((msg.retryCount ?? 0) > 0 || plyHadAttempts) {
      this.loadAttempts();
    }
  }

  private replayBufferedLiveMessages() {
    const buffered = this.bufferedLiveMessages;
    this.bufferedLiveMessages = [];
    buffered.forEach((msg) => this.handleWsMessage(msg));
  }

  flipBoard() {
    this.flipped.update((v) => !v);
  }

  goToMove(index: number) {
    this.moveList?.revealSelectedMove();
    this.currentMoveIndex.set(index);
  }

  goToTimelineEvent(event: TimelineEvent) {
    if (event.moveIndex != null) {
      this.goToMove(event.moveIndex);
    }
  }

  goFirst() {
    this.currentMoveIndex.set(-1);
  }
  goPrev() {
    this.goToMove(Math.max(-1, this.currentMoveIndex() - 1));
  }
  goNext() {
    this.goToMove(Math.min(this.moves().length - 1, this.currentMoveIndex() + 1));
  }
  goLast() {
    this.goToMove(this.moves().length - 1);
  }

  pauseGame() {
    this.api.pauseGame(this.gameId).subscribe({
      error: () => {
        this.snackbar.open('Failed to pause the game.', 'Dismiss', {
          duration: 4000,
          horizontalPosition: 'right',
          verticalPosition: 'top',
        });
      },
    });
  }

  /** A pause may follow settings changed elsewhere while this view stayed open. */
  private refreshPauseSettings(): void {
    const tournamentId = this.game()?.tournamentId;
    if (!tournamentId) {
      return;
    }
    const revision = this.knownStatusRevision;
    const settingsGeneration = ++this.settingsGeneration;
    this.pauseSettingsSub?.unsubscribe();
    this.refreshingPauseSettings.set(true);
    this.pauseSettingsSub = forkJoin({
      game: this.api.getGame(this.gameId),
      tournament: this.api.getTournament(tournamentId),
    }).subscribe({
      next: ({ game, tournament }) => {
        if (
          settingsGeneration !== this.settingsGeneration ||
          this.game()?.status !== 'PAUSED' ||
          this.knownStatusRevision !== revision
        ) {
          return;
        }
        this.game.update((current) =>
          current
            ? {
                ...current,
                whiteReasoningEffort: game.whiteReasoningEffort,
                blackReasoningEffort: game.blackReasoningEffort,
              }
            : current,
        );
        this.tournamentReasoningEffort.set(
          isReasoningEffort(tournament.reasoningEffort) ? tournament.reasoningEffort : null,
        );
        this.tournamentMaxOutputTokens.set(tournament.maxOutputTokens ?? DEFAULT_MAX_OUTPUT_TOKENS);
        this.refreshingPauseSettings.set(false);
      },
      // Keep the save controls hidden rather than offer to overwrite settings from a stale snapshot.
      error: (err) => this.showServerError(err, 'Failed to refresh the paused game settings.'),
    });
  }

  /**
   * Looks up the levels of the cut-off side's model in the catalog once, so the pause banner offers only those.
   * Until it answers, and if it fails, every level is offered (the backend maps to the nearest supported one).
   */
  private lookUpTruncatedModelEfforts(): void {
    const side = this.truncatedSide();
    const game = this.game();
    const modelId =
      side && game ? (side === 'WHITE' ? game.whiteModelId : game.blackModelId) : null;
    if (!modelId || modelId in this.modelEfforts()) {
      return;
    }
    this.modelEfforts.update((known) => ({ ...known, [modelId]: null }));
    this.api.getModels({ featuredOnly: false, q: modelId, limit: 0 }).subscribe({
      next: (response) => {
        const model = (response.data ?? []).find((option) => option.id === modelId);
        if (model) {
          this.modelEfforts.update((known) => ({ ...known, [modelId]: supportedEfforts(model) }));
        }
      },
      // Unknown: every level stays on offer.
      error: () => undefined,
    });
  }

  /** The pause banner's "Save and resume": a changed limit and the cut-off side's effort when offered. */
  saveTruncationFixAndResume(request: RaiseLimitRequest) {
    if (this.connection)
      this.connection.withKey(() => this.saveTruncationFixAndResumeWithKey(request));
    else this.saveTruncationFixAndResumeWithKey(request);
  }

  private saveTruncationFixAndResumeWithKey(request: RaiseLimitRequest) {
    this.saveLimitAndResume(request.maxOutputTokens, request.reasoningEffort);
  }

  /**
   * Saves a changed output-token limit for the game's tournament and, unless `reasoningEffort` is undefined, the
   * cut-off side's reasoning-effort override (null follows the tournament's), then resumes the game.
   */
  saveLimitAndResume(maxOutputTokens?: number, reasoningEffort?: ReasoningEffort | null) {
    const tournamentId = this.game()?.tournamentId;
    if (!tournamentId || this.savingLimit() || this.refreshingPauseSettings()) {
      return;
    }
    const effortChoice = reasoningEffort === undefined ? null : this.truncationEffort();
    const side = this.truncatedSide();
    this.savingLimit.set(true);
    const saveLimit: Observable<unknown> =
      maxOutputTokens === undefined || maxOutputTokens === this.tournamentMaxOutputTokens()
        ? of(null)
        : this.api
            .updateTournament(tournamentId, { maxOutputTokens })
            .pipe(
              tap((tournament) =>
                this.tournamentMaxOutputTokens.set(tournament?.maxOutputTokens ?? maxOutputTokens),
              ),
            );
    saveLimit
      .pipe(
        switchMap(() => {
          if (!effortChoice || !side) {
            return this.api.startGame(this.gameId, true);
          }
          return this.api
            .updateParticipant(tournamentId, effortChoice.participantId, {
              reasoningEffort: reasoningEffort ?? null,
            })
            .pipe(
              switchMap((participant) => {
                // Shown next to the model name at once; the next reload confirms it.
                const asked = participant?.reasoningEffort ?? effortChoice.tournamentLevel;
                this.game.update((game) =>
                  game
                    ? side === 'WHITE'
                      ? { ...game, whiteReasoningEffort: asked }
                      : { ...game, blackReasoningEffort: asked }
                    : game,
                );
                return this.api.startGame(this.gameId, true);
              }),
            );
        }),
      )
      .subscribe({
        next: (game) => {
          this.savingLimit.set(false);
          this.applyResumeSnapshot(game);
        },
        error: (err) => {
          this.savingLimit.set(false);
          this.showServerError(err, 'Failed to save the limit and resume the game.');
        },
      });
  }

  resumeGame() {
    if (this.connection) this.connection.withKey(() => this.resumeGameWithKey());
    else this.resumeGameWithKey();
  }

  private resumeGameWithKey() {
    if (this.savingLimit()) {
      return;
    }
    this.api.startGame(this.gameId, true).subscribe({
      next: (game) => this.applyResumeSnapshot(game),
      error: (err) => {
        this.showServerError(err, 'Failed to resume the game.');
      },
    });
  }

  cancelResumeRequest(displayed: Game): void {
    if (displayed.statusRevision == null) {
      this.loadGame();
      return;
    }
    this.api.cancelResumeRequest(displayed.id, displayed.statusRevision).subscribe({
      next: (game) => this.applyResumeSnapshot(game),
      error: (err) => {
        this.showServerError(err, 'Failed to cancel the queued resume.');
        if (err.status === 409) this.loadGame();
      },
    });
  }

  private applyResumeSnapshot(game: Game): void {
    const current = this.game();
    if (!current || game?.statusRevision == null) return;
    if (game.statusRevision === this.knownStatusRevision) {
      if (current.resumeRequestedAt) this.game.set({ ...current, runCapacity: game.runCapacity });
      return;
    }
    this.handleWsMessage({
      type: 'gameStatus',
      gameId: game.id,
      status: game.status,
      statusRevision: game.statusRevision,
      detail: game.statusDetail,
      result: game.result,
      resultReason: game.resultReason,
      totalCostUsd: game.totalCostUsd,
      resumeRequestedAt: game.resumeRequestedAt,
      runCapacity: game.runCapacity,
    });
  }

  submitHumanMove() {
    if (!this.isAwaitingHumanMove() || this.humanMoveSubmitting()) {
      return;
    }
    const move = this.humanMove.trim();
    if (!move) {
      this.snackbar.open('Enter a SAN move before submitting.', 'Dismiss', {
        duration: 4000,
        horizontalPosition: 'right',
        verticalPosition: 'top',
      });
      return;
    }

    this.humanMoveSubmitting.set(true);
    this.api
      .submitHumanMove(this.gameId, move, this.normalizeMessage(this.humanMessage))
      .subscribe({
        next: () => {
          this.humanMove = '';
          this.humanMessage = '';
          this.humanMoveSubmitting.set(false);
        },
        error: (err) => {
          const serverError = err?.error;
          const message =
            typeof serverError === 'string'
              ? serverError
              : (serverError?.error ?? 'Failed to submit the human move.');
          this.snackbar.open(message, 'Dismiss', {
            duration: 5000,
            horizontalPosition: 'right',
            verticalPosition: 'top',
          });
          this.humanMoveSubmitting.set(false);
        },
      });
  }

  onBoardMoveAttempt(attempt: BoardMoveAttempt) {
    if (!this.isAwaitingHumanMove() || this.humanMoveSubmitting()) return;
    if (attempt.promotionRequired) {
      this.pendingPromotion.set({ from: attempt.from, to: attempt.to });
      return;
    }
    this.submitCoordinateMove(attempt.from, attempt.to, null);
  }

  selectPromotion(piece: string) {
    const pending = this.pendingPromotion();
    this.pendingPromotion.set(null);
    if (!pending || !this.isAwaitingHumanMove() || this.humanMoveSubmitting()) return;
    this.submitCoordinateMove(pending.from, pending.to, piece);
  }

  cancelPromotion() {
    this.pendingPromotion.set(null);
  }

  private submitCoordinateMove(from: string, to: string, promotion: string | null) {
    if (!this.isAwaitingHumanMove() || this.humanMoveSubmitting()) return;
    this.humanMoveSubmitting.set(true);
    this.api
      .submitHumanMoveCoordinates(
        this.gameId,
        from,
        to,
        promotion,
        this.normalizeMessage(this.humanMessage),
      )
      .subscribe({
        next: () => {
          this.humanMessage = '';
          this.humanMoveSubmitting.set(false);
        },
        error: (err) => {
          const serverError = err?.error;
          const message =
            typeof serverError === 'string' ? serverError : (serverError?.error ?? 'Invalid move.');
          this.snackbar.open(message, 'Dismiss', {
            duration: 5000,
            horizontalPosition: 'right',
            verticalPosition: 'top',
          });
          this.humanMoveSubmitting.set(false);
        },
      });
  }

  goToTournament() {
    const tournamentId = this.game()?.tournamentId;
    if (!tournamentId) return;
    this.router.navigate(['/tournaments', tournamentId]);
  }

  submitOverride() {
    if (!this.overrideAllowed()) {
      this.snackbar.open('Pause the game before submitting an override move.', 'Dismiss', {
        duration: 4000,
        horizontalPosition: 'right',
        verticalPosition: 'top',
      });
      return;
    }
    if (!this.overrideMove) return;
    this.api.overrideMove(this.gameId, this.overrideMove).subscribe({
      next: () => {
        this.overrideMove = '';
      },
      error: (err) => {
        const serverError = err?.error;
        const message =
          typeof serverError === 'string' ? serverError : (serverError?.error ?? 'Invalid move.');
        this.snackbar.open(`Invalid move: ${message}`, 'Dismiss', {
          duration: 5000,
          horizontalPosition: 'right',
          verticalPosition: 'top',
        });
      },
    });
  }

  exportPgn() {
    this.api.getGamePgn(this.gameId).subscribe({
      next: (pgn) => {
        downloadBlob(
          new Blob([pgn], { type: 'application/x-chess-pgn;charset=utf-8' }),
          `game-${this.gameId}.pgn`,
        );
      },
      error: () => {
        this.snackbar.open('Could not export game. Please try again.', 'OK', { duration: 5000 });
      },
    });
  }

  private findTimelineMoveIndex(
    moveNumber: number,
    color: 'WHITE' | 'BLACK' | string,
  ): number | null {
    const normalizedColor = color === 'WHITE' || color === 'BLACK' ? color : null;
    const moves = this.moves();
    const index = moves.findIndex(
      (move) =>
        move.moveNumber === moveNumber && (!normalizedColor || move.color === normalizedColor),
    );
    return index >= 0 ? index : null;
  }

  private timelinePlayerName(color: 'WHITE' | 'BLACK', fallbackModel: string | null): string {
    const game = this.game();
    if (color === 'WHITE') {
      return game?.whitePlayerName || fallbackModel || 'White';
    }
    return game?.blackPlayerName || fallbackModel || 'Black';
  }

  private showServerError(err: { error?: unknown } | null | undefined, fallback: string): void {
    const serverError = err?.error as string | { error?: string } | null | undefined;
    const message =
      typeof serverError === 'string' ? serverError : (serverError?.error ?? fallback);
    this.snackbar.open(message, 'Dismiss', {
      duration: 4000,
      horizontalPosition: 'right',
      verticalPosition: 'top',
    });
  }

  private normalizeMessage(value: string): string | null {
    const trimmed = value.trim();
    return trimmed.length > 0 ? trimmed : null;
  }
}

/**
 * The loaded moves, except that a move the reload has without an evaluation keeps the evaluation (and quality)
 * that already arrived live for the same ply: a reload must not erase fresher live data.
 */
function keepLiveEvaluations(loaded: Move[], live: Move[]): Move[] {
  return loaded.map((move) => {
    if (move.evaluationCp != null || move.evaluationMate != null) {
      return move;
    }
    const known = live.find(
      (other) =>
        other.moveNumber === move.moveNumber &&
        other.color === move.color &&
        (other.evaluationCp != null || other.evaluationMate != null),
    );
    return known
      ? {
          ...move,
          evaluationCp: known.evaluationCp,
          evaluationMate: known.evaluationMate,
          quality: move.quality ?? known.quality,
        }
      : move;
  });
}

/** The highest status revision among buffered status updates; null when none carries one. */
function highestStatusRevision(messages: WsMessage[]): number | null {
  let highest: number | null = null;
  for (const msg of messages) {
    if (msg.type === 'gameStatus' && msg.statusRevision != null) {
      highest = Math.max(highest ?? msg.statusRevision, msg.statusRevision);
    }
  }
  return highest;
}

function isFinishedStatus(status: Game['status']): boolean {
  return status === 'COMPLETED' || status === 'FORFEIT' || status === 'CANCELLED';
}

/** The colour that resigned, from the result; null when the game did not end by resignation. */
function resigningColor(game: Game): 'WHITE' | 'BLACK' | null {
  if (game.resultReason !== 'RESIGNATION') {
    return null;
  }
  if (game.result === 'WHITE_WINS') {
    return 'BLACK';
  }
  return game.result === 'BLACK_WINS' ? 'WHITE' : null;
}

function retryText(msg: RetryWsMessage): string {
  const reason =
    msg.outcome === 'ILLEGAL_MOVE' && msg.proposedMove
      ? `illegal move ${msg.proposedMove}`
      : msg.reason;
  return `${msg.color} attempt ${msg.attemptNumber}: ${reason}`;
}
