import { Injectable } from '@angular/core';
import { Observable, Subject, filter } from 'rxjs';
import { WsMessage } from '../models/tournament.model';

@Injectable({ providedIn: 'root' })
export class WebSocketService {
  private ws: WebSocket | null = null;
  private messages$ = new Subject<WsMessage>();
  private currentGameId: string | null = null;
  private shouldReconnect = true;
  private reconnectTimeout: ReturnType<typeof setTimeout> | null = null;

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
      if (this.currentGameId) {
        this.subscribeToGame(this.currentGameId);
      }
    };

    this.ws.onmessage = (event) => {
      try {
        const msg: WsMessage = JSON.parse(event.data);
        this.messages$.next(msg);
      } catch (e) {
        console.error('Failed to parse WebSocket message', e);
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
      console.error('WebSocket error', err);
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

  private send(data: unknown): void {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(data));
    } else {
      const messageType =
        typeof data === 'object' && data !== null && 'type' in data
          ? String((data as { type?: unknown }).type)
          : 'unknown';
      console.warn('WebSocket not open, message not sent:', messageType);
      // We don't retry here to avoid loops, connect() will re-subscribe on open
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
