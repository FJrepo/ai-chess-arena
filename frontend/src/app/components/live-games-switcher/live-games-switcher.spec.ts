import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject, of } from 'rxjs';
import type { LiveGamesSnapshot } from '../../models/live-games.model';
import { ApiService } from '../../services/api.service';
import { WebSocketService } from '../../services/websocket.service';
import { LiveGamesSwitcher } from './live-games-switcher';

// Accessible name as assistive technology reads it here: aria-label, then aria-labelledby, then the
// text of descendants that are not hidden with aria-hidden.
function accessibleName(element: Element): string {
  const label = element.getAttribute('aria-label');
  if (label) return label.trim();
  const labelledBy = element.getAttribute('aria-labelledby');
  if (labelledBy) {
    return labelledBy
      .split(/\s+/)
      .map((id) => element.ownerDocument.getElementById(id))
      .map((node) => (node ? accessibleName(node) : ''))
      .join(' ')
      .trim();
  }
  const parts: string[] = [];
  const walk = (node: Node) => {
    if (node.nodeType === Node.TEXT_NODE) {
      parts.push(node.textContent ?? '');
      return;
    }
    if (node instanceof Element && node.getAttribute('aria-hidden') === 'true') return;
    node.childNodes.forEach((child) => {
      walk(child);
      if (child instanceof Element) parts.push(' ');
    });
  };
  walk(element);
  return parts.join('').replace(/\s+/g, ' ').trim();
}

describe('LiveGamesSwitcher', () => {
  function openMenu(snapshot: LiveGamesSnapshot): HTMLElement {
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
    const trigger = fixture.nativeElement.querySelector('button') as HTMLButtonElement;
    trigger.click();
    fixture.detectChanges();
    return document.querySelector<HTMLElement>('[role="menu"]')!;
  }

  it('includes the game state and visible markers in each accessible menu item name', () => {
    const panel = openMenu({
      active: [
        { id: 'running', status: 'IN_PROGRESS', tournamentName: 'Club Rapid', practice: false },
        {
          id: 'paused',
          status: 'PAUSED',
          practice: true,
          whitePlayerName: 'Light',
          blackPlayerName: 'Dark',
        },
      ],
      lastFinished: { id: 'finished', status: 'FORFEIT', practice: false },
    });
    const labels = [...panel.querySelectorAll('.section-label[aria-hidden="true"]')];
    const items = [...panel.querySelectorAll('[role="menuitem"]')];
    expect.soft(accessibleName(panel)).toBe('Live games');
    expect
      .soft(labels.map((label) => label.textContent?.trim()))
      .toEqual(['Running', 'Paused', 'Last finished']);
    expect(items.map((item) => accessibleName(item))).toEqual([
      'White vs Black Running Club Rapid',
      'Light vs Dark Paused Practice Needs attention',
      'White vs Black Last finished Game',
    ]);
  });

  it('wraps long matchups with a two-line clamp', () => {
    const white = 'Atlas Experimental Reasoning Edition 2026 Extended Context';
    const black = 'Kestrel Grandmaster Research Preview 405B Tournament Build';
    const panel = openMenu({
      active: [
        {
          id: 'long-names',
          status: 'IN_PROGRESS',
          practice: false,
          whitePlayerName: white,
          blackPlayerName: black,
        },
      ],
    });
    const matchup = panel.querySelector('.matchup')!;

    expect(accessibleName(panel.querySelector('[role="menuitem"]')!)).toContain(
      `${white} vs ${black}`,
    );
    expect(getComputedStyle(matchup).getPropertyValue('-webkit-line-clamp')).toBe('2');
    for (const name of matchup.querySelectorAll('.player')) {
      expect(getComputedStyle(name).whiteSpace).not.toBe('nowrap');
    }
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
