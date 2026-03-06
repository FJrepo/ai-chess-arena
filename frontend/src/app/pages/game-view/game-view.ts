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
import { ApiService } from '../../services/api.service';
import { WebSocketService } from '../../services/websocket.service';
import { Game, Move, ChatMessage, WsMessage } from '../../models/tournament.model';
import { ChessboardComponent } from '../../components/chessboard/chessboard';
import { MoveListComponent } from '../../components/move-list/move-list';
import { ChatPanel } from '../../components/chat-panel/chat-panel';
import { GameInfoBar } from '../../components/game-info-bar/game-info-bar';
import { Subscription } from 'rxjs';
import { CapturablePiece, deriveCapturedMaterial } from '../../utils/captured-material';
import { AdvantageBar } from '../../components/advantage-bar/advantage-bar';

type TimelineEvent = {
  id: string;
  type: 'start' | 'move' | 'chat' | 'result';
  label: string;
  detail: string;
  tone: 'neutral' | 'move' | 'chat' | 'warning' | 'success';
  icon: string;
  createdAtMs: number;
  createdAtLabel: string;
  moveIndex: number | null;
};

const WHITE_PIECE_GLYPHS: Record<CapturablePiece, string> = {
  Q: '\u2655',
  R: '\u2656',
  B: '\u2657',
  N: '\u2658',
  P: '\u2659',
};

const BLACK_PIECE_GLYPHS: Record<CapturablePiece, string> = {
  Q: '\u265B',
  R: '\u265C',
  B: '\u265D',
  N: '\u265E',
  P: '\u265F',
};

