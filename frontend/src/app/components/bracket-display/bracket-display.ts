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
import { Game, RunCapacity } from '../../models/tournament.model';
import { ProviderLogoComponent } from '../provider-logo/provider-logo';
import { QueuedResume } from '../queued-resume/queued-resume';
import { gameStatusLabel } from '../../utils/status-labels';

interface ConnectorPath {
  id: string;
  d: string;
}

/** One side of a game: the bracket participant id when known, otherwise just the name. */
interface Participant {
  /** Null for a side without a participant; undefined in data from older backends. */
  id: string | null | undefined;
  name: string | null;
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
  whiteParticipantId: string | null | undefined;
  whiteModelId: string | null;
  blackPlayerName: string | null;
  blackParticipantId: string | null | undefined;
  blackModelId: string | null;
  /** Wins in the regular games (1..bestOf) only, like the backend's series score. */
  whiteWins: number;
  blackWins: number;
  /** Winner of the first decisive sudden-death game played after the regular games, if any. */
  tiebreakWinner: Participant | null;
  /** Who advanced from this series, as recorded by the backend (covers draw-policy decisions). */
  advancedName: string | null;
  advancedParticipantId: string | null | undefined;
  isComplete: boolean;
  stateLabel: string;
  stateTone: 'live' | 'paused' | 'ready' | 'pending' | 'complete' | 'forfeit' | 'bye' | 'cancelled';
  detailLabel: string;
}

const BYE_PLAYER_NAME = 'BYE';

function whiteOf(game: Game): Participant {
  return { id: game.whiteParticipantId, name: game.whitePlayerName };
}

function blackOf(game: Game): Participant {
  return { id: game.blackParticipantId, name: game.blackPlayerName };
}

/**
 * Same participant: by id when both carry one, so two participants with the same name are never
 * mixed up. Names are only compared when an id is missing (older backends) or neither side has a
 * participant; a side without a participant never matches one with a participant.
 */
function sameParticipant(a: Participant | null, b: Participant | null): boolean {
  if (!a || !b) return false;
  if (a.id && b.id) return a.id === b.id;
  const idsMissing = a.id === undefined || b.id === undefined || (!a.id && !b.id);
  return idsMissing && !!a.name && a.name === b.name;
}

/**
 * A side without a participant. As in the backend, the name is only a fallback when there is no
 * participant id: a real participant named "BYE" still plays.
 */
function isEmptySide(side: Participant): boolean {
  if (side.id) return false;
  return !side.name || !side.name.trim() || side.name === BYE_PLAYER_NAME;
}

function gameWinner(game: Game): Participant | null {
  switch (game.result) {
    case 'WHITE_WINS':
    case 'BLACK_FORFEIT':
      return whiteOf(game);
    case 'BLACK_WINS':
    case 'WHITE_FORFEIT':
      return blackOf(game);
    default:
      return null;
  }
}

function isFinished(game: Game): boolean {
  return game.status === 'COMPLETED' || game.status === 'FORFEIT';
}

