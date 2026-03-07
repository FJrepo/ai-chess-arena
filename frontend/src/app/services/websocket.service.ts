import { Injectable } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Observable, Subject, filter } from 'rxjs';
import { WsMessage, WsOutgoingMessage } from '../models/tournament.model';

@Injectable({ providedIn: 'root' })
export class WebSocketService {
  private ws: WebSocket | null = null;
  private messages$ = new Subject<WsMessage>();
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
    this.ws = new WebSocket(`${protocol}//${window.location.host}/ws/games`);

    this.ws.onopen = () => {
      this.connectionWarningVisible = false;
      if (this.currentGameId) {
        this.subscribeToGame(this.currentGameId);
      }
    };

    this.ws.onmessage = (event) => {
      try {
        const raw: unknown = JSON.parse(event.data);
        if (isWsMessage(raw)) {
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

    this.ws.onclose = () => {
      this.ws = null;
      if (this.shouldReconnect) {
        if (this.reconnectTimeout) clearTimeout(this.reconnectTimeout);
        this.reconnectTimeout = setTimeout(() => this.connect(), 3000);
      }
    };

    this.ws.onerror = (err) => {
      void err;
      if (!this.connectionWarningVisible) {
        this.connectionWarningVisible = true;
        this.snackBar.open('Live updates were interrupted. Reconnecting...', 'Dismiss', {
          duration: 4000,
        });
      }
    };
  }

  subscribeToGame(gameId: string): void {
    this.currentGameId = gameId;
    this.send({ type: 'subscribe', gameId });
  }

  unsubscribeFromGame(gameId: string): void {
    this.send({ type: 'unsubscribe', gameId });
    this.currentGameId = null;
  }

  getMessages(gameId: string): Observable<WsMessage> {
    return this.messages$.pipe(filter((msg) => msg.gameId === gameId || !msg.gameId));
  }

  private send(data: WsOutgoingMessage): void {
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
    this.ws?.close();
    this.ws = null;
  }
}

function isWsMessage(message: unknown): message is WsMessage {
  if (!message || typeof message !== 'object') {
    return false;
  }

  const candidate = message as { type?: unknown; gameId?: unknown };
  if (typeof candidate.type !== 'string' || typeof candidate.gameId !== 'string') {
    return false;
  }

  return (
    candidate.type === 'move' ||
    candidate.type === 'chat' ||
    candidate.type === 'gameStatus' ||
    candidate.type === 'retry' ||
    candidate.type === 'forfeit' ||
    candidate.type === 'evaluationUpdate'
  );
}
