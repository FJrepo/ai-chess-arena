import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { type Observable, Subject, of } from 'rxjs';
import type { LiveGameSummary, LiveGamesSnapshot } from '../../models/live-games.model';
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
  const runningGame = { id: 'running', status: 'IN_PROGRESS', practice: false } as const;
  const pausedGame = { id: 'paused', status: 'PAUSED', practice: true } as const;

  function renderSwitcher(getLiveGames: () => Observable<LiveGamesSnapshot>) {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: ApiService, useValue: { getLiveGames } },
        {
          provide: WebSocketService,
          useValue: { liveGamesChanges: () => new Subject<void>(), watchLiveGames: vi.fn() },
        },
      ],
    });
    const fixture = TestBed.createComponent(LiveGamesSwitcher);
    fixture.detectChanges();
    const trigger = fixture.nativeElement.querySelector('button') as HTMLButtonElement;
    return { fixture, trigger };
  }

  function openMenu(snapshot: LiveGamesSnapshot): HTMLElement {
    const { fixture, trigger } = renderSwitcher(() => of(snapshot));
    trigger.click();
    fixture.detectChanges();
    return document.querySelector<HTMLElement>('[role="menu"]')!;
  }

  function renderTrigger() {
    const snapshots = new Subject<LiveGamesSnapshot>();
    const { fixture, trigger } = renderSwitcher(() => snapshots);
    return {
      trigger,
      showGames(...active: LiveGameSummary[]) {
        snapshots.next({
          active,
          lastFinished: { id: 'finished', status: 'FORFEIT', practice: false },
        });
        fixture.detectChanges();
      },
    };
  }

  it('shows the decorative lamp only while a game is running', () => {
    const { trigger, showGames } = renderTrigger();
    const lamp = () => trigger.querySelector('.live-lamp');
    expect(lamp()).toBeNull();
    showGames(runningGame, pausedGame);
    expect(lamp()?.getAttribute('aria-hidden')).toBe('true');
    showGames(pausedGame);
    expect(lamp()).toBeNull();
  });

  it('gates the lamp animation behind a no-preference motion query', () => {
    renderTrigger();
    const rules = [...document.styleSheets].flatMap((sheet) => [...sheet.cssRules]);
    const lampAnimates = (rule: CSSRule): boolean =>
      rule.type === CSSRule.STYLE_RULE &&
      (rule as CSSStyleRule).selectorText.includes('.live-lamp') &&
      Boolean((rule as CSSStyleRule).style.getPropertyValue('animation'));

    expect(rules.some(lampAnimates)).toBe(false);
    expect(
      rules.some(
        (rule) =>
          rule.type === CSSRule.MEDIA_RULE &&
          (rule as CSSMediaRule).media.mediaText === '(prefers-reduced-motion: no-preference)' &&
          [...(rule as CSSMediaRule).cssRules].some(lampAnimates),
      ),
    ).toBe(true);
  });

  it('shows the decorative attention marker only while a game is paused', () => {
    const { trigger, showGames } = renderTrigger();
    const marker = () => trigger.querySelector('.attention-marker');
    expect(marker()).toBeNull();
    showGames(pausedGame);
    expect(marker()?.getAttribute('aria-hidden')).toBe('true');
    expect(marker()?.textContent?.trim()).toBe('');
    expect(marker()?.getAttribute('title')).toBe('1 game needs attention');
    showGames(pausedGame, { ...pausedGame, id: 'another-pause' });
    expect(marker()?.getAttribute('title')).toBe('2 games need attention');
    showGames(runningGame);
    expect(marker()).toBeNull();
  });

  it('names the trigger with the running and attention counts once loaded', () => {
    const { trigger, showGames } = renderTrigger();
    expect(accessibleName(trigger)).toBe('Live games');
    showGames(runningGame, pausedGame, { ...pausedGame, id: 'another-pause' });
    expect(accessibleName(trigger)).toBe('Live games, 1 running, 2 need attention');
    showGames();
    expect(accessibleName(trigger)).toBe('Live games, 0 running, 0 need attention');
  });

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

  it('shows why a paused game needs attention', () => {
    const reason =
      'A model request (test/model, White, move 12) was in flight during a backend restart and may already have been charged. Check spend, then resume.';
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        {
          provide: ApiService,
          useValue: {
            getLiveGames: () =>
              of({
                active: [
                  { id: 'running', status: 'IN_PROGRESS', practice: false, statusDetail: null },
                  { id: 'inspect', status: 'PAUSED', practice: false, statusDetail: reason },
                  { id: 'paused', status: 'PAUSED', practice: false, statusDetail: null },
                ],
              }),
          },
        },
        {
          provide: WebSocketService,
          useValue: { liveGamesChanges: () => new Subject<void>(), watchLiveGames: vi.fn() },
        },
      ],
    });
    const fixture = TestBed.createComponent(LiveGamesSwitcher);
    try {
      fixture.detectChanges();
      (fixture.nativeElement.querySelector('button') as HTMLButtonElement).click();
      fixture.detectChanges();
      const reasonOf = (id: string) =>
        document.querySelector<HTMLElement>(`a[data-live-game-id="${id}"] .reason`);
      expect(reasonOf('inspect')?.textContent?.trim()).toBe(reason);
      expect(reasonOf('inspect')?.title).toBe(reason);
      expect(reasonOf('running')).toBeNull();
      expect(reasonOf('paused')).toBeNull();
    } finally {
      fixture.destroy();
    }
  });
});
