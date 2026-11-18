import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject, of } from 'rxjs';
import type { LiveGamesSnapshot } from '../../models/live-games.model';
import { ApiService } from '../../services/api.service';
import { WebSocketService } from '../../services/websocket.service';
import { LiveGamesSwitcher } from './live-games-switcher';

describe('LiveGamesSwitcher', () => {
  it('counts running games, preserves menu order and refreshes after a live change during an in-flight read', () => {
    vi.useFakeTimers();
    const first = new Subject<LiveGamesSnapshot>();
    const changed = new Subject<void>();
    const empty = { active: [] };
    const getLiveGames = vi.fn().mockReturnValueOnce(first).mockReturnValue(of(empty));
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: ApiService, useValue: { getLiveGames } },
        {
          provide: WebSocketService,
          useValue: { liveGamesChanges: () => changed, watchLiveGames: vi.fn() },
        },
      ],
    });
    const fixture = TestBed.createComponent(LiveGamesSwitcher);
    const menu = fixture.componentInstance;
    try {
      fixture.detectChanges();
      first.next({
        active: [
          { id: 'p', status: 'IN_PROGRESS', practice: true, moveNumber: 2, evaluationCp: 0 },
          { id: 'a', status: 'PAUSED', practice: false },
        ],
        lastFinished: { id: 'f', status: 'COMPLETED', practice: false },
      });
      expect(menu.running()).toBe(1);
      expect(menu.rows().map((game) => game.id)).toEqual(['p', 'a', 'f']);
      expect(menu.score(menu.rows()[0])).toBe('0.0');
      expect(menu.score(menu.rows()[1])).toBeNull();

      changed.next();
      changed.next();
      vi.advanceTimersByTime(200);
      expect(getLiveGames).toHaveBeenCalledTimes(1);
      first.complete();
      expect(getLiveGames).toHaveBeenCalledTimes(2);
      expect(menu.snapshot()).toEqual(empty);
      expect(menu.running()).toBe(0);

      vi.advanceTimersByTime(15000);
      expect(getLiveGames).toHaveBeenCalledTimes(3);
    } finally {
      fixture.destroy();
      vi.useRealTimers();
    }
  });
});
