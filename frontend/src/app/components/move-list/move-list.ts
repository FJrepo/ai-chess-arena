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
  OnChanges,
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
export class MoveListComponent implements AfterViewChecked, OnChanges {
  @Input() set moves(value: Move[]) {
    this._moves.set(value);
  }
  @Input() currentIndex = -1;
  @Input() compact = false;
  @Output() moveSelected = new EventEmitter<number>();
  @ViewChild('scrollContainer') scrollContainer?: ElementRef<HTMLElement>;

  private _moves = signal<Move[]>([]);
  private shouldScroll = false;

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

  ngAfterViewChecked() {
    if (!this.shouldScroll || !this.scrollContainer) return;
    const list = this.scrollContainer.nativeElement;
    const selected = list.querySelector<HTMLElement>('.move.active');
    if (!selected) return;
    this.shouldScroll = false;

    const viewport = list.getBoundingClientRect();
    const move = selected.getBoundingClientRect();
    if (move.top < viewport.top) {
      list.scrollTop += move.top - viewport.top;
    } else if (move.bottom > viewport.bottom) {
      list.scrollTop += move.bottom - viewport.bottom;
    }
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
