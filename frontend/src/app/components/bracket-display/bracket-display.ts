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
import { gameStatusLabel } from '../../utils/status-labels';

interface ConnectorPath {
  id: string;
  d: string;
}

interface SeriesCard {
  id: string;
  roundName: string;
  bracketPosition: number;
  bestOf: number;
  /**
   * The game being played or up next; once the series is over, its last game.
   * Drives the start/ready state and the colours shown on the card.
   */
  currentGame: Game;
  /** The most recent finished (completed or forfeited) game, used for review and result text. */
  lastFinishedGame: Game | null;
  whitePlayerName: string | null;
  whiteModelId: string | null;
  blackPlayerName: string | null;
  blackModelId: string | null;
  whiteWins: number;
  blackWins: number;
  /** Who advanced from this series, as recorded by the backend (covers draw-policy decisions). */
  advancedName: string | null;
  isComplete: boolean;
  stateLabel: string;
  stateTone: 'live' | 'paused' | 'ready' | 'pending' | 'complete' | 'forfeit' | 'bye';
  detailLabel: string;
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
  /** Champion of a completed tournament; shown as the bracket's closing element. */
  @Input() champion: string | null = null;
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
    const roundMap = new Map<string, SeriesCard[]>();
    const seriesMap = new Map<string, Game[]>();

    for (const game of games) {
      const seriesKey = game.seriesId || game.id;
      if (!seriesMap.has(seriesKey)) {
        seriesMap.set(seriesKey, []);
      }
      seriesMap.get(seriesKey)!.push(game);
    }

    for (const seriesGames of seriesMap.values()) {
      const card = this.toSeriesCard(seriesGames);
      if (!roundMap.has(card.roundName)) {
        roundMap.set(card.roundName, []);
      }
      roundMap.get(card.roundName)!.push(card);
    }

