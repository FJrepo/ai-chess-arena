import { TestBed } from '@angular/core/testing';
import { TestbedHarnessEnvironment } from '@angular/cdk/testing/testbed';
import { MatSelectHarness } from '@angular/material/select/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { EMPTY, of, Subject, throwError } from 'rxjs';
import { TournamentBracket } from './tournament-bracket';
import { ApiService } from '../../services/api.service';
import { BracketDrawMode, Game, Tournament } from '../../models/tournament.model';

describe('TournamentBracket', () => {
  beforeEach(() => {
    // jsdom has no ResizeObserver; used by bracket connectors and Material form labels.
    vi.stubGlobal(
      'ResizeObserver',
      class {
        observe(): void {}
        unobserve(): void {}
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
      'Black wins the tournament on the draw policy\u00a0(0\u20110)',
    );
  });

  function drawPage(result = of([] as Game[])) {
    const created = tournament('CREATED', null, finalGame());
    created.games = [];
    created.participants = [
      {
        id: 'a',
        playerName: 'A',
        controlType: 'AI',
        modelId: 'm-a',
        customInstructions: null,
        seed: 0,
      },
      {
        id: 'b',
        playerName: 'B',
        controlType: 'AI',
        modelId: 'm-b',
        customInstructions: null,
        seed: 1,
      },
    ];
    const drawn: Tournament = {
      ...created,
      status: 'IN_PROGRESS',
      games: [finalGame({ status: 'WAITING' })],
      participants: [
        { ...created.participants[0], seed: 1 },
        { ...created.participants[1], seed: 0 },
      ],
    };
    const api = {
      getTournament: vi.fn().mockReturnValueOnce(of(created)).mockReturnValue(of(drawn)),
      getTournamentCostSummary: () => EMPTY,
      generateBracket: vi.fn().mockReturnValue(result),
    };
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: ApiService, useValue: api },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ id: created.id }) } },
        },
      ],
    });
    const fixture = TestBed.createComponent(TournamentBracket);
    fixture.detectChanges();
    return { fixture, api };
  }

  it.each(['RANDOM', 'ORDER'] as BracketDrawMode[])(
    'defaults to Random draw, allows added order, and submits %s',
    async (mode) => {
      const { fixture, api } = drawPage();
      const select = await TestbedHarnessEnvironment.loader(fixture).getHarness(MatSelectHarness);
      expect(await select.getValueText()).toBe('Random draw');
      if (mode === 'ORDER') {
        await select.open();
        await select.clickOptions({ text: 'In added order' });
      }

      const button = fixture.nativeElement.querySelector('.generate-section button') as HTMLElement;
      button.click();
      fixture.detectChanges();

      expect(api.generateBracket).toHaveBeenCalledExactlyOnceWith('t-1', mode);
      expect(fixture.nativeElement.querySelector('.generate-section')).toBeNull();
      const seeds = Array.from(
        fixture.nativeElement.querySelectorAll('.participant-seeds li') as NodeListOf<HTMLElement>,
        (row) => row.textContent?.trim(),
      );
      expect(seeds).toEqual(['Seed 0: B', 'Seed 1: A']);
    },
  );

  it('disables the draw selector and prevents duplicate requests while generating', async () => {
    const pending = new Subject<Game[]>();
    const { fixture, api } = drawPage(pending);
    const select = await TestbedHarnessEnvironment.loader(fixture).getHarness(MatSelectHarness);

    fixture.componentInstance.generateBracket();
    fixture.componentInstance.generateBracket();
    fixture.detectChanges();

    expect(await select.isDisabled()).toBe(true);
    expect(fixture.nativeElement.querySelector('.generate-section button').disabled).toBe(true);
    expect(api.generateBracket).toHaveBeenCalledOnce();
    pending.complete();
  });

  it('reloads the winning draw after a concurrent generation conflict', () => {
    const { fixture, api } = drawPage(
      throwError(() => ({
        status: 409,
        error: { error: 'The bracket has already been generated' },
      })),
    );

    fixture.componentInstance.generateBracket();
    fixture.detectChanges();

    expect(fixture.componentInstance.generating()).toBe(false);
    expect(api.getTournament).toHaveBeenCalledTimes(2);
    expect(fixture.nativeElement.querySelector('.generate-section')).toBeNull();
    expect(fixture.nativeElement.querySelector('.participant-seeds')?.textContent).toContain(
      'Seed 0: B',
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
