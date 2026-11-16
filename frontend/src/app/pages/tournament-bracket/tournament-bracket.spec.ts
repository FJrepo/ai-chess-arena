import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { EMPTY, of } from 'rxjs';
import { TournamentBracket } from './tournament-bracket';
import { ApiService } from '../../services/api.service';
import { Game, Tournament } from '../../models/tournament.model';

describe('TournamentBracket champion', () => {
  beforeEach(() => {
    // jsdom has no ResizeObserver; the bracket's connector layout only needs observe/disconnect.
    vi.stubGlobal(
      'ResizeObserver',
      class {
        observe(): void {}
        disconnect(): void {}
      },
    );
  });

  afterEach(() => vi.unstubAllGlobals());

  function render(t: Tournament): HTMLElement {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        {
          provide: ApiService,
          useValue: { getTournament: () => of(t), getTournamentCostSummary: () => EMPTY },
        },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ id: t.id }) } },
        },
      ],
    });
    const fixture = TestBed.createComponent(TournamentBracket);
    fixture.detectChanges();
    return fixture.nativeElement;
  }

  it('is hidden while the tournament is still running', () => {
    const el = render(tournament('IN_PROGRESS', 'White', finalGame({ status: 'WAITING' })));

    expect(el.querySelector('.champion-card')).toBeNull();
  });

  it('is hidden for a completed tournament without a winner', () => {
    const el = render(tournament('COMPLETED', null, finalGame()));

    expect(el.querySelector('.champion-card')).toBeNull();
  });

  it('shows the winner of a completed tournament after the final', () => {
    const el = render(tournament('COMPLETED', 'White', finalGame({ advancedPlayerName: 'White' })));

    expect(el.querySelector('.champion-card')?.textContent).toContain('White');
  });

  it('shows the winner of a final decided by the draw policy', () => {
    const el = render(
      tournament(
        'COMPLETED',
        'Black',
        finalGame({ result: 'DRAW', resultReason: 'STALEMATE', advancedPlayerName: 'Black' }),
      ),
    );

    expect(el.querySelector('.champion-card')?.textContent).toContain('Black');
    expect(el.querySelector('.series-context')?.textContent).toContain(
      'Black wins the tournament on the draw policy (0‑0)',
    );
  });
});

function tournament(
  status: Tournament['status'],
  winnerName: string | null,
  final: Game,
): Tournament {
  return {
    id: 't-1',
    name: 'Cup',
    status,
    winnerName,
    format: 'SINGLE_ELIMINATION',
    drawPolicy: 'BLACK_ADVANCES',
    sharedCustomInstructions: null,
    moveTimeoutSeconds: 60,
    maxRetries: 3,
    matchupBestOf: 1,
    finalsBestOf: null,
    trashTalkEnabled: false,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
    participants: [],
    games: [final],
  };
}

function finalGame(overrides: Partial<Game> = {}): Game {
  return {
    id: 'final-1',
    tournamentId: 't-1',
    whitePlayerName: 'White',
    whiteControlType: 'AI',
    whiteModelId: 'model-a',
    blackPlayerName: 'Black',
    blackControlType: 'AI',
    blackModelId: 'model-b',
    status: 'COMPLETED',
    result: 'WHITE_WINS',
    resultReason: 'CHECKMATE',
    pgn: null,
    currentFen: 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1',
    bracketRound: 'Final',
    bracketPosition: 0,
    seriesId: 'final-1',
    seriesGameNumber: 1,
    seriesBestOf: 1,
    totalCostUsd: 0,
    createdAt: new Date().toISOString(),
    startedAt: null,
    completedAt: null,
    moves: [],
    chatMessages: [],
    ...overrides,
  };
}
