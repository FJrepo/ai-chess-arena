import { Component, OnInit, computed, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { DatePipe } from '@angular/common';
import { ApiService, SystemStatusResponse } from '../../services/api.service';
import { Tournament } from '../../models/tournament.model';
import { finalize } from 'rxjs';

@Component({
  selector: 'app-tournament-list',
  imports: [
    RouterLink,
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatChipsModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatProgressSpinnerModule,
    DatePipe,
  ],
  templateUrl: './tournament-list.html',
  styleUrl: './tournament-list.scss',
})
export class TournamentList implements OnInit {
  private readonly starterPresets = [
    {
      key: 'quickstart',
      eyebrow: 'Fastest Path',
      title: 'Quick Start Arena',
      description: 'Best-of-1 matchups, lower timeout, and minimal setup for the first tournament.',
      detail: 'Fastest way to validate your stack end to end.',
    },
    {
      key: 'balanced',
      eyebrow: 'Recommended',
      title: 'Balanced Ladder',
      description:
        'Best-of-3 matchups with safer progression defaults for a more representative first run.',
      detail: 'Good default once provider access is working.',
    },
    {
      key: 'showcase',
      eyebrow: 'Series Demo',
      title: 'Series Showcase',
      description: 'Best-of-3 rounds with a best-of-5 final to exercise the richer bracket flow.',
      detail: 'Use this once you want a more visible demo.',
    },
  ] as const;

  tournaments = signal<Tournament[]>([]);
  deletingIds = signal<Set<string>>(new Set());
  deleteError = signal<string | null>(null);
  systemStatus = signal<SystemStatusResponse | null>(null);
  systemStatusLoading = signal(false);
  systemStatusError = signal<string | null>(null);
  searchTerm = signal('');
  statusFilter = signal<'ALL' | Tournament['status']>('ALL');
  bestOfFilter = signal<'ALL' | Tournament['matchupBestOf']>('ALL');
  sortBy = signal<'newest' | 'oldest' | 'participants' | 'games' | 'name'>('newest');
  onboardingReady = computed(
    () => this.systemStatus()?.openRouterValid === true && this.systemStatusError() === null,
  );
  onboardingCards = computed(() => this.starterPresets);

  filteredTournaments = computed(() => {
    const query = this.searchTerm().trim().toLowerCase();
    const status = this.statusFilter();
    const bestOf = this.bestOfFilter();
    const sort = this.sortBy();

    const filtered = this.tournaments().filter((tournament) => {
      const matchesQuery =
        query.length === 0 ||
        tournament.name.toLowerCase().includes(query) ||
        tournament.status.toLowerCase().includes(query);
      const matchesStatus = status === 'ALL' || tournament.status === status;
      const matchesBestOf = bestOf === 'ALL' || tournament.matchupBestOf === bestOf;

      return matchesQuery && matchesStatus && matchesBestOf;
    });

    return [...filtered].sort((left, right) => {
      switch (sort) {
        case 'oldest':
          return Date.parse(left.createdAt) - Date.parse(right.createdAt);
        case 'participants':
          return right.participants.length - left.participants.length;
        case 'games':
          return right.games.length - left.games.length;
        case 'name':
          return left.name.localeCompare(right.name);
        case 'newest':
        default:
          return Date.parse(right.createdAt) - Date.parse(left.createdAt);
      }
    });
  });

  hasActiveFilters = computed(
    () =>
      this.searchTerm().trim().length > 0 ||
      this.statusFilter() !== 'ALL' ||
      this.bestOfFilter() !== 'ALL' ||
      this.sortBy() !== 'newest',
  );

  constructor(
    private api: ApiService,
    private router: Router,
  ) {}

  ngOnInit() {
    this.api.getTournaments().subscribe((t) => this.tournaments.set(t));
    this.loadSystemStatus();
  }

  openTournament(id: string): void {
    this.router.navigate(['/tournaments', id]);
  }

  resetFilters(): void {
    this.searchTerm.set('');
    this.statusFilter.set('ALL');
    this.bestOfFilter.set('ALL');
    this.sortBy.set('newest');
  }

  deleteTournament(id: string, event: MouseEvent): void {
    event.preventDefault();
    event.stopPropagation();
    if (this.deletingIds().has(id)) {
      return;
    }

    this.deleteError.set(null);
    this.deletingIds.update((current) => {
      const next = new Set(current);
      next.add(id);
      return next;
    });

    this.api
      .deleteTournament(id)
      .pipe(
        finalize(() => {
          this.deletingIds.update((current) => {
            const next = new Set(current);
            next.delete(id);
            return next;
          });
        }),
      )
      .subscribe({
        next: () => {
          this.tournaments.update((list) => list.filter((t) => t.id !== id));
        },
        error: (err) => {
          const rawError = err?.error;
          const message =
            typeof rawError === 'string'
              ? rawError
              : typeof rawError?.error === 'string'
                ? rawError.error
                : 'Failed to delete tournament.';
          this.deleteError.set(message);
        },
      });
  }

  statusColor(status: string): string {
    switch (status) {
      case 'CREATED':
        return 'primary';
      case 'IN_PROGRESS':
        return 'accent';
      case 'COMPLETED':
        return '';
      default:
        return 'warn';
    }
  }

  resultCountLabel(): string {
    const visible = this.filteredTournaments().length;
    const total = this.tournaments().length;

    if (visible === total) {
      return `${total} tournaments`;
    }

    return `${visible} of ${total} tournaments`;
  }

  onboardingSummary(): string {
    if (this.systemStatusLoading()) {
      return 'Checking provider and evaluation readiness...';
    }
    if (this.systemStatusError()) {
      return 'System readiness could not be verified yet. Open the System page before launching a tournament.';
    }
    if (this.onboardingReady()) {
      return 'OpenRouter is configured and the stack is ready for a first tournament.';
    }
    return 'The stack is up, but provider access still needs attention before a tournament can start.';
  }

  private loadSystemStatus(): void {
    this.systemStatusLoading.set(true);
    this.systemStatusError.set(null);
    this.api.getSystemStatus().subscribe({
      next: (status) => this.systemStatus.set(status),
      error: () => {
        this.systemStatus.set(null);
        this.systemStatusError.set('System status is unavailable.');
      },
      complete: () => this.systemStatusLoading.set(false),
    });
  }
}
