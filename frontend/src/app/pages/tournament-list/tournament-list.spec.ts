import { of } from 'rxjs';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { TestbedHarnessEnvironment } from '@angular/cdk/testing/testbed';
import { MatSelectHarness } from '@angular/material/select/testing';
import { TournamentList } from './tournament-list';
import { Tournament } from '../../models/tournament.model';
import { ApiService, SystemStatusResponse } from '../../services/api.service';

describe('TournamentList', () => {
  it('uses a focusable button to disclose filters and counts the active hidden controls', async () => {
    const harness = await renderList([
      tournament('Alpha Cup', 'CREATED', 1, 4, 2, '2026-03-01T10:00:00Z'),
      tournament('Bravo Finals', 'IN_PROGRESS', 3, 8, 6, '2026-03-03T10:00:00Z'),
      tournament('Classic Arena', 'CREATED', 3, 4, 2, '2026-03-02T10:00:00Z'),
    ]);
    const element = harness.routeNativeElement!;
    const button = element.querySelector<HTMLButtonElement>(
      'button[aria-controls="list-filters"]',
    )!;
    const filters = element.querySelector<HTMLElement>('#list-filters')!;
    const search = element.querySelector<HTMLInputElement>('.search-field input')!;
    expect(button.type).toBe('button');
    button.focus();
    expect(document.activeElement).toBe(button);
    expect(button.getAttribute('aria-expanded')).toBe('false');
    expect(filters.hidden).toBe(true);
    expect(search.closest('[hidden]')).toBeNull();
    expect(button.querySelector('.num')).toBeNull();

    button.click();
    harness.detectChanges();
    expect(button.getAttribute('aria-expanded')).toBe('true');
    expect(filters.hidden).toBe(false);
    const selects = await TestbedHarnessEnvironment.loader(harness.fixture).getAllHarnesses(
      MatSelectHarness,
    );
    await selects[0].clickOptions({ text: 'Not started' });
    await selects[1].clickOptions({ text: 'Best of 3' });
    await selects[2].clickOptions({ text: 'Oldest' });
    expect(button.querySelector('.num')?.textContent?.trim()).toBe('3');
    expect(element.querySelector('.results-label')?.textContent?.trim()).toBe('1 of 3 tournaments');
    search.value = 'classic';
    search.dispatchEvent(new Event('input'));
    harness.detectChanges();
    expect(element.querySelector('.card-title')?.textContent?.trim()).toBe('Classic Arena');
    expect(button.querySelector('.num')?.textContent?.trim()).toBe('3');
    expect(TestBed.inject(Router).url).toBe('/tournaments?view=arena');

    button.focus();
    button.click();
    harness.detectChanges();
    expect(filters.hidden).toBe(true);
    expect(button.querySelector('.num')?.textContent?.trim()).toBe('3');
    button.click();
    harness.detectChanges();
    filters.querySelector<HTMLButtonElement>('.reset-button')!.click();
    harness.detectChanges();
    await harness.fixture.whenStable();
    expect(search.value).toBe('');
    expect(button.querySelector('.num')).toBeNull();
    expect(element.querySelectorAll('.tournament-card')).toHaveLength(3);
    expect(TestBed.inject(Router).url).toBe('/tournaments?view=arena');
  });

  it('shows current game activity only on in-progress tournament cards', async () => {
    const date = '2026-03-01T10:00:00Z';
    const running = tournament('Alpha Cup', 'IN_PROGRESS', 1, 4, 3, date);
    running.games[0].status = running.games[1].status = 'IN_PROGRESS';
    running.games[2].status = 'PAUSED';
    const quiet = tournament('Bravo Finals', 'IN_PROGRESS', 1, 4, 2, date);
    const mixed = tournament('Classic Arena', 'IN_PROGRESS', 1, 8, 4, date);
    mixed.games[0].status = mixed.games[1].status = 'IN_PROGRESS';
    mixed.games[1].bracketRound = 'Round 2';
    const single = tournament('Delta Cup', 'IN_PROGRESS', 1, 4, 2, date);
    single.games[0].status = 'IN_PROGRESS';
    const harness = await renderList([
      running,
      quiet,
      mixed,
      single,
      tournament('Finished Cup', 'COMPLETED', 1, 4, 2, date),
      tournament('Draft Cup', 'CREATED', 1, 4, 0, date),
      tournament('Stopped Cup', 'CANCELLED', 1, 4, 2, date),
    ]);
    const activity = Array.from(harness.routeNativeElement!.querySelectorAll('.card-activity'));
    expect(
      activity.map((line) => line.closest('mat-card')?.querySelector('h2')?.textContent?.trim()),
    ).toEqual([running.name, quiet.name, mixed.name, single.name]);
    expect(activity.map((line) => line.textContent?.replace(/\s+/g, ' ').trim())).toEqual([
      'Round 1 · 2 games running',
      'No games running',
      '2 games running',
      'Round 1 · 1 game running',
    ]);
  });

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

  it('shows and filters cancelled tournaments by the existing status label', () => {
    const component = new TournamentList({} as any);
    component.tournaments.set([
      tournament('Stopped', 'CANCELLED', 1, 4, 2, '2026-03-01T10:00:00Z'),
    ]);
    component.statusFilter.set('CANCELLED');
    expect(component.filteredTournaments().map((t) => t.name)).toEqual(['Stopped']);
    expect(component.statusLabel('CANCELLED')).toBe('Cancelled');
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
      key: {
        source: 'ui',
        maskedSuffix: '••••-key',
        validation: 'valid',
        lastValidatedAt: '2026-03-06T18:00:00Z',
      },
      stockfishAvailable: true,
      stockfishReason: null,
      checkedAt: '2026-03-06T21:30:00Z',
    });

    expect(component.onboardingReady()).toBe(true);
    expect(component.onboardingSummary()).toContain('ready for a first tournament');
    expect(component.onboardingCards()).toHaveLength(3);
  });

  it.each(['env', 'file', 'ui'] as const)(
    'recognizes an untested %s key as connected for onboarding',
    (source) => {
      const component = new TournamentList({} as ApiService);
      component.systemStatus.set(
        statusWithKey({ source, maskedSuffix: null, lastValidatedAt: null, validation: 'unknown' }),
      );
      expect(component.openRouterConnected()).toBe(true);
      expect(component.onboardingReady()).toBe(true);
      expect(component.onboardingSummary()).toContain('ready for a first tournament');
      expect(component.onboardingSummary()).not.toContain('Connect OpenRouter');
    },
  );

  it('prompts only for a missing key and distinguishes an invalid key from an unavailable check', () => {
    const component = new TournamentList({} as ApiService);
    component.systemStatus.set(
      statusWithKey({
        source: 'none',
        maskedSuffix: null,
        lastValidatedAt: null,
        validation: 'none',
      }),
    );
    expect(component.openRouterConnected()).toBe(false);
    expect(component.onboardingReady()).toBe(false);
    expect(component.onboardingSummary()).toBe(
      'Connect your OpenRouter account to create and run tournaments.',
    );

    const status = statusWithKey({
      source: 'env',
      maskedSuffix: null,
      lastValidatedAt: '2026-03-06T18:00:00Z',
      validation: 'invalid',
    });
    component.systemStatus.set(status);
    expect(component.openRouterConnected()).toBe(true);
    expect(component.onboardingReady()).toBe(false);
    expect(component.onboardingSummary()).toContain('failed its last validation');
    expect(component.onboardingSummary()).not.toContain('Connect OpenRouter');

    component.systemStatus.set({ ...status, key: { ...status.key, validation: 'unavailable' } });
    expect(component.onboardingReady()).toBe(true);
    expect(component.onboardingSummary()).not.toContain('failed');
  });
});

async function renderList(tournaments: Tournament[]) {
  TestBed.configureTestingModule({
    providers: [
      provideRouter([{ path: 'tournaments', component: TournamentList }]),
      {
        provide: ApiService,
        useValue: {
          getTournaments: () => of(tournaments),
          getSystemStatus: () =>
            of(
              statusWithKey({
                source: 'ui',
                maskedSuffix: null,
                lastValidatedAt: null,
                validation: 'valid',
              }),
            ),
        },
      },
    ],
  });
  const harness = await RouterTestingHarness.create('/tournaments?view=arena');
  return harness;
}

function statusWithKey(key: SystemStatusResponse['key']): SystemStatusResponse {
  return {
    backendVersion: '0.4.0',
    openRouterValid: false,
    key,
    stockfishAvailable: true,
    stockfishReason: null,
    checkedAt: '2026-03-06T21:30:00Z',
  };
}

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
    maxAttempts: 3,
    matchupBestOf,
    finalsBestOf: null,
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