@Component({
  selector: 'app-game-view',
  standalone: true,
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
  currentMoveIndex = signal(-1);
  flipped = signal(false);
  overrideMove = '';
  retryInfo = signal<string | null>(null);
  moveTimeoutSeconds = signal(60);
  timerNowMs = signal(Date.now());
  serverActiveColor = signal<'WHITE' | 'BLACK' | null>(null);
  serverTurnStartedAtMs = signal<number | null>(null);
  serverTurnDeadlineAtMs = signal<number | null>(null);
  stockfishAvailable = signal(true);
  stockfishReason = signal<string | null>(null);

  private gameId = '';
  private wsSub?: Subscription;
  private timerHandle?: ReturnType<typeof setInterval>;

  currentFen = computed(() => {
    const idx = this.currentMoveIndex();
    const moveList = this.moves();
    if (idx < 0 || moveList.length === 0) {
      return this.game()?.currentFen || 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1';
    }
    return moveList[idx]?.fen || this.game()?.currentFen || '';
  });

  currentEvaluation = computed(() => {
    const idx = this.currentMoveIndex();
    const moveList = this.moves();

    if (idx < 0) {
      return { cp: 0, mate: null };
    }

    const move = moveList[idx];
    if (!move) return { cp: 0, mate: null };

    return {
      cp: move.evaluationCp ?? null,
      mate: move.evaluationMate ?? null,
    };
  });

  isLive = computed(() => {
    const g = this.game();
    return g?.status === 'IN_PROGRESS';
  });

  overrideAllowed = computed(() => this.game()?.status === 'PAUSED');

  activeColor = computed<'WHITE' | 'BLACK' | null>(() => {
    if (!this.isLive()) return null;
    const serverColor = this.serverActiveColor();
    if (serverColor) return serverColor;
    const fen = this.currentFen();
    const sideToken = fen.split(' ')[1];
    if (sideToken === 'w') return 'WHITE';
    if (sideToken === 'b') return 'BLACK';
    return null;
  });

  turnStartedAtMs = computed<number | null>(() => {
    if (!this.isLive()) return null;
    const serverStartedAt = this.serverTurnStartedAtMs();
    if (serverStartedAt != null) return serverStartedAt;

    const moveList = this.moves();
    if (moveList.length > 0) {
      const ts = Date.parse(moveList[moveList.length - 1].createdAt);
      return Number.isNaN(ts) ? null : ts;
    }

    const startedAt = this.game()?.startedAt;
    if (!startedAt) return null;
    const ts = Date.parse(startedAt);
    return Number.isNaN(ts) ? null : ts;
  });

  moveTimerSeconds = computed<number | null>(() => {
    if (!this.isLive()) return null;
    const serverDeadline = this.serverTurnDeadlineAtMs();
    if (serverDeadline != null) {
      const remaining = Math.ceil((serverDeadline - this.timerNowMs()) / 1000);
      return Math.max(0, remaining);
    }
    const timeout = Math.max(1, this.moveTimeoutSeconds());
    const startedAtMs = this.turnStartedAtMs();
    if (!startedAtMs) return timeout;

    const elapsedMs = this.timerNowMs() - startedAtMs;
    const remaining = Math.ceil((timeout * 1000 - elapsedMs) / 1000);
    return Math.max(0, remaining);
  });

  moveTimerDisplay = computed(() => {
    const remaining = this.moveTimerSeconds();
    if (remaining == null) return '--:--';
    const mins = Math.floor(remaining / 60)
      .toString()
      .padStart(2, '0');
    const secs = (remaining % 60).toString().padStart(2, '0');
    return `${mins}:${secs}`;
  });

  moveTimerWarning = computed(() => {
    const remaining = this.moveTimerSeconds();
    return remaining != null && remaining <= 10 && this.isLive();
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
    this.capturedMaterial().whiteCaptured.map((piece) => BLACK_PIECE_GLYPHS[piece]),
  );

  blackCapturedGlyphs = computed(() =>
    this.capturedMaterial().blackCaptured.map((piece) => WHITE_PIECE_GLYPHS[piece]),
  );

  whiteMaterialEdge = computed(() => Math.max(0, this.capturedMaterial().materialDelta));
  blackMaterialEdge = computed(() => Math.max(0, -this.capturedMaterial().materialDelta));
  timelineEvents = computed<TimelineEvent[]>(() => {
    const events: TimelineEvent[] = [];
    const moves = this.moves();
    const chats = this.chatMessages();
    const game = this.game();

    if (game?.startedAt) {
      events.push({
        id: `start-${game.id}`,
        type: 'start',
        label: 'Game Started',
        detail: `${game.whitePlayerName || 'White'} vs ${game.blackPlayerName || 'Black'}`,
        tone: 'neutral',
        icon: 'flag',
        createdAtMs: Date.parse(game.startedAt),
        createdAtLabel: game.startedAt,
        moveIndex: moves.length > 0 ? 0 : null,
      });
    }

    moves.forEach((move, index) => {
      const retrySuffix =
        move.retryCount > 0
          ? ` after ${move.retryCount} retr${move.retryCount === 1 ? 'y' : 'ies'}`
          : '';
      const overrideSuffix = move.isOverride ? ' (override)' : '';
      events.push({
        id: `move-${move.id || `${move.moveNumber}-${move.color}`}`,
        type: 'move',
        label: `${move.color === 'WHITE' ? 'White' : 'Black'} played ${move.san}`,
        detail: `${move.modelId || 'Unknown model'}${retrySuffix}${overrideSuffix}`,
        tone: move.isOverride ? 'warning' : 'move',
        icon: move.isOverride ? 'edit' : 'arrow_right_alt',
        createdAtMs: Date.parse(move.createdAt),
        createdAtLabel: move.createdAt,
        moveIndex: index,
      });
    });

    chats.forEach((message) => {
      const relatedMoveIndex = this.findTimelineMoveIndex(message.moveNumber, message.senderColor);
      events.push({
        id: `chat-${message.id || `${message.moveNumber}-${message.senderColor}-${message.createdAt}`}`,
        type: 'chat',
        label: `${message.senderColor === 'WHITE' ? 'White' : 'Black'} chat`,
        detail: `${this.timelinePlayerName(message.senderColor, message.senderModel)}: ${message.message}`,
        tone: 'chat',
        icon: 'chat',
        createdAtMs: Date.parse(message.createdAt),
        createdAtLabel: message.createdAt,
        moveIndex: relatedMoveIndex,
      });
    });

    if (game && (game.status === 'COMPLETED' || game.status === 'FORFEIT')) {
      const resultLabel =
        game.resultReason ||
        game.result?.replaceAll('_', ' ').toLowerCase() ||
        game.status.toLowerCase();
      events.push({
        id: `result-${game.id}`,
        type: 'result',
        label: game.status === 'FORFEIT' ? 'Game Forfeit' : 'Game Complete',
        detail: resultLabel.charAt(0).toUpperCase() + resultLabel.slice(1),
        tone: game.status === 'FORFEIT' ? 'warning' : 'success',
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

  constructor(
    private route: ActivatedRoute,
    private api: ApiService,
    private ws: WebSocketService,
    private router: Router,
    private snackbar: MatSnackBar,
  ) {}

  ngOnInit() {
    this.gameId = this.route.snapshot.paramMap.get('id')!;
    this.loadGame();
    this.loadSystemStatus();
    this.startTimerTicker();

    this.ws.connect();
    this.ws.subscribeToGame(this.gameId);

    this.wsSub = this.ws.getMessages(this.gameId).subscribe((msg) => this.handleWsMessage(msg));
  }

  ngOnDestroy() {
    this.ws.unsubscribeFromGame(this.gameId);
    this.wsSub?.unsubscribe();
    if (this.timerHandle) {
      clearInterval(this.timerHandle);
      this.timerHandle = undefined;
    }
  }

  loadGame() {
    this.api.getGame(this.gameId).subscribe((game) => {
      this.game.set(game);
      this.moves.set(game.moves || []);
      this.chatMessages.set(game.chatMessages || []);
      this.serverActiveColor.set(null);
      this.serverTurnStartedAtMs.set(null);
      this.serverTurnDeadlineAtMs.set(null);
      if (game.moves?.length) {
        this.currentMoveIndex.set(game.moves.length - 1);
      }

      if (game.tournamentId) {
        this.api.getTournament(game.tournamentId).subscribe({
          next: (tournament) =>
            this.moveTimeoutSeconds.set(Math.max(1, tournament.moveTimeoutSeconds || 60)),
          error: () => this.moveTimeoutSeconds.set(60),
        });
      } else {
        this.moveTimeoutSeconds.set(60);
      }
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
    switch (msg.type) {
      case 'move':
        const newMove: Move = {
          id: '',
          moveNumber: msg['moveNumber']!,
          color: msg['color']!,
          san: msg['san']!,
          fen: msg['fen']!,
          modelId: msg['modelId']!,
          promptVersion: null,
          promptHash: null,
          promptTokens: null,
          completionTokens: null,
          costUsd: null,
          responseTimeMs: msg['responseTimeMs'] || 0,
          retryCount: msg['retryCount'] || 0,
          isOverride: false,
          evaluationCp: msg['evaluationCp'],
          evaluationMate: msg['evaluationMate'],
          createdAt: new Date().toISOString(),
        };
        const wasAtLatest = this.currentMoveIndex() === this.moves().length - 1;
        this.moves.update((list) => [...list, newMove]);
        if (wasAtLatest) {
          this.currentMoveIndex.set(this.moves().length - 1);
        }
        this.game.update((g) => (g ? { ...g, currentFen: msg['fen'], pgn: msg['pgn'] } : g));
        this.retryInfo.set(null);
        break;

      case 'evaluationUpdate':
        this.moves.update((list) =>
          list.map((m) =>
            m.moveNumber === msg['moveNumber'] && m.color === msg['color']
              ? { ...m, evaluationCp: msg['evaluationCp'], evaluationMate: msg['evaluationMate'] }
              : m,
          ),
        );
        break;

      case 'chat':
        const newChat: ChatMessage = {
          id: '',
          moveNumber: msg['moveNumber']!,
          senderModel: msg['senderModel']!,
          senderColor: msg['senderColor'] as 'WHITE' | 'BLACK',
          message: msg['message'] || '',
          createdAt: new Date().toISOString(),
        };
        this.chatMessages.update((list) => [...list, newChat]);
        break;

      case 'gameStatus':
        const status = msg['status'];
        this.game.update((g) =>
          g
            ? {
                ...g,
                status: status || g.status,
                result: msg['result'] || g.result,
                resultReason: msg['resultReason'] || g.resultReason,
                totalCostUsd: msg['totalCostUsd'] ?? g.totalCostUsd,
                startedAt:
                  status === 'IN_PROGRESS'
                    ? (g.startedAt ?? new Date().toISOString())
                    : g.startedAt,
              }
            : g,
        );
        if (status === 'IN_PROGRESS') {
          const activeColor = msg['activeColor'];
          if (activeColor === 'WHITE' || activeColor === 'BLACK') {
            this.serverActiveColor.set(activeColor);
          }
          const startedAtMs = this.parseWsDate(msg['turnStartedAt']);
          if (startedAtMs != null) {
            this.serverTurnStartedAtMs.set(startedAtMs);
          }
          const deadlineAtMs = this.parseWsDate(msg['turnDeadlineAt']);
          if (deadlineAtMs != null) {
            this.serverTurnDeadlineAtMs.set(deadlineAtMs);
          }
        } else {
          this.serverActiveColor.set(null);
          this.serverTurnStartedAtMs.set(null);
          this.serverTurnDeadlineAtMs.set(null);
        }
        break;

      case 'retry':
        this.retryInfo.set(`${msg['color']} attempt ${msg['attemptNumber']}: ${msg['reason']}`);
        break;

      case 'forfeit':
        this.game.update((g) => (g ? { ...g, status: 'FORFEIT' } : g));
        break;
    }
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
    this.api.pauseGame(this.gameId).subscribe();
  }

  resumeGame() {
    this.api.startGame(this.gameId).subscribe();
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

  private startTimerTicker() {
    this.timerNowMs.set(Date.now());
    this.timerHandle = setInterval(() => {
      this.timerNowMs.set(Date.now());
    }, 1000);
  }

  private parseWsDate(value: unknown): number | null {
    if (typeof value !== 'string' || value.length === 0) {
      return null;
    }
    const parsed = Date.parse(value);
    return Number.isNaN(parsed) ? null : parsed;
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

  private timelinePlayerName(color: 'WHITE' | 'BLACK', fallbackModel: string): string {
    const game = this.game();
    if (color === 'WHITE') {
      return game?.whitePlayerName || fallbackModel;
    }
    return game?.blackPlayerName || fallbackModel;
  }
}
