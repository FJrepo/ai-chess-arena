import { DOCUMENT } from '@angular/common';
import {
  Component,
  DestroyRef,
  Injector,
  OnInit,
  afterNextRender,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule, MatMenuTrigger } from '@angular/material/menu';
import { RouterLink } from '@angular/router';
import { auditTime, finalize, merge, timer, timeout } from 'rxjs';
import type { LiveGameSummary, LiveGamesSnapshot } from '../../models/live-games.model';
import { ApiService } from '../../services/api.service';
import { WebSocketService } from '../../services/websocket.service';
import { evaluationLabel } from '../../utils/position-evaluation';

@Component({
  selector: 'app-live-games-switcher',
  imports: [MatButtonModule, MatIconModule, MatMenuModule, RouterLink],
  templateUrl: './live-games-switcher.html',
  styleUrl: './live-games-switcher.scss',
})
export class LiveGamesSwitcher implements OnInit {
  private readonly api = inject(ApiService);
  private readonly socket = inject(WebSocketService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly injector = inject(Injector);
  private readonly document = inject(DOCUMENT);
  private readonly trigger = viewChild(MatMenuTrigger);
  private inFlight = false;
  private refreshPending = false;

  readonly snapshot = signal<LiveGamesSnapshot | null>(null);
  readonly unavailable = signal(false);
  readonly running = computed(
    () => this.snapshot()?.active.filter((game) => game.status === 'IN_PROGRESS').length ?? 0,
  );
  readonly rows = computed(() => {
    const snapshot = this.snapshot();
    return snapshot?.lastFinished
      ? [...snapshot.active, snapshot.lastFinished]
      : (snapshot?.active ?? []);
  });

  ngOnInit(): void {
    merge(timer(15000, 15000), this.socket.liveGamesChanges())
      .pipe(auditTime(200), takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.refresh());
    this.socket.watchLiveGames();
    this.refresh();
  }

  score(game: LiveGameSummary): string | null {
    const cp = game.evaluationCp ?? null;
    const mate = game.evaluationMate ?? null;
    return mate === 0 || (cp == null && mate == null)
      ? null
      : evaluationLabel({
          cp,
          mate,
          checkmated: null,
        });
  }

  private installSnapshot(snapshot: LiveGamesSnapshot): void {
    const focused = this.document.activeElement;
    const focusedId = focused?.getAttribute('data-live-game-id');
    this.snapshot.set(snapshot);
    const trigger = this.trigger();
    if (!focusedId || !trigger?.menuOpen || this.rows().some((row) => row.id === focusedId)) return;

    if (!this.rows().length) {
      trigger.closeMenu();
    } else {
      afterNextRender(
        () => {
          if (
            trigger.menuOpen &&
            (this.document.activeElement === focused ||
              this.document.activeElement === this.document.body)
          ) {
            trigger.menu?.focusFirstItem('keyboard');
          }
        },
        { injector: this.injector },
      );
    }
  }

  private refresh(): void {
    if (this.inFlight) {
      this.refreshPending = true;
      return;
    }
    this.inFlight = true;
    this.api
      .getLiveGames()
      .pipe(
        timeout(15000),
        takeUntilDestroyed(this.destroyRef),
        finalize(() => {
          this.inFlight = false;
          if (this.refreshPending && !this.destroyRef.destroyed) {
            this.refreshPending = false;
            this.refresh();
          }
        }),
      )
      .subscribe({
        next: (snapshot) => {
          this.installSnapshot(snapshot);
          this.unavailable.set(false);
        },
        error: () => this.unavailable.set(true),
      });
  }
}
