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
} from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { Move } from '../../models/tournament.model';
import { QualityBadge, qualityBadge, qualityTooltip } from '../../utils/move-quality';

interface MovePair {
  number: number;
  white: Move | null;
  black: Move | null;
  whiteIdx: number;
  blackIdx: number;
}

@Component({
  selector: 'app-move-list',
  imports: [MatCardModule],
  templateUrl: './move-list.html',
  styleUrl: './move-list.scss',
})
export class MoveListComponent implements AfterViewChecked {
  @Input() set moves(value: Move[]) {
    this._moves.set(value);
  }
  @Input() currentIndex = -1;
  @Input() compact = false;
  @Output() moveSelected = new EventEmitter<number>();
  @ViewChild('scrollContainer') scrollContainer?: ElementRef;

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

  ngAfterViewChecked() {
    if (this.shouldScroll && this.scrollContainer) {
      const el = this.scrollContainer.nativeElement;
      el.scrollTop = el.scrollHeight;
      this.shouldScroll = false;
    }
  }

  onMoveClick(index: number) {
    this.moveSelected.emit(index);
  }

  /** The move's ?! / ? / ?? annotation, if it was judged an error. */
  badge(move: Move): QualityBadge | null {
    return qualityBadge(move.quality);
  }

  /** "Blunder: winning chances 72% → 18% (−3.2)", or a note for forced and overridden moves. */
  tooltip(move: Move): string | null {
    return qualityTooltip(move.quality);
  }

  formatTime(ms: number | null): string {
    if (!ms) return '';
    return (ms / 1000).toFixed(1) + 's';
  }
}
