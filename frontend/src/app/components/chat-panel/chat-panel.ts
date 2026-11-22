import {
  AfterViewChecked,
  Component,
  ElementRef,
  EventEmitter,
  Input,
  Output,
  ViewChild,
  computed,
  signal,
} from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { BanterLevel, ChatMessage, ChatMode } from '../../models/tournament.model';
import { plyKey } from '../../utils/move-attempts';

let nextTitleId = 0;

/** A chat move link was used: the move-list index, and whether a pointer (not a key) activated it. */
export interface ChatMoveSelection {
  index: number;
  pointer: boolean;
}

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
export class ChatPanel implements AfterViewChecked {
  @Input() set messages(value: ChatMessage[]) {
    this.anchorFocusedLink();
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

  /** Index in the move list of each recorded ply, by `plyKey`; a message's move number links there. */
  @Input() set moveIndexByPly(value: ReadonlyMap<string, number> | null | undefined) {
    this._moveIndexByPly.set(value ?? new Map());
  }
  /** The ply a message was sent with, when its move number is selected. */
  @Output() moveSelected = new EventEmitter<ChatMoveSelection>();
  @ViewChild('scroll') private scroll?: ElementRef<HTMLElement>;
  /** A focused move link and its offset in the transcript before a live update re-rendered it. */
  private anchor: { element: HTMLElement; offset: number } | null = null;
  /** Polite live-region text naming the position a move link just showed. */
  readonly announcement = signal('');

  /** Names the scrollable transcript after the card title. */
  readonly titleId = `chat-panel-title-${nextTitleId++}`;

  readonly _banterLevel = signal<BanterLevel | null>(null);
  readonly _messages = signal<ChatMessage[]>([]);
  readonly _chatMode = signal<ChatMode | null>(null);
  private readonly _moveIndexByPly = signal<ReadonlyMap<string, number>>(new Map());
  /** The move link last focused; it keeps the links' single tab stop. */
  private readonly focusedLink = signal<string | null>(null);

  /**
   * Messages newest first, each with a stable key: the message id, or for legacy messages without
   * one its ply and time, numbered in arrival order when those repeat. Rows keep their DOM node
   * (and focus) when a live message arrives.
   */
  readonly rows = computed(() => {
    const seen = new Map<string, number>();
    const rows = this._messages().map((msg) => {
      const base = msg.id || `${msg.moveNumber}-${msg.senderColor}-${msg.createdAt}`;
      const repeat = seen.get(base) ?? 0;
      seen.set(base, repeat + 1);
      return { msg, key: repeat ? `${base}#${repeat}` : base };
    });
    return rows.reverse();
  });
  /** Key of the one move link in the tab order: the last focused one, else the newest. */
  readonly linkTabStop = computed(() => {
    const linked = this.rows().filter((row) => this.moveIndex(row.msg) !== undefined);
    const focused = this.focusedLink();
    return linked.some((row) => row.key === focused) ? focused : (linked[0]?.key ?? null);
  });
  readonly chatModeLabel = computed(() => {
    const mode = this._chatMode();
    if (!mode) return null;
    const level = this._banterLevel();
    const tone =
      level === 'MIND_GAMES' ? 'Mind games' : level && level[0] + level.slice(1).toLowerCase();
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

  /** The move-list index of the ply the message was sent with; undefined when that ply is not recorded. */
  moveIndex(msg: ChatMessage): number | undefined {
    return this._moveIndexByPly().get(plyKey(msg.moveNumber, msg.senderColor));
  }

  selectMove(index: number, msg: ChatMessage, event: MouseEvent): void {
    this.announcement.set(
      `Showing the board after ${msg.senderColor === 'WHITE' ? 'White' : 'Black'}'s move ${msg.moveNumber}`,
    );
    // Enter or Space on a button fires a click with detail 0; a pointer click counts its presses.
    this.moveSelected.emit({ index, pointer: event.detail > 0 });
  }

  /** Keeps a focused move link where it was when a live message is inserted above it. */
  ngAfterViewChecked(): void {
    const anchor = this.anchor;
    const scroll = this.scroll?.nativeElement;
    this.anchor = null;
    if (!anchor || !scroll || scroll.ownerDocument.activeElement !== anchor.element) return;
    const shift =
      anchor.element.getBoundingClientRect().top -
      scroll.getBoundingClientRect().top -
      anchor.offset;
    if (shift) scroll.scrollTop += shift;
  }

  private anchorFocusedLink(): void {
    const scroll = this.scroll?.nativeElement;
    const active = scroll?.ownerDocument.activeElement;
    if (
      !scroll ||
      !(active instanceof HTMLElement) ||
      active === scroll ||
      !scroll.contains(active)
    ) {
      return;
    }
    this.anchor = {
      element: active,
      offset: active.getBoundingClientRect().top - scroll.getBoundingClientRect().top,
    };
  }

  onLinkFocus(key: string): void {
    this.focusedLink.set(key);
  }

  /** Up/Down (or Left/Right) move between move links, Home/End to the newest/oldest; Enter or Space activates. */
  onLinkKeydown(event: KeyboardEvent): void {
    // Modified arrows are browser shortcuts (Alt+Left is Back); leave them alone.
    if (event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return;
    const link = event.target as HTMLElement;
    const links = Array.from(
      link.closest('.chat-scroll')?.querySelectorAll<HTMLElement>('button.msg-move') ?? [],
    );
    const at = links.indexOf(link);
    const target = {
      ArrowDown: at + 1,
      ArrowRight: at + 1,
      ArrowUp: at - 1,
      ArrowLeft: at - 1,
      Home: 0,
      End: links.length - 1,
    }[event.key];
    if (target === undefined || at < 0) return;
    event.preventDefault();
    links[Math.max(0, Math.min(links.length - 1, target))].focus();
  }

  moveLabel(msg: ChatMessage): string {
    return `Show the board after ${msg.senderColor === 'WHITE' ? 'White' : 'Black'}'s move ${msg.moveNumber}`;
  }

  getPlayerName(msg: ChatMessage): string {
    if (msg.senderColor === 'WHITE') return this.whitePlayerName || 'White';
    return this.blackPlayerName || 'Black';
  }
}
