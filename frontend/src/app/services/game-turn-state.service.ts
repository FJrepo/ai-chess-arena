import { Injectable, computed, signal } from '@angular/core';
import { Game, Move } from '../models/tournament.model';

@Injectable()
export class GameTurnStateService {
  /** The game's time limit per move in seconds; null while unknown or when there is no limit. */
  readonly moveTimeoutSeconds = signal<number | null>(null);
  /** The tournament has no time limit per move: there is no countdown. */
  readonly noMoveTimeLimit = signal(false);
  readonly timerNowMs = signal(Date.now());
  readonly serverActiveColor = signal<'WHITE' | 'BLACK' | null>(null);
  readonly serverTurnStartedAtMs = signal<number | null>(null);
  readonly serverTurnDeadlineAtMs = signal<number | null>(null);

  readonly isLive = computed(() => this.game()?.status === 'IN_PROGRESS');
  readonly activeColor = computed<'WHITE' | 'BLACK' | null>(() => {
    if (!this.isLive()) return null;
    const serverColor = this.serverActiveColor();
    if (serverColor) return serverColor;
    const sideToken = this.currentFen().split(' ')[1];
    if (sideToken === 'w') return 'WHITE';
    if (sideToken === 'b') return 'BLACK';
    return null;
  });

  readonly turnStartedAtMs = computed<number | null>(() => {
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

  readonly moveTimerSeconds = computed<number | null>(() => {
    if (!this.isLive()) return null;
    const serverDeadline = this.serverTurnDeadlineAtMs();
    if (serverDeadline != null) {
      const remaining = Math.ceil((serverDeadline - this.timerNowMs()) / 1000);
      return Math.max(0, remaining);
    }

    // Without a (known) limit there is nothing to count down.
    const limit = this.moveTimeoutSeconds();
    if (limit == null) return null;
    const timeout = Math.max(1, limit);
    const startedAtMs = this.turnStartedAtMs();
    if (!startedAtMs) return timeout;

    const elapsedMs = this.timerNowMs() - startedAtMs;
    const remaining = Math.ceil((timeout * 1000 - elapsedMs) / 1000);
    return Math.max(0, remaining);
  });

  readonly moveTimerDisplay = computed(() => {
    const remaining = this.moveTimerSeconds();
    if (remaining == null) return '--:--';
    const mins = Math.floor(remaining / 60)
      .toString()
      .padStart(2, '0');
    const secs = (remaining % 60).toString().padStart(2, '0');
    return `${mins}:${secs}`;
  });

  readonly moveTimerWarning = computed(() => {
    const remaining = this.moveTimerSeconds();
    return remaining != null && remaining <= 10 && this.isLive();
  });

  private game: () => Game | null = () => null;
  private moves: () => Move[] = () => [];
  private currentFen: () => string = () =>
    'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1';
  private timerHandle: ReturnType<typeof setInterval> | null = null;

  bind(game: () => Game | null, moves: () => Move[], currentFen: () => string): void {
    this.game = game;
    this.moves = moves;
    this.currentFen = currentFen;
    if (game()?.status !== 'IN_PROGRESS') {
      this.clearServerTurnState();
    }
  }

  /** Sets the time limit per move; null (or 0) means the game has no time limit per move. */
  setMoveTimeoutSeconds(seconds: number | null): void {
    const limited = seconds != null && seconds > 0;
    this.moveTimeoutSeconds.set(limited ? seconds : null);
    this.noMoveTimeLimit.set(!limited);
  }

  applyStatusUpdate(
    status: Game['status'] | undefined,
    activeColor: 'WHITE' | 'BLACK' | null | undefined,
    turnStartedAt: string | undefined,
    turnDeadlineAt: string | undefined,
  ): void {
    if (status === 'IN_PROGRESS') {
      this.serverActiveColor.set(
        activeColor === 'WHITE' || activeColor === 'BLACK' ? activeColor : null,
      );
      const startedAtMs = this.parseWsDate(turnStartedAt);
      this.serverTurnStartedAtMs.set(startedAtMs);
      const deadlineAtMs = this.parseWsDate(turnDeadlineAt);
      this.serverTurnDeadlineAtMs.set(deadlineAtMs);
      return;
    }

    this.clearServerTurnState();
  }

  clearServerTurnState(): void {
    this.serverActiveColor.set(null);
    this.serverTurnStartedAtMs.set(null);
    this.serverTurnDeadlineAtMs.set(null);
  }

  startTicker(): void {
    this.timerNowMs.set(Date.now());
    if (this.timerHandle) {
      clearInterval(this.timerHandle);
    }
    this.timerHandle = setInterval(() => {
      this.timerNowMs.set(Date.now());
    }, 1000);
  }

  stopTicker(): void {
    if (this.timerHandle) {
      clearInterval(this.timerHandle);
      this.timerHandle = null;
    }
  }

  private parseWsDate(value: unknown): number | null {
    if (typeof value !== 'string' || value.length === 0) {
      return null;
    }
    const parsed = Date.parse(value);
    return Number.isNaN(parsed) ? null : parsed;
  }
}
