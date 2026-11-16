import { of } from 'rxjs';
import { TournamentList } from './tournament-list';
import { Tournament } from '../../models/tournament.model';
import { ApiService } from '../../services/api.service';

describe('TournamentList', () => {
  it('filters tournaments by search, status, and best-of', () => {
    const component = new TournamentList({} as any);
    component.tournaments.set([
      tournament('Alpha Cup', 'CREATED', 1, 4, 2, '2026-03-01T10:00:00Z'),
      tournament('Bravo Finals', 'IN_PROGRESS', 3, 8, 6, '2026-03-03T10:00:00Z'),
      tournament('Classic Arena', 'COMPLETED', 5, 16, 14, '2026-03-02T10:00:00Z'),
    ]);

    component.searchTerm.set('bravo');
    component.statusFilter.set('IN_PROGRESS');
    component.bestOfFilter.set(3);

    expect(component.filteredTournaments().map((t) => t.name)).toEqual(['Bravo Finals']);
  });

  it('finds tournaments by their readable status label', () => {
    const component = new TournamentList({} as any);
    component.tournaments.set([
      tournament('Alpha Cup', 'CREATED', 1, 4, 2, '2026-03-01T10:00:00Z'),
      tournament('Bravo Finals', 'IN_PROGRESS', 3, 8, 6, '2026-03-03T10:00:00Z'),
    ]);

    component.searchTerm.set('not started');

    expect(component.filteredTournaments().map((t) => t.name)).toEqual(['Alpha Cup']);
    expect(component.statusLabel('IN_PROGRESS')).toBe('In progress');
  });

  it('sorts tournaments by participant count when requested', () => {
    const component = new TournamentList({} as any);
    component.tournaments.set([
      tournament('Alpha Cup', 'CREATED', 1, 4, 2, '2026-03-01T10:00:00Z'),
      tournament('Bravo Finals', 'IN_PROGRESS', 3, 8, 6, '2026-03-03T10:00:00Z'),
      tournament('Classic Arena', 'COMPLETED', 5, 16, 14, '2026-03-02T10:00:00Z'),
    ]);

    component.sortBy.set('participants');

    expect(component.filteredTournaments().map((t) => t.name)).toEqual([
      'Classic Arena',
      'Bravo Finals',
      'Alpha Cup',
    ]);
  });

  describe('deleting a tournament', () => {
    afterEach(() => vi.restoreAllMocks());

    it('asks for confirmation and keeps the tournament when it is declined', () => {
      const deleted: string[] = [];
      const component = new TournamentList(deleteApi(deleted));
      const alpha = tournament('Alpha Cup', 'COMPLETED', 1, 4, 2, '2026-03-01T10:00:00Z');
      component.tournaments.set([alpha]);
      const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);

      component.deleteTournament(alpha);

      expect(confirm).toHaveBeenCalledWith(
        'Delete "Alpha Cup" with all its games and history? This cannot be undone.',
      );
      expect(deleted).toEqual([]);
      expect(component.tournaments()).toHaveLength(1);
    });

    it('deletes the tournament once confirmed', () => {
      const deleted: string[] = [];
      const component = new TournamentList(deleteApi(deleted));
      const alpha = tournament('Alpha Cup', 'CREATED', 1, 4, 2, '2026-03-01T10:00:00Z');
      component.tournaments.set([alpha]);
      vi.spyOn(window, 'confirm').mockReturnValue(true);

      component.deleteTournament(alpha);

      expect(deleted).toEqual(['Alpha Cup']);
      expect(component.tournaments()).toEqual([]);
    });

    it('warns that games and history are lost for a running tournament', () => {
      const component = new TournamentList(deleteApi([]));
      const running = tournament('Bravo Finals', 'IN_PROGRESS', 3, 8, 6, '2026-03-03T10:00:00Z');
      const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);

      component.deleteTournament(running);

      const message = confirm.mock.calls[0][0] as string;
      expect(message).toContain('"Bravo Finals" is in progress');
      expect(message).toContain('deletes all its games, moves, chat and history');
    });
  });

  it('describes onboarding as ready when system status is healthy', () => {
    const component = new TournamentList({} as any);
    component.systemStatus.set({
      backendVersion: '0.4.0',
      openRouterValid: true,
      stockfishAvailable: true,
      stockfishReason: null,
      checkedAt: '2026-03-06T21:30:00Z',
    });

    expect(component.onboardingReady()).toBe(true);
    expect(component.onboardingSummary()).toContain('ready for a first tournament');
    expect(component.onboardingCards()).toHaveLength(3);
  });
});

function tournament(
  name: string,
  status: Tournament['status'],
  matchupBestOf: Tournament['matchupBestOf'],
  participantCount: number,
  gameCount: number,
  createdAt: string,
): Tournament {
  return {
    id: name,
    name,
    status,
    format: 'SINGLE_ELIMINATION',
    drawPolicy: 'WHITE_ADVANCES',
    sharedCustomInstructions: null,
    moveTimeoutSeconds: 60,
    maxRetries: 3,
    matchupBestOf,
    finalsBestOf: null,
    trashTalkEnabled: true,
    createdAt,
    updatedAt: createdAt,
    participants: Array.from({ length: participantCount }, (_, index) => ({
      id: `${name}-participant-${index}`,
      playerName: `Player ${index + 1}`,
      controlType: 'AI' as const,
      modelId: `model-${index + 1}`,
      customInstructions: null,
      seed: index + 1,
    })),
    games: Array.from({ length: gameCount }, (_, index) => ({
      id: `${name}-game-${index}`,
      tournamentId: name,
      whitePlayerName: 'White',
      whiteControlType: 'AI',
      whiteModelId: 'model-w',
      blackPlayerName: 'Black',
      blackControlType: 'AI',
      blackModelId: 'model-b',
      status: 'WAITING',
      result: null,
      resultReason: null,
      pgn: null,
      currentFen: 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1',
      bracketRound: 'Round 1',
      bracketPosition: index,
      seriesId: `${name}-series-${index}`,
      seriesGameNumber: 1,
      seriesBestOf: matchupBestOf,
      totalCostUsd: 0,
      createdAt,
      startedAt: null,
      completedAt: null,
      moves: [],
      chatMessages: [],
    })),
  };
}

function deleteApi(deleted: string[]): ApiService {
  return {
    deleteTournament: (id: string) => {
      deleted.push(id);
      return of(undefined);
    },
  } as unknown as ApiService;
}
