import { Component, OnInit, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { DatePipe } from '@angular/common';
import { ApiService } from '../../services/api.service';
import { Tournament } from '../../models/tournament.model';
import { finalize } from 'rxjs';

@Component({
  selector: 'app-tournament-list',
  imports: [RouterLink, MatCardModule, MatButtonModule, MatIconModule, MatChipsModule, DatePipe],
  templateUrl: './tournament-list.html',
  styleUrl: './tournament-list.scss',
})
export class TournamentList implements OnInit {
  tournaments = signal<Tournament[]>([]);
  deletingIds = signal<Set<string>>(new Set());
  deleteError = signal<string | null>(null);

  constructor(
    private api: ApiService,
    private router: Router,
  ) {}

  ngOnInit() {
    this.api.getTournaments().subscribe((t) => this.tournaments.set(t));
  }

  openTournament(id: string): void {
    this.router.navigate(['/tournaments', id]);
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
}
