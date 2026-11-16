import { DatePipe } from '@angular/common';
import { Component, OnInit, OnDestroy, signal, computed } from '@angular/core';
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
import { WebSocketService } from '../../services/websocket.service';
import {
  Game,
  Move,
  ChatMessage,
  ChatMode,
  MoveAttempt,
  MoveWsMessage,
  RetryWsMessage,
  WsMessage,
} from '../../models/tournament.model';
import { ChessboardComponent, BoardMoveAttempt } from '../../components/chessboard/chessboard';
import { MoveListComponent } from '../../components/move-list/move-list';
import { ChatPanel } from '../../components/chat-panel/chat-panel';
import { GameInfoBar } from '../../components/game-info-bar/game-info-bar';
import { Subscription, switchMap } from 'rxjs';
import { CapturablePiece, deriveCapturedMaterial } from '../../utils/captured-material';
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
const DEFAULT_MAX_OUTPUT_TOKENS = 4096;

// Solid glyphs for both colours; the stylesheet colours them (white pieces get a light fill
// and a dark edge).
const PIECE_GLYPHS: Record<CapturablePiece, string> = {
  Q: '\u265B',
  R: '\u265C',
  B: '\u265D',
  N: '\u265E',
  P: '\u265F',
};

@Component({
  selector: 'app-game-view',
  standalone: true,
  providers: [GameTurnStateService],
  imports: [
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
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
  savingLimit = signal(false);
  /**
   * The current output-token limit when the game is paused because the latest answer for the ply to be
   * played was cut off at it (outcome TRUNCATED); null otherwise.
   */
  truncationPauseLimit = computed<number | null>(() => {
    const game = this.game();
    if (!game || game.status !== 'PAUSED' || !game.statusDetail || !game.tournamentId) {
      return null;
    }
    const ply = nextPly(this.moves());
    const latest = latestAttemptFor(this.attempts(), ply.moveNumber, ply.color);
    if (latest?.outcome !== 'TRUNCATED') {
      return null;
    }
    return this.tournamentMaxOutputTokens() ?? DEFAULT_MAX_OUTPUT_TOKENS;
  });
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

  whiteCapturedGlyphs = computed(() =>
    this.capturedMaterial().whiteCaptured.map((piece) => PIECE_GLYPHS[piece]),
  );

  blackCapturedGlyphs = computed(() =>
    this.capturedMaterial().blackCaptured.map((piece) => PIECE_GLYPHS[piece]),
  );

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
      const badge = qualityBadge(move.quality);
      events.push({
        id: `move-${move.id || `${move.moveNumber}-${move.color}`}`,
        type: 'move',
        quality: badge ? { badge, tooltip: qualityTooltip(move.quality) } : null,
        label: `${move.color === 'WHITE' ? 'White' : 'Black'} played ${move.san}`,
        detail: `${this.timelinePlayerName(move.color, move.modelId)}${overrideSuffix}`,
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

    if (game && (game.status === 'COMPLETED' || game.status === 'FORFEIT')) {
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
        label: game.status === 'FORFEIT' ? 'Game Forfeit' : 'Game Complete',
        detail: resultLabel.charAt(0).toUpperCase() + resultLabel.slice(1),
        tone: game.status === 'FORFEIT' ? 'warning' : 'success',
        attemptSummary: null,
        icon: game.status === 'FORFEIT' ? 'gpp_bad' : 'emoji_events',
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
  }

  ngOnDestroy() {
    this.ws.unsubscribeFromGame(this.gameId);
    this.wsSub?.unsubscribe();
    this.reconnectSub?.unsubscribe();
    this.loadSub?.unsubscribe();
    this.attemptsSub?.unsubscribe();
    this.turnState.stopTicker();
  }

  loadGame() {
    const generation = ++this.loadGeneration;
    this.loadInFlight = true;
    this.loadSub?.unsubscribe();
    this.loadSub = this.api.getGame(this.gameId).subscribe({
      next: (game) => {
        if (generation !== this.loadGeneration) {
          return;
        }
        this.loadInFlight = false;
        this.game.set(game);
        const moves = sortMovesInPlayOrder(game.moves || []);
        this.moves.set(moves);
        this.chatMessages.set(game.chatMessages || []);
        if (moves.length) {
          this.currentMoveIndex.set(moves.length - 1);
        }
        this.replayBufferedLiveMessages();
        this.loadAttempts();

        if (game.tournamentId) {
          this.api.getTournament(game.tournamentId).subscribe({
            next: (tournament) => {
              // null: the tournament has no time limit per move, so there is no countdown.
              this.turnState.setMoveTimeoutSeconds(tournament.moveTimeoutSeconds ?? null);
              this.tournamentMaxOutputTokens.set(
                tournament.maxOutputTokens ?? DEFAULT_MAX_OUTPUT_TOKENS,
              );
              this.tournamentChatMode.set(
                tournament.chatMode ?? (tournament.trashTalkEnabled === false ? 'OFF' : 'OPTIONAL'),
              );
            },
            // Limit unknown: only a deadline sent by the server shows a countdown.
            error: () => this.turnState.moveTimeoutSeconds.set(null),
          });
        } else {
          // Games outside a tournament keep the backend's 60-second limit.
          this.turnState.setMoveTimeoutSeconds(60);
        }
      },
      error: () => {
        if (generation !== this.loadGeneration) {
          return;
        }
        this.loadInFlight = false;
        this.replayBufferedLiveMessages();
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
    switch (msg.type) {
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
        const status = msg.status;
        this.game.update((g) =>
          g
            ? {
                ...g,
                status: status || g.status,
                result: msg.result || g.result,
                resultReason: msg.resultReason || g.resultReason,
                statusDetail: status
                  ? status === 'PAUSED'
                    ? (msg.detail ?? null)
                    : null
                  : g.statusDetail,
                totalCostUsd: msg.totalCostUsd ?? g.totalCostUsd,
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
        if (status === 'COMPLETED' && msg.resultReason === 'RESIGNATION') {
          // A resignation has details (winning chance, last words) that only the game itself carries; the
          // reload also brings the attempts.
          this.loadGame();
        } else if (status === 'PAUSED' || status === 'COMPLETED' || status === 'FORFEIT') {
          this.loadAttempts();
        }
        break;

      case 'retry':
        this.retryInfo.set(retryText(msg));
        break;

      case 'forfeit':
        this.turnState.clearServerTurnState();
        this.game.update((g) => (g ? { ...g, status: 'FORFEIT' } : g));
        this.loadAttempts();
        break;
    }
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
    this.currentMoveIndex.set(index);
  }

  goToTimelineEvent(event: TimelineEvent) {
    if (event.moveIndex != null) {
      this.currentMoveIndex.set(event.moveIndex);
    }
  }

  goFirst() {
    this.currentMoveIndex.set(-1);
  }
  goPrev() {
    this.currentMoveIndex.update((i) => Math.max(-1, i - 1));
  }
  goNext() {
    this.currentMoveIndex.update((i) => Math.min(this.moves().length - 1, i + 1));
  }
  goLast() {
    this.currentMoveIndex.set(this.moves().length - 1);
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

  /** Saves a raised output-token limit for the game's tournament, then resumes the game. */
  saveLimitAndResume(maxOutputTokens: number) {
    const tournamentId = this.game()?.tournamentId;
    if (!tournamentId || this.savingLimit()) {
      return;
    }
    this.savingLimit.set(true);
    this.api
      .updateTournament(tournamentId, { maxOutputTokens })
      .pipe(
        switchMap((tournament) => {
          this.tournamentMaxOutputTokens.set(tournament?.maxOutputTokens ?? maxOutputTokens);
          return this.api.startGame(this.gameId);
        }),
      )
      .subscribe({
        next: () => this.savingLimit.set(false),
        error: (err) => {
          this.savingLimit.set(false);
          this.showServerError(err, 'Failed to save the limit and resume the game.');
        },
      });
  }

  resumeGame() {
    this.api.startGame(this.gameId).subscribe({
      error: (err) => {
        this.showServerError(err, 'Failed to resume the game.');
      },
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
    if (attempt.promotionRequired) {
      this.pendingPromotion.set({ from: attempt.from, to: attempt.to });
      return;
    }
    this.submitCoordinateMove(attempt.from, attempt.to, null);
  }

  selectPromotion(piece: string) {
    const pending = this.pendingPromotion();
    if (!pending) return;
    this.pendingPromotion.set(null);
    this.submitCoordinateMove(pending.from, pending.to, piece);
  }

  cancelPromotion() {
    this.pendingPromotion.set(null);
  }

  private submitCoordinateMove(from: string, to: string, promotion: string | null) {
    if (this.humanMoveSubmitting()) return;
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
    this.api.getGamePgn(this.gameId).subscribe((pgn) => {
      const blob = new Blob([pgn], { type: 'text/plain' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `game-${this.gameId}.pgn`;
      a.click();
      URL.revokeObjectURL(url);
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