    const sorted = [...roundMap.entries()].sort((a, b) => {
      return this.roundSortValue(b[0]) - this.roundSortValue(a[0]);
    });
    return sorted.map(([name, games]) => ({
      name,
      games: games.sort((a, b) => a.bracketPosition - b.bracketPosition),
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

  canBattle(card: SeriesCard): boolean {
    const game = card.currentGame;
    return (
      game.status === 'WAITING' &&
      !!game.whitePlayerName &&
      !!game.blackPlayerName &&
      game.whitePlayerName !== 'BYE' &&
      game.blackPlayerName !== 'BYE'
    );
  }

  /** Whether this side won the series (uses the backend's advancement record when available). */
  isSeriesWinner(card: SeriesCard, side: 'white' | 'black'): boolean {
    if (!card.isComplete) return false;
    const name = side === 'white' ? card.whitePlayerName : card.blackPlayerName;
    if (card.advancedName) return card.advancedName === name;
    return side === 'white' ? card.whiteWins > card.blackWins : card.blackWins > card.whiteWins;
  }

  isSeriesLoser(card: SeriesCard, side: 'white' | 'black'): boolean {
    return card.isComplete && this.isSeriesWinner(card, side === 'white' ? 'black' : 'white');
  }

  /**
   * Who advanced (or, in the Final, won the tournament) and the series score. The score is joined
   * with a non-breaking hyphen, and "(x-y)" is kept on the line of "draw policy", so it never wraps.
   */
  private advancedText(card: SeriesCard, name: string): string {
    const high = Math.max(card.whiteWins, card.blackWins);
    const low = Math.min(card.whiteWins, card.blackWins);
    const verb = card.roundName === 'Final' ? 'wins the tournament' : 'advances';
    const score = `${high}\u2011${low}`;
    return card.whiteWins === card.blackWins
      ? `${name} ${verb} on the draw policy\u00a0(${score})`
      : `${name} ${verb} ${score}`;
  }

  /** Series wins of the participant with this name (wins are attributed by name, not by colour). */
  private winsOf(card: SeriesCard, name: string | null): number {
    if (!name) return 0;
    if (name === card.whitePlayerName) return card.whiteWins;
    if (name === card.blackPlayerName) return card.blackWins;
    return 0;
  }

  getResultText(card: SeriesCard): string {
    if (card.advancedName && card.isComplete) {
      return this.advancedText(card, card.advancedName);
    }
    const game = card.lastFinishedGame;
    if (!game?.result) return '';
    const white = game.whitePlayerName;
    const black = game.blackPlayerName;
    switch (game.result) {
      case 'WHITE_WINS':
        return `${white} wins ${this.winsOf(card, white)}-${this.winsOf(card, black)}`;
      case 'BLACK_WINS':
        return `${black} wins ${this.winsOf(card, black)}-${this.winsOf(card, white)}`;
      case 'WHITE_FORFEIT':
        return `${white} forfeits`;
      case 'BLACK_FORFEIT':
        return `${black} forfeits`;
      case 'DRAW':
        return 'Draw';
      default:
        return game.resultReason || '';
    }
  }

  hasReviewableGame(card: SeriesCard): boolean {
    return card.lastFinishedGame !== null;
  }

  /** Opens the most recent finished game of the series for review. */
  review(card: SeriesCard): void {
    if (card.lastFinishedGame) this.view.emit(card.lastFinishedGame);
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
      const nextByPosition = new Map<number, SeriesCard>();

      for (let i = 0; i < nextRound.length; i++) {
        const nextGame = nextRound[i];
        nextByPosition.set(nextGame.bracketPosition, nextGame);
      }

      for (let i = 0; i < currentRound.length; i++) {
        const game = currentRound[i];
        const currentPos = game.bracketPosition;
        const targetGame = nextByPosition.get(Math.floor(currentPos / 2));
        if (!targetGame) continue;

        const fromRect = cardRectByGameId.get(game.id);
        const toRect = cardRectByGameId.get(targetGame.id);
        if (!fromRect || !toRect) continue;

        const fromX = fromRect.right - hostRect.left;
        const fromY = fromRect.top + fromRect.height / 2 - hostRect.top;
        const toX = toRect.left - hostRect.left;
        const toY = toRect.top + toRect.height / 2 - hostRect.top;

        const midX = fromX + (toX - fromX) / 2;
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

  private toSeriesCard(seriesGames: Game[]): SeriesCard {
    const orderedGames = [...seriesGames].sort((left, right) => {
      if (left.seriesGameNumber !== right.seriesGameNumber) {
        return left.seriesGameNumber - right.seriesGameNumber;
      }
      return left.createdAt.localeCompare(right.createdAt);
    });
    const firstGame = orderedGames[0];
    const lastGame = orderedGames[orderedGames.length - 1];
    const activeGame = orderedGames.find(
      (game) =>
        game.status === 'WAITING' || game.status === 'IN_PROGRESS' || game.status === 'PAUSED',
    );
    const currentGame = activeGame ?? lastGame;
    const lastFinishedGame =
      [...orderedGames]
        .reverse()
        .find((game) => game.status === 'COMPLETED' || game.status === 'FORFEIT') ?? null;

    // The card shows the current game's colours; wins are counted per participant name, so a
    // later game that swaps colours still credits the right player.
    const whiteName = currentGame.whitePlayerName;
    const blackName = currentGame.blackPlayerName;
    let whiteWins = 0;
    let blackWins = 0;
    for (const game of orderedGames) {
      const winnerName =
        game.result === 'WHITE_WINS' || game.result === 'BLACK_FORFEIT'
          ? game.whitePlayerName
          : game.result === 'BLACK_WINS' || game.result === 'WHITE_FORFEIT'
            ? game.blackPlayerName
            : null;
      if (!winnerName) continue;
      if (winnerName === whiteName) {
        whiteWins += 1;
      } else if (winnerName === blackName) {
        blackWins += 1;
      }
    }

    const advancedName =
      orderedGames.map((game) => game.advancedPlayerName).find((name) => !!name) ?? null;
    const isComplete =
      !activeGame && (lastGame.status === 'COMPLETED' || lastGame.status === 'FORFEIT');
    const hasBye = firstGame.whitePlayerName === 'BYE' || firstGame.blackPlayerName === 'BYE';

    const card: SeriesCard = {
      id: firstGame.seriesId || firstGame.id,
      roundName: firstGame.bracketRound || 'Unknown',
      bracketPosition: firstGame.bracketPosition ?? 0,
      bestOf: firstGame.seriesBestOf,
      currentGame,
      lastFinishedGame,
      whitePlayerName: whiteName,
      whiteModelId: currentGame.whiteModelId,
      blackPlayerName: blackName,
      blackModelId: currentGame.blackModelId,
      whiteWins,
      blackWins,
      advancedName,
      isComplete,
      stateLabel: '',
      stateTone: 'pending',
      detailLabel: '',
    };
    return { ...card, ...this.describeSeriesState(card, hasBye) };
  }

  private describeSeriesState(
    card: SeriesCard,
    hasBye: boolean,
  ): Pick<SeriesCard, 'stateLabel' | 'stateTone' | 'detailLabel'> {
    if (hasBye) {
      return {
        stateLabel: 'Bye',
        stateTone: 'bye',
        detailLabel: 'Auto-advanced without a played game.',
      };
    }

    const currentGame = card.currentGame;
    const sideToMove = this.sideToMoveLabel(currentGame.currentFen);
    switch (currentGame.status) {
      case 'IN_PROGRESS':
        return {
          stateLabel: gameStatusLabel(currentGame.status),
          stateTone: 'live',
          detailLabel: sideToMove
            ? `${sideToMove} to move in Game ${currentGame.seriesGameNumber}.`
            : `Game ${currentGame.seriesGameNumber} is live.`,
        };
      case 'PAUSED':
        return {
          stateLabel: gameStatusLabel(currentGame.status),
          stateTone: 'paused',
          detailLabel: sideToMove
            ? `${sideToMove} to move when resumed.`
            : `Game ${currentGame.seriesGameNumber} is paused.`,
        };
      case 'WAITING': {
        const bothPlayersSet = !!currentGame.whitePlayerName && !!currentGame.blackPlayerName;
        let detailLabel: string;
        if (!bothPlayersSet) {
          detailLabel = 'Waiting for the previous round.';
        } else if (card.lastFinishedGame) {
          detailLabel = `Game ${currentGame.seriesGameNumber} is next. Review the previous result below.`;
        } else {
          detailLabel = `Game ${currentGame.seriesGameNumber} can start when you're ready.`;
        }
        return {
          stateLabel: bothPlayersSet ? 'Ready' : 'Pending',
          stateTone: bothPlayersSet ? 'ready' : 'pending',
          detailLabel,
        };
      }
      case 'FORFEIT':
        return {
          stateLabel: gameStatusLabel(currentGame.status),
          stateTone: 'forfeit',
          detailLabel: currentGame.resultReason || 'Series ended by forfeit.',
        };
      case 'COMPLETED':
      default:
        return {
          stateLabel: gameStatusLabel('COMPLETED'),
          stateTone: 'complete',
          detailLabel: this.getResultText(card) || 'Series complete.',
        };
    }
  }

  private sideToMoveLabel(fen: string | null | undefined): string | null {
    if (!fen) {
      return null;
    }
    const side = fen.split(' ')[1];
    if (side === 'w') {
      return 'White';
    }
    if (side === 'b') {
      return 'Black';
    }
    return null;
  }
}
