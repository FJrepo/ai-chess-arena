import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject, of } from 'rxjs';
import type { LiveGamesSnapshot } from '../../models/live-games.model';
import { ApiService } from '../../services/api.service';
import { WebSocketService } from '../../services/websocket.service';
import { LiveGamesSwitcher } from './live-games-switcher';

describe('LiveGamesSwitcher', () => {
  it('names the menu and keeps the state text in the menu items', () => {
    const snapshot: LiveGamesSnapshot = {
      active: [
        { id: 'practice', status: 'IN_PROGRESS', practice: true },
        { id: 'paused', status: 'PAUSED', practice: false },
      ],
      lastFinished: { id: 'finished', status: 'FORFEIT', practice: false },
    };
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: ApiService, useValue: { getLiveGames: () => of(snapshot) } },
        {
          provide: WebSocketService,
          useValue: { liveGamesChanges: () => new Subject<void>(), watchLiveGames: vi.fn() },
        },
      ],
    });
    const fixture = TestBed.createComponent(LiveGamesSwitcher);
    fixture.detectChanges();
    fixture.nativeElement.querySelector('button').click();
    fixture.detectChanges();

    const panel = document.querySelector('[role="menu"]')!;
    const labels = [...panel.querySelectorAll('.section-label[aria-hidden="true"]')];
    const items = [...panel.querySelectorAll('[role="menuitem"]')];
    expect.soft(panel.getAttribute('aria-label')).toBe('Live games');
    expect
      .soft(labels.map((label) => label.textContent?.trim()))
      .toEqual(['Running', 'Paused', 'Last finished']);
    expect.soft(items[0].textContent).toMatch(/Running\s+Practice/);
    expect.soft(items[1].textContent).toContain('Needs attention');
    expect(items[2].textContent).toContain('Last finished');
  });

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

  it('restores keyboard focus after reordered, removed and emptied live rows', () => {
    vi.useFakeTimers();
    const changed = new Subject<void>();
    const first = { id: 'first', status: 'IN_PROGRESS', practice: false } as const;
    const second = { id: 'second', status: 'IN_PROGRESS', practice: true } as const;
    let snapshot: LiveGamesSnapshot = { active: [first, second] };
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: ApiService, useValue: { getLiveGames: () => of(snapshot) } },
        {
          provide: WebSocketService,
          useValue: { liveGamesChanges: () => changed, watchLiveGames: vi.fn() },
        },
      ],
    });
    const fixture = TestBed.createComponent(LiveGamesSwitcher);
    const row = (id: string) =>
      document.querySelector<HTMLAnchorElement>(`a[data-live-game-id="${id}"]`)!;
    const refresh = (active: LiveGamesSnapshot['active']) => {
      snapshot = { active };
      changed.next();
      vi.advanceTimersByTime(200);
    };

    try {
      fixture.detectChanges();
      const trigger = fixture.nativeElement.querySelector('button') as HTMLButtonElement;
      trigger.focus();
      trigger.click();
      fixture.detectChanges();
      row('second').focus();

      refresh([second, { ...first, status: 'PAUSED' }]);
      // The test DOM does not drop focus when Angular detaches and reinserts this row.
      row('second').blur();
      fixture.detectChanges();
      expect(document.activeElement).toBe(row('second'));

      const down = new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true });
      Object.defineProperty(down, 'keyCode', { value: 40 });
      row('second').dispatchEvent(down);
      expect(document.activeElement).toBe(row('first'));

      refresh([second]);
      fixture.detectChanges();
      expect(document.activeElement).toBe(row('second'));

      refresh([]);
      fixture.detectChanges();
      expect(trigger.getAttribute('aria-expanded')).toBe('false');
      expect(document.activeElement).toBe(trigger);
    } finally {
      fixture.destroy();
      vi.useRealTimers();
    }
  });
});
