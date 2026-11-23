import {
  Component,
  Input,
  Output,
  EventEmitter,
  signal,
  computed,
  ElementRef,
  ViewChild,
  AfterViewChecked,
  AfterViewInit,
  OnChanges,
  OnDestroy,
  SimpleChanges,
} from '@angular/core';
import { Move } from '../../models/tournament.model';
import {
  QualityBadge,
  qualityBadge,
  qualityTooltip,
  missedMateTooltip,
} from '../../utils/move-quality';

interface MovePair {
  number: number;
  white: Move | null;
  black: Move | null;
  whiteIdx: number;
  blackIdx: number;
}

@Component({
  selector: 'app-move-list',
  host: { '[class.compact]': 'compact' },
  templateUrl: './move-list.html',
  styleUrl: './move-list.scss',
})
export class MoveListComponent implements AfterViewInit, AfterViewChecked, OnChanges, OnDestroy {
  @Input() set moves(value: Move[]) {
    this._moves.set(value);
  }
  @Input() currentIndex = -1;
  @Input() compact = false;
  @Output() moveSelected = new EventEmitter<number>();
  @ViewChild('scrollContainer') scrollContainer?: ElementRef<HTMLElement>;

  private _moves = signal<Move[]>([]);
  private shouldScroll = false;
  /** The move holding keyboard focus; null while focus is outside the list. */
  focusedIndex: number | null = null;
  /** The list follows the shown move (across resizes) until the user scrolls it. */
  private following = false;
  private revealedTop: number | null = null;
  private resizeObserver?: ResizeObserver;

  movePairs = computed<MovePair[]>(() => {
    const moves = this._moves();
    const pairs: MovePair[] = [];

    for (let i = 0; i < moves.length; i += 2) {
      pairs.push({
        number: moves[i].moveNumber,
        white: moves[i] || null,
        black: moves[i + 1] || null,
        whiteIdx: i,
        blackIdx: i + 1,
      });
    }
    return pairs;
  });

  ngOnChanges(changes: SimpleChanges<MoveListComponent>) {
    if (changes.currentIndex) this.revealSelectedMove();
  }

  revealSelectedMove() {
    this.shouldScroll = this.currentIndex >= 0;
  }

  ngAfterViewInit() {
    const list = this.scrollContainer?.nativeElement;
    if (!list || typeof ResizeObserver === 'undefined') return;
    // The column can settle after the first reveal (fonts, board size): keep the shown move in view.
    this.resizeObserver = new ResizeObserver(() => {
      if (this.following) this.reveal(list);
    });
    this.resizeObserver.observe(list);
  }

  ngOnDestroy() {
    this.resizeObserver?.disconnect();
  }

  ngAfterViewChecked() {
    if (!this.shouldScroll || !this.scrollContainer) return;
    if (this.reveal(this.scrollContainer.nativeElement)) this.shouldScroll = false;
  }

  /** A scroll the list did not make itself is the user's: stop following the shown move. */
  onScroll() {
    const list = this.scrollContainer?.nativeElement;
    if (list && list.scrollTop !== this.revealedTop) this.following = false;
  }

  /**
   * Scrolls the shown move into view, clear of the edges by the list's scroll padding. Returns false
   * while the move is not rendered yet.
   */
  private reveal(list: HTMLElement): boolean {
    // A keyboard user is working in the list: a live ply must not scroll their focus out of sight.
    if (list.contains(list.ownerDocument.activeElement)) {
      this.following = false;
      return true;
    }
    const selected = list.querySelector<HTMLElement>('.move.active');
    if (!selected) return false;
    const style = getComputedStyle(list);
    const padTop = parseFloat(style.scrollPaddingTop) || 0;
    const padBottom = parseFloat(style.scrollPaddingBottom) || 0;
    const viewport = list.getBoundingClientRect();
    const move = selected.getBoundingClientRect();
    if (move.top < viewport.top + padTop) {
      list.scrollTop += move.top - viewport.top - padTop;
    } else if (move.bottom > viewport.bottom - padBottom) {
      list.scrollTop += move.bottom - viewport.bottom + padBottom;
    }
    this.revealedTop = list.scrollTop;
    this.following = true;
    return true;
  }

  /**
   * Roving tab stop: the focused move while focus is in the list, else the move shown on the board
   * (the first move before any is shown).
   */
  tabIndexFor(index: number): 0 | -1 {
    const count = this._moves().length;
    const shown = this.currentIndex >= 0 && this.currentIndex < count ? this.currentIndex : 0;
    return index === (this.focusedIndex ?? shown) ? 0 : -1;
  }

  /** Left/Right step one ply, Up/Down one move (same column), Home/End to the first/last ply. */
  onKeydown(event: KeyboardEvent) {
    // Modified arrows are browser shortcuts (Alt+Left is Back); leave them alone.
    if (event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return;
    const button = (event.target as HTMLElement).closest<HTMLElement>('.move');
    const last = this._moves().length - 1;
    if (!button || last < 0) return;
    const at = Number(button.dataset['ply']);
    const target = {
      ArrowRight: at + 1,
      ArrowLeft: at - 1,
      ArrowDown: at + 2,
      ArrowUp: at - 2,
      Home: 0,
      End: last,
    }[event.key];
    if (target === undefined) return;
    event.preventDefault();
    // Up/Down stay in their column: past the first or last row they keep focus where it is.
    if ((event.key === 'ArrowUp' || event.key === 'ArrowDown') && (target < 0 || target > last)) {
      return;
    }
    const next = Math.max(0, Math.min(last, target));
    this.scrollContainer?.nativeElement
      .querySelector<HTMLElement>(`.move[data-ply="${next}"]`)
      ?.focus();
  }

  onFocusOut(event: FocusEvent) {
    const list = this.scrollContainer?.nativeElement;
    if (!list?.contains(event.relatedTarget as Node | null)) this.focusedIndex = null;
  }

  onMoveClick(index: number) {
    this.moveSelected.emit(index);
  }

  /** The move's ?! / ? / ?? annotation, if it was judged an error. */
  badge(move: Move): QualityBadge | null {
    return move.isBook ? null : qualityBadge(move.quality);
  }

  /** "Blunder: winning chances 72% → 18% (−3.2)", or a note for forced and overridden moves. */
  tooltip(move: Move): string | null {
    return move.isBook ? 'Book move: excluded from model metrics' : qualityTooltip(move.quality);
  }

  missedMate(move: Move): string | null {
    return move.isBook ? null : missedMateTooltip(move.quality);
  }

  /** Accessible name: notation as on a scoresheet ("12... cxd4") plus any annotations. */
  moveLabel(move: Move): string {
    const parts = [`${move.moveNumber}${move.color === 'WHITE' ? '.' : '...'} ${move.san}`];
    if (move.isBook) parts.push('book move');
    const badge = this.badge(move);
    if (badge) parts.push(badge.label.toLowerCase());
    const missedMate = this.missedMate(move);
    if (missedMate) parts.push(missedMate.toLowerCase());
    if (move.retryCount > 0) {
      parts.push(`${move.retryCount} ${move.retryCount === 1 ? 'retry' : 'retries'}`);
    }
    return parts.join(', ');
  }

  formatTime(ms: number | null): string {
    if (!ms) return '';
    return (ms / 1000).toFixed(1) + 's';
  }
}
