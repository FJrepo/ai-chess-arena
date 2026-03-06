import { TournamentList } from './tournament-list';
import { Tournament } from '../../models/tournament.model';

describe('TournamentList', () => {
  it('filters tournaments by search, status, and best-of', () => {
    const component = new TournamentList({} as any, {} as any);
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

  it('sorts tournaments by participant count when requested', () => {
    const component = new TournamentList({} as any, {} as any);
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

  it('describes onboarding as ready when system status is healthy', () => {
    const component = new TournamentList({} as any, {} as any);
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
      modelId: `model-${index + 1}`,
      customInstructions: null,
      seed: index + 1,
    })),
    games: Array.from({ length: gameCount }, (_, index) => ({
      id: `${name}-game-${index}`,
      tournamentId: name,
      whitePlayerName: 'White',
      whiteModelId: 'model-w',
      blackPlayerName: 'Black',
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
