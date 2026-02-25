import { Component, Input, computed, signal } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { ChatMessage } from '../../models/tournament.model';

@Component({
  selector: 'app-chat-panel',
  imports: [MatCardModule],
  templateUrl: './chat-panel.html',
  styleUrl: './chat-panel.scss',
})
export class ChatPanel {
  @Input() set messages(value: ChatMessage[]) {
    this._messages.set(value);
  }
  @Input() whitePlayerName: string | null = null;
  @Input() blackPlayerName: string | null = null;

  readonly _messages = signal<ChatMessage[]>([]);
  readonly displayedMessages = computed(() => [...this._messages()].reverse());

  getPlayerName(msg: ChatMessage): string {
    if (msg.senderColor === 'WHITE') return this.whitePlayerName || 'White';
    return this.blackPlayerName || 'Black';
  }
}
