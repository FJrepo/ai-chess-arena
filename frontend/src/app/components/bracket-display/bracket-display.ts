import {
  AfterViewInit,
  Component,
  ElementRef,
  EventEmitter,
  Input,
  OnDestroy,
  Output,
  QueryList,
  ViewChild,
  ViewChildren,
  computed,
  signal,
} from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { Game } from '../../models/tournament.model';
import { ProviderLogoComponent } from '../provider-logo/provider-logo';

interface ConnectorPath {
  id: string;
  d: string;
}

@Component({
  selector: 'app-bracket-display',
  imports: [MatCardModule, MatButtonModule, MatIconModule, ProviderLogoComponent],
  templateUrl: './bracket-display.html',
  styleUrl: './bracket-display.scss',
})
export class BracketDisplay implements AfterViewInit, OnDestroy {
  @Input() set games(value: Game[]) {
    this._games.set(value);
    this.scheduleConnectorLayout();
  }
  @Output() battle = new EventEmitter<Game>();
  @Output() view = new EventEmitter<Game>();
  @ViewChild('bracketContent') bracketContent?: ElementRef<HTMLElement>;
  @ViewChildren('matchCard', { read: ElementRef }) matchCards?: QueryList<ElementRef<HTMLElement>>;

  private _games = signal<Game[]>([]);
  private layoutScheduled = false;
  private viewInitialized = false;
  private resizeObserver?: ResizeObserver;

  rounds = computed(() => {
    const games = this._games();
    const roundMap = new Map<string, Game[]>();
    for (const game of games) {
      const round = game.bracketRound || 'Unknown';
      if (!roundMap.has(round)) roundMap.set(round, []);
      roundMap.get(round)!.push(game);
    }
    const sorted = [...roundMap.entries()].sort((a, b) => {
      return this.roundSortValue(b[0]) - this.roundSortValue(a[0]);
    });
    return sorted.map(([name, games]) => ({
      name,
      games: games.sort((a, b) => (a.bracketPosition ?? 0) - (b.bracketPosition ?? 0)),
    }));
  });
  connectorPaths = signal<ConnectorPath[]>([]);
  connectorSvgWidth = signal(0);
  connectorSvgHeight = signal(0);

  ngAfterViewInit(): void {
    this.viewInitialized = true;
    this.matchCards?.changes.subscribe(() => this.scheduleConnectorLayout());

    const contentEl = this.bracketContent?.nativeElement;
    if (contentEl) {
      this.resizeObserver = new ResizeObserver(() => this.scheduleConnectorLayout());
      this.resizeObserver.observe(contentEl);
    }

    window.addEventListener('resize', this.onWindowResize, { passive: true });
    this.scheduleConnectorLayout();
  }

  ngOnDestroy(): void {
    this.resizeObserver?.disconnect();
    window.removeEventListener('resize', this.onWindowResize);
  }

  canBattle(game: Game): boolean {
    return (
      game.status === 'WAITING' &&
      !!game.whitePlayerName &&
      !!game.blackPlayerName &&
      game.whitePlayerName !== 'BYE' &&
      game.blackPlayerName !== 'BYE'
    );
  }

  getResultText(game: Game): string {
    if (!game.result) return '';
    switch (game.result) {
      case 'WHITE_WINS':
        return `${game.whitePlayerName} wins`;
      case 'BLACK_WINS':
        return `${game.blackPlayerName} wins`;
      case 'WHITE_FORFEIT':
        return `${game.whitePlayerName} forfeits`;
      case 'BLACK_FORFEIT':
        return `${game.blackPlayerName} forfeits`;
      case 'DRAW':
        return 'Draw';
      default:
        return game.result;
    }
  }

  private onWindowResize = (): void => {
    this.scheduleConnectorLayout();
  };

  private scheduleConnectorLayout(): void {
    if (!this.viewInitialized || this.layoutScheduled) return;
    this.layoutScheduled = true;
    requestAnimationFrame(() => {
      this.layoutScheduled = false;
      this.layoutConnectors();
    });
  }

  private layoutConnectors(): void {
    const content = this.bracketContent?.nativeElement;
    if (!content) return;

    const rounds = this.rounds();
    if (rounds.length < 2) {
      this.connectorPaths.set([]);
      this.connectorSvgWidth.set(0);
      this.connectorSvgHeight.set(0);
      return;
    }

    const cards = this.matchCards?.toArray() ?? [];
    const hostRect = content.getBoundingClientRect();
    const cardRectByGameId = new Map<string, DOMRect>();

    for (const card of cards) {
      const id = card.nativeElement.dataset['gameId'];
      if (id) cardRectByGameId.set(id, card.nativeElement.getBoundingClientRect());
    }

    const paths: ConnectorPath[] = [];
    for (let roundIndex = 0; roundIndex < rounds.length - 1; roundIndex++) {
      const currentRound = rounds[roundIndex].games;
      const nextRound = rounds[roundIndex + 1].games;
      const nextByPosition = new Map<number, Game>();

      for (let i = 0; i < nextRound.length; i++) {
        const nextGame = nextRound[i];
        nextByPosition.set(nextGame.bracketPosition ?? i, nextGame);
      }

      for (let i = 0; i < currentRound.length; i++) {
        const game = currentRound[i];
        const currentPos = game.bracketPosition ?? i;
        const targetGame = nextByPosition.get(Math.floor(currentPos / 2));
        if (!targetGame) continue;

        const fromRect = cardRectByGameId.get(game.id);
        const toRect = cardRectByGameId.get(targetGame.id);
        if (!fromRect || !toRect) continue;

        const fromX = fromRect.right - hostRect.left;
        const fromY = fromRect.top + fromRect.height / 2 - hostRect.top;
        const toX = toRect.left - hostRect.left;
        const toY = toRect.top + toRect.height / 2 - hostRect.top;

        const travel = Math.max(24, (toX - fromX) * 0.45);
        const midX = Math.min(toX - 12, fromX + travel);
        const d = `M ${fromX} ${fromY} L ${midX} ${fromY} L ${midX} ${toY} L ${toX} ${toY}`;
        paths.push({ id: `${game.id}-${targetGame.id}`, d });
      }
    }

    this.connectorSvgWidth.set(Math.ceil(content.scrollWidth));
    this.connectorSvgHeight.set(Math.ceil(content.scrollHeight));
    this.connectorPaths.set(paths);
  }

  private roundSortValue(roundName: string): number {
    switch (roundName) {
      case 'Final':
        return 2;
      case 'Semifinal':
        return 4;
      case 'Quarterfinal':
        return 8;
      default: {
        if (roundName.startsWith('Round of ')) {
          const parsed = Number.parseInt(roundName.slice('Round of '.length), 10);
          return Number.isNaN(parsed) ? -1 : parsed;
        }
        return -1;
      }
    }
  }
}
