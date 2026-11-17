import { Component, Input, computed, signal } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { BanterLevel, ChatMessage, ChatMode } from '../../models/tournament.model';

const CHAT_MODE_LABELS: Record<ChatMode, string> = {
  OFF: 'Chat off',
  OPTIONAL: 'Chat optional',
  MANDATORY: 'Chat mandatory',
};

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
  /** The tournament's chat mode; null while unknown or for a game outside a tournament. */
  @Input() set chatMode(value: ChatMode | null | undefined) {
    this._chatMode.set(value ?? null);
  }

  @Input() set banterLevel(value: BanterLevel | null | undefined) {
    this._banterLevel.set(value ?? null);
  }

  readonly _banterLevel = signal<BanterLevel | null>(null);
  readonly _messages = signal<ChatMessage[]>([]);
  readonly _chatMode = signal<ChatMode | null>(null);
  readonly displayedMessages = computed(() => [...this._messages()].reverse());
  readonly chatModeLabel = computed(() => {
    const mode = this._chatMode();
    if (!mode) return null;
    const level = this._banterLevel();
    const tone = level ? level[0] + level.slice(1).toLowerCase() : null;
    return tone && mode !== 'OFF' ? `${CHAT_MODE_LABELS[mode]} · ${tone}` : CHAT_MODE_LABELS[mode];
  });
  readonly emptyText = computed(() => {
    switch (this._chatMode()) {
      case 'OFF':
        return 'Chat is off in this tournament: the models were asked to stay silent.';
      case 'MANDATORY':
        return 'No messages yet. The models are asked for a short message with every move.';
      default:
        return 'No messages yet. The trash talk will appear here.';
    }
  });

  getPlayerName(msg: ChatMessage): string {
    if (msg.senderColor === 'WHITE') return this.whitePlayerName || 'White';
    return this.blackPlayerName || 'Black';
  }
}
