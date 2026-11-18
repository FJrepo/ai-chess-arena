import { Injectable } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Observable, Subject, filter } from 'rxjs';
import { WsMessage, WsOutgoingMessage } from '../models/tournament.model';

@Injectable({ providedIn: 'root' })
export class WebSocketService {
  private ws: WebSocket | null = null;
  private messages$ = new Subject<WsMessage>();
  private readonly liveGames$ = new Subject<void>();
  private watchingLiveGames = false;
  private reconnected$ = new Subject<void>();
  private hasConnectedBefore = false;
  private revisionProtocol = false;
  private awaitingWatermark = false;
  private negotiationTimeout?: ReturnType<typeof setTimeout>;
  private heartbeatTimer?: ReturnType<typeof setInterval>;
  private currentGameId: string | null = null;
  private shouldReconnect = true;
  private reconnectTimeout: ReturnType<typeof setTimeout> | null = null;
  private connectionWarningVisible = false;

  constructor(private snackBar: MatSnackBar) {}

  connect(): void {
    if (
      this.ws &&
      (this.ws.readyState === WebSocket.OPEN || this.ws.readyState === WebSocket.CONNECTING)
    )
      return;

    this.shouldReconnect = true;
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    // Use same-origin websocket endpoint and let dev/prod proxies handle backend routing.
    const socket = new WebSocket(`${protocol}//${window.location.host}/ws/games`);
    this.ws = socket;

    socket.onopen = () => {
      if (this.ws !== socket) return;
      this.connectionWarningVisible = false;
      if (this.watchingLiveGames) this.send({ type: 'watchLiveGames' });
      if (this.currentGameId) {
        this.sendSubscription(this.currentGameId);
      }
      // Updates sent while disconnected (e.g. a backend restart pausing games) were missed.
      if (this.hasConnectedBefore) {
        this.reconnected$.next();
      }
      this.hasConnectedBefore = true;
    };

    socket.onmessage = (event) => {
      if (this.ws !== socket) return;
      try {
        const raw: unknown = JSON.parse(event.data);
        if (raw && typeof raw === 'object' && 'type' in raw && raw.type === 'liveGamesChanged') {
          this.liveGames$.next();
          return;
        }
        if (isWsMessage(raw)) {
          if (
            raw.gameId === this.currentGameId &&
            (raw.type === 'subscribed' || raw.type === 'heartbeat')
          ) {
            clearTimeout(this.negotiationTimeout);
            this.awaitingWatermark = false;
          }
          this.messages$.next(raw);
        } else {
          this.snackBar.open('Ignored an invalid live update payload.', 'Dismiss', {
            duration: 4000,
          });
        }
      } catch {
        this.snackBar.open('Ignored an unreadable live update payload.', 'Dismiss', {
          duration: 4000,
        });
      }
    };

    socket.onclose = () => {
      if (this.ws !== socket) return;
      this.clearSubscriptionTimers();
      this.ws = null;
      if (this.revisionProtocol && this.currentGameId) {
        this.messages$.next({ type: 'subscriptionUnavailable', gameId: this.currentGameId });
      }
      if (this.shouldReconnect) {
        if (this.reconnectTimeout) clearTimeout(this.reconnectTimeout);
        this.reconnectTimeout = setTimeout(() => this.connect(), 3000);
      }
    };

    socket.onerror = () => {
      if (this.ws !== socket) return;
      if (this.currentGameId && !this.connectionWarningVisible) {
        this.connectionWarningVisible = true;
        this.snackBar.open('Live updates were interrupted. Reconnecting...', 'Dismiss', {
          duration: 4000,
        });
      }
    };
  }