@Component({
  selector: 'app-bracket-display',
  imports: [MatCardModule, MatButtonModule, MatIconModule, ProviderLogoComponent, QueuedResume],
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
  @Input() runCapacity: RunCapacity | null = null;
  @Output() battle = new EventEmitter<Game>();
  @Output() view = new EventEmitter<Game>();
  @Output() cancelResume = new EventEmitter<Game>();
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
    return game.status === 'WAITING' && !isEmptySide(whiteOf(game)) && !isEmptySide(blackOf(game));
  }

  /** Whether this side won the series (uses the backend's advancement record when available). */
  isSeriesWinner(card: SeriesCard, side: 'white' | 'black'): boolean {
    if (!card.isComplete) return false;
    const advanced = this.advancedOf(card);
    if (advanced) return sameParticipant(advanced, this.sideOf(card, side));
    return side === 'white' ? card.whiteWins > card.blackWins : card.blackWins > card.whiteWins;
  }

  isSeriesLoser(card: SeriesCard, side: 'white' | 'black'): boolean {
    return card.isComplete && this.isSeriesWinner(card, side === 'white' ? 'black' : 'white');
  }

  /** Model of the champion, taken from the Final's advancing side (by participant when known). */
  championModelId(): string | null {
    const rounds = this.rounds();
    const finalSeries = rounds[rounds.length - 1]?.games?.[0];
    if (!finalSeries || !this.champion) return null;
    const advanced = this.advancedOf(finalSeries);
    if (advanced && finalSeries.isComplete) {
      if (sameParticipant(advanced, this.sideOf(finalSeries, 'white'))) {
        return finalSeries.whiteModelId;
      }
      if (sameParticipant(advanced, this.sideOf(finalSeries, 'black'))) {
        return finalSeries.blackModelId;
      }
    }
    if (finalSeries.whitePlayerName === this.champion) return finalSeries.whiteModelId;
    if (finalSeries.blackPlayerName === this.champion) return finalSeries.blackModelId;
    return null;
  }

  private sideOf(card: SeriesCard, side: 'white' | 'black'): Participant {
    return side === 'white'
      ? { id: card.whiteParticipantId, name: card.whitePlayerName }
      : { id: card.blackParticipantId, name: card.blackPlayerName };
  }

  private advancedOf(card: SeriesCard): Participant | null {
    if (!card.advancedName && !card.advancedParticipantId) return null;
    return { id: card.advancedParticipantId, name: card.advancedName };
  }

  /** The series score with a non-breaking hyphen, so it never wraps. */
  private scoreText(card: SeriesCard): string {
    const high = Math.max(card.whiteWins, card.blackWins);
    const low = Math.min(card.whiteWins, card.blackWins);
    return `${high}\u2011${low}`;
  }

  /**
   * Who advanced (or, in the Final, won the tournament) and the regular-game score. "(x-y)" is
   * kept on the line of "draw policy" or "tiebreak" so it never wraps.
   */
  private advancedText(card: SeriesCard, advanced: Participant): string {
    const name = advanced.name ?? 'TBD';
    const isFinal = card.roundName === 'Final';
    const score = this.scoreText(card);
    if (card.whiteWins !== card.blackWins) {
      return `${name} ${isFinal ? 'wins the tournament' : 'advances'} ${score}`;
    }
    if (card.tiebreakWinner && sameParticipant(card.tiebreakWinner, advanced)) {
      const games = card.bestOf === 1 ? '1 game' : `${card.bestOf} games`;
      const verb = isFinal ? 'wins the tournament on the tiebreak' : 'wins the tiebreak';
      return `${name} ${verb}\u00a0(${score} after ${games})`;
    }
    const verb = isFinal ? 'wins the tournament' : 'advances';
    return `${name} ${verb} on the draw policy\u00a0(${score})`;
  }

  /** Regular-game wins of this participant in the series. */
  private winsOf(card: SeriesCard, participant: Participant): number {
    if (sameParticipant(participant, this.sideOf(card, 'white'))) return card.whiteWins;
    if (sameParticipant(participant, this.sideOf(card, 'black'))) return card.blackWins;
    return 0;
  }

  getResultText(card: SeriesCard): string {
    if (card.isComplete) {
      const advanced = this.advancedOf(card) ?? card.tiebreakWinner;
      if (advanced) return this.advancedText(card, advanced);
    }
    const game = card.lastFinishedGame;
    if (!game?.result) return '';
    const white = whiteOf(game);
    const black = blackOf(game);
    const resigned = game.resultReason === 'RESIGNATION';
    switch (game.result) {
      case 'WHITE_WINS':
        return (
          `${white.name} wins ${this.winsOf(card, white)}-${this.winsOf(card, black)}` +
          (resigned ? ` (${black.name} resigned)` : '')
        );
      case 'BLACK_WINS':
        return (
          `${black.name} wins ${this.winsOf(card, black)}-${this.winsOf(card, white)}` +
          (resigned ? ` (${white.name} resigned)` : '')
        );
      case 'WHITE_FORFEIT':
        return `${white.name} forfeits`;
      case 'BLACK_FORFEIT':
        return `${black.name} forfeits`;
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

    this.connectorSvgWidth.set(Math.ceil(hostRect.width));
    this.connectorSvgHeight.set(Math.ceil(hostRect.height));
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
    const bestOf = Math.max(1, firstGame.seriesBestOf || 1);
    const activeGame = orderedGames.find(
      (game) =>
        game.status === 'WAITING' || game.status === 'IN_PROGRESS' || game.status === 'PAUSED',
    );
    const currentGame = activeGame ?? lastGame;
    const lastFinishedGame = [...orderedGames].reverse().find(isFinished) ?? null;

    // The card shows the current game's colours. Wins are counted per participant (by id, by name
    // only for older data), so a later game that swaps colours still credits the right player.
    // Only the regular games count toward the score, as in the backend; games beyond best-of-N are
    // sudden-death tiebreaks, and the first decisive one decides the series.
    const white = whiteOf(currentGame);
    const black = blackOf(currentGame);
    let whiteWins = 0;
    let blackWins = 0;
    let tiebreakWinner: Participant | null = null;
    for (const game of orderedGames) {
      const winner = gameWinner(game);
      if (!winner) continue;
      if (game.seriesGameNumber > bestOf) {
        tiebreakWinner ??= winner;
      } else if (sameParticipant(winner, white)) {
        whiteWins += 1;
      } else if (sameParticipant(winner, black)) {
        blackWins += 1;
      }
    }

    const decidingGame = orderedGames.find(
      (game) => !!game.advancedPlayerName || !!game.advancedParticipantId,
    );
    const isComplete = !activeGame && isFinished(lastGame);
    const hasBye =
      isFinished(firstGame) && (isEmptySide(whiteOf(firstGame)) || isEmptySide(blackOf(firstGame)));

    const card: SeriesCard = {
      id: firstGame.seriesId || firstGame.id,
      roundName: firstGame.bracketRound || 'Unknown',
      bracketPosition: firstGame.bracketPosition ?? 0,
      bestOf,
      currentGame,
      lastFinishedGame,
      whitePlayerName: white.name,
      whiteParticipantId: white.id,
      whiteModelId: currentGame.whiteModelId,
      blackPlayerName: black.name,
      blackParticipantId: black.id,
      blackModelId: currentGame.blackModelId,
      whiteWins,
      blackWins,
      tiebreakWinner,
      advancedName: decidingGame?.advancedPlayerName ?? null,
      advancedParticipantId: decidingGame?.advancedParticipantId,
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
    const isDecidedFinal = card.isComplete && card.roundName === 'Final';
    switch (currentGame.status) {
      case 'CANCELLED':
        return {
          stateLabel: gameStatusLabel(currentGame.status),
          stateTone: 'cancelled',
          detailLabel: 'Tournament cancelled. This game ended without a result.',
        };
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
        const bothPlayersSet =
          !isEmptySide(whiteOf(currentGame)) && !isEmptySide(blackOf(currentGame));
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
          detailLabel:
            (isDecidedFinal && this.getResultText(card)) ||
            currentGame.resultReason ||
            'Series ended by forfeit.',
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