  subscribeToGame(gameId: string, revisionProtocol = false): void {
    this.currentGameId = gameId;
    this.revisionProtocol = revisionProtocol;
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.sendSubscription(gameId);
    } else if (revisionProtocol) {
      this.awaitWatermark(gameId);
    }
  }

  private sendSubscription(gameId: string): void {
    this.clearSubscriptionTimers();
    this.send({ type: 'subscribe', gameId, ...(this.revisionProtocol ? { protocol: 2 } : {}) });
    if (!this.revisionProtocol) return;
    this.awaitWatermark(gameId);
    this.heartbeatTimer = setInterval(() => {
      if (!this.awaitingWatermark) {
        this.awaitWatermark(gameId);
        this.send({ type: 'heartbeat', gameId });
      }
    }, 15000);
  }

  private awaitWatermark(gameId: string): void {
    clearTimeout(this.negotiationTimeout);
    this.awaitingWatermark = true;
    this.negotiationTimeout = setTimeout(() => {
      this.awaitingWatermark = false;
      this.messages$.next({ type: 'subscriptionUnavailable', gameId });
    }, 2500);
  }

  private clearSubscriptionTimers(): void {
    clearTimeout(this.negotiationTimeout);
    clearInterval(this.heartbeatTimer);
    this.awaitingWatermark = false;
  }

  unsubscribeFromGame(gameId: string): void {
    this.send({ type: 'unsubscribe', gameId });
    if (this.currentGameId !== gameId) return;
    this.clearSubscriptionTimers();
    this.currentGameId = null;
  }

  /** Emits after the live connection was re-established; views should reload their state. */
  reconnected(): Observable<void> {
    return this.reconnected$.asObservable();
  }

  getMessages(gameId: string): Observable<WsMessage> {
    return this.messages$.pipe(filter((msg) => msg.gameId === gameId || !msg.gameId));
  }

  watchLiveGames(): void {
    if (this.watchingLiveGames) return;
    this.watchingLiveGames = true;
    this.send({ type: 'watchLiveGames' });
    this.connect();
  }

  liveGamesChanges(): Observable<void> {
    return this.liveGames$.asObservable();
  }

  private send(data: WsOutgoingMessage | { type: 'watchLiveGames' }): void {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(data));
    }
  }

  disconnect(): void {
    this.shouldReconnect = false;
    if (this.reconnectTimeout) {
      clearTimeout(this.reconnectTimeout);
      this.reconnectTimeout = null;
    }
    if (this.currentGameId) {
      this.unsubscribeFromGame(this.currentGameId);
    }
    const socket = this.ws;
    this.ws = null;
    socket?.close();
  }
}

function isWsMessage(message: unknown): message is WsMessage {
  if (!message || typeof message !== 'object') {
    return false;
  }

  const candidate = message as {
    type?: unknown;
    gameId?: unknown;
    revision?: unknown;
    changes?: unknown;
    streamId?: unknown;
  };
  if (typeof candidate.type !== 'string' || typeof candidate.gameId !== 'string') {
    return false;
  }

  if (
    candidate.type === 'subscribed' ||
    candidate.type === 'heartbeat' ||
    candidate.type === 'gameRevision'
  ) {
    return (
      typeof candidate.revision === 'number' &&
      Number.isSafeInteger(candidate.revision) &&
      candidate.revision >= 0 &&
      (candidate.streamId === undefined || typeof candidate.streamId === 'string') &&
      (candidate.type !== 'gameRevision' || isChangeList(candidate.changes, candidate.gameId))
    );
  }

  return (
    candidate.type === 'move' ||
    candidate.type === 'chat' ||
    candidate.type === 'gameStatus' ||
    candidate.type === 'retry' ||
    candidate.type === 'forfeit' ||
    candidate.type === 'attemptsUpdated' ||
    candidate.type === 'evaluationUpdate'
  );
}

function isChangeList(changes: unknown, gameId: string): boolean {
  if (!Array.isArray(changes) || changes.at(-1)?.type !== 'gameState') return false;
  return changes.every((value) => {
    if (!value || typeof value !== 'object' || value.gameId !== gameId) return false;
    switch (value.type) {
      case 'committedMove':
        return typeof value.move?.fen === 'string' && typeof value.move?.id === 'string';
      case 'committedChat':
        return typeof value.chat?.id === 'string';
      case 'committedAttempt':
        return typeof value.attempt?.id === 'string';
      case 'gameState':
        return typeof value.totalCostUsd === 'number';
      case 'gameDetailsChanged':
      case 'gameStatus':
      case 'evaluationUpdate':
        return true;
      default:
        return false;
    }
  });
}
