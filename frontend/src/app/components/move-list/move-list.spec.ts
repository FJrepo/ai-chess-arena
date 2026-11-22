import { TestBed } from '@angular/core/testing';
import { Move, MoveQuality } from '../../models/tournament.model';
import { MoveListComponent } from './move-list';

function move(
  moveNumber: number,
  color: 'WHITE' | 'BLACK',
  san: string,
  quality: MoveQuality | null = null,
): Move {
  return {
    id: `m-${moveNumber}-${color}`,
    moveNumber,
    color,
    san,
    fen: 'fen',
    modelId: 'model-a',
    promptVersion: null,
    promptHash: null,
    promptTokens: null,
    completionTokens: null,
    costUsd: null,
    responseTimeMs: 100,
    retryCount: 0,
    isOverride: false,
    createdAt: '2026-03-06T10:02:00Z',
    quality,
  };
}

function render(moves: Move[]): HTMLElement {
  const fixture = TestBed.createComponent(MoveListComponent);
  fixture.componentRef.setInput('moves', moves);
  fixture.detectChanges();
  return fixture.nativeElement as HTMLElement;
}

describe('MoveListComponent selection', () => {
  it('reveals only the selected ply and preserves manual scroll on move updates', () => {
    const fixture = TestBed.createComponent(MoveListComponent);
    const moves = [move(1, 'WHITE', 'e4'), move(1, 'BLACK', 'e5'), move(2, 'WHITE', 'Nf3')];
    fixture.componentRef.setInput('moves', moves);
    fixture.detectChanges();
    const element: HTMLElement = fixture.nativeElement;
    const list = element.querySelector<HTMLElement>('.move-list-scroll')!;
    const [first, , last] = element.querySelectorAll<HTMLElement>('.move');
    vi.spyOn(list, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 0, 100, 40));
    vi.spyOn(last, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 60, 100, 20));
    vi.spyOn(first, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, -40, 100, 20));

    fixture.componentRef.setInput('currentIndex', 2);
    fixture.detectChanges();
    expect(element.querySelector('.move.active')).toBe(last);
    expect(list.scrollTop).toBe(40);

    fixture.componentRef.setInput('currentIndex', 0);
    fixture.detectChanges();
    expect(element.querySelector('.move.active')).toBe(first);
    expect(list.scrollTop).toBe(0);

    list.scrollTop = 12;
    fixture.componentRef.setInput('moves', [...moves, move(2, 'BLACK', 'Nc6')]);
    fixture.detectChanges();
    expect(element.querySelector('.move.active')).toBe(first);
    expect(list.scrollTop).toBe(12);
  });
});

describe('MoveListComponent quality annotations', () => {
  it('marks book moves, suppresses quality markers and keeps them selectable for board replay', () => {
    const fixture = TestBed.createComponent(MoveListComponent);
    fixture.componentRef.setInput('moves', [
      { ...move(1, 'WHITE', 'e4'), isBook: true },
      { ...move(1, 'BLACK', 'e5'), isBook: true },
      move(2, 'WHITE', 'Nf3'),
    ]);
    const selected = vi.fn();
    fixture.componentInstance.moveSelected.subscribe(selected);
    fixture.detectChanges();
    const element: HTMLElement = fixture.nativeElement;
    expect(element.querySelectorAll('.book-badge')).toHaveLength(2);
    expect(element.querySelectorAll('.book-move')).toHaveLength(2);
    expect(element.querySelector('.book-move')?.getAttribute('title')).toContain('excluded');
    element.querySelector<HTMLElement>('.book-move')!.click();
    expect(selected).toHaveBeenCalledWith(0);
    expect(element.querySelectorAll('.quality-badge')).toHaveLength(0);
  });

  it('annotates errors with ?? / ? / ?! in their tone, right after the SAN', () => {
    const element = render([
      move(1, 'WHITE', 'e4', {
        judgement: null,
        centipawnLoss: 0,
        winChanceBefore: 0.5,
        winChanceAfter: 0.53,
        excluded: null,
      }),
      move(1, 'BLACK', 'f6', {
        judgement: 'INACCURACY',
        centipawnLoss: 60,
        winChanceBefore: 0.47,
        winChanceAfter: 0.41,
        excluded: null,
      }),
      move(2, 'WHITE', 'Qh5', {
        judgement: 'MISTAKE',
        centipawnLoss: 120,
        winChanceBefore: 0.6,
        winChanceAfter: 0.49,
        excluded: null,
      }),
      move(2, 'BLACK', 'g5', {
        judgement: 'BLUNDER',
        centipawnLoss: 1000,
        winChanceBefore: 0.4,
        winChanceAfter: 0,
        excluded: null,
      }),
    ]);

    const cells = Array.from(element.querySelectorAll<HTMLElement>('.move'));
    const badges = cells.map((cell) => cell.querySelector('.quality-badge'));

    expect(badges[0]).toBeNull();
    expect(cells[0].getAttribute('title')).toBeNull();
    expect(badges[1]?.textContent?.trim()).toBe('?!');
    expect(badges[1]?.classList).toContain('quality-inaccuracy');
    expect(badges[2]?.textContent?.trim()).toBe('?');
    expect(badges[2]?.classList).toContain('quality-mistake');
    expect(badges[3]?.textContent?.trim()).toBe('??');
    expect(badges[3]?.classList).toContain('quality-blunder');
    expect(badges[3]?.classList).toContain('quality-badge');
    expect(badges[3]?.getAttribute('aria-label')).toBe('Blunder');
    expect(cells[3].getAttribute('title')).toBe('Blunder: winning chances 40% → 0% (−10.0)');
    // No space between the move and its annotation, as in printed notation.
    expect(cells[3].textContent?.replace(/\s+$/, '')).toMatch(/g5\?\?$/);
  });

  it('marks a missed mate distinctly even without an ordinary quality error', () => {
    const element = render([
      move(1, 'WHITE', 'Qh5', {
        judgement: null,
        centipawnLoss: 0,
        winChanceBefore: 1,
        winChanceAfter: 0.976,
        excluded: null,
        missedMate: true,
        missedMateIn: 2,
      }),
    ]);
    const marker = element.querySelector('.missed-mate-badge');
    expect(marker?.textContent).toBe('M×');
    expect(marker?.getAttribute('title')).toBe('Missed mate in 2');
    expect(marker?.getAttribute('aria-label')).toBe('Missed mate in 2');
    expect(element.querySelector('.quality-badge')).toBeNull();
  });

  it('does not mark kept mates, overrides or forced moves', () => {
    const element = render([
      move(1, 'WHITE', 'Qh5', {
        judgement: null,
        centipawnLoss: null,
        winChanceBefore: 1,
        winChanceAfter: 0.5,
        excluded: 'OVERRIDE',
        missedMate: true,
        missedMateIn: 2,
      }),
      move(1, 'BLACK', 'Kh7', {
        judgement: null,
        centipawnLoss: null,
        winChanceBefore: 1,
        winChanceAfter: 0.5,
        excluded: 'FORCED',
        missedMate: true,
        missedMateIn: 2,
      }),
      move(2, 'WHITE', 'Qh6', {
        judgement: null,
        centipawnLoss: 0,
        winChanceBefore: 1,
        winChanceAfter: 1,
        excluded: null,
        missedMate: false,
      }),
    ]);
    expect(element.querySelector('.missed-mate-badge')).toBeNull();
  });

  it('shows no annotation before the evaluations are in, and a note for forced moves', () => {
    const element = render([
      move(1, 'WHITE', 'e4'),
      move(1, 'BLACK', 'Kh8', {
        judgement: null,
        centipawnLoss: null,
        winChanceBefore: 0.1,
        winChanceAfter: 0.1,
        excluded: 'FORCED',
      }),
    ]);

    const cells = Array.from(element.querySelectorAll<HTMLElement>('.move'));

    expect(element.querySelector('.quality-badge')).toBeNull();
    expect(cells[0].getAttribute('title')).toBeNull();
    expect(cells[1].getAttribute('title')).toBe('Only legal move: not judged');
  });
});

describe('MoveListComponent keyboard access', () => {
  it('renders each move as a native button named by its notation', () => {
    const fixture = TestBed.createComponent(MoveListComponent);
    fixture.componentRef.setInput('moves', [
      move(1, 'WHITE', 'e4'),
      move(1, 'BLACK', 'e5', {
        judgement: 'MISTAKE',
        centipawnLoss: 150,
        winChanceBefore: 0.5,
        winChanceAfter: 0.35,
        excluded: null,
      }),
      { ...move(2, 'WHITE', 'Nf3'), isBook: true },
    ]);
    fixture.componentRef.setInput('currentIndex', 1);
    const selected = vi.fn();
    fixture.componentInstance.moveSelected.subscribe(selected);
    fixture.detectChanges();
    const element: HTMLElement = fixture.nativeElement;

    const buttons = Array.from(element.querySelectorAll<HTMLElement>('.move'));
    expect(buttons.map((button) => button.tagName)).toEqual(['BUTTON', 'BUTTON', 'BUTTON']);
    expect(buttons.map((button) => (button as HTMLButtonElement).type)).toEqual([
      'button',
      'button',
      'button',
    ]);
    expect(buttons[0].getAttribute('aria-label')).toBe('1. e4');
    expect(buttons[1].getAttribute('aria-label')).toBe('1... e5, mistake');
    expect(buttons[2].getAttribute('aria-label')).toBe('2. Nf3, book move');
    expect(buttons.map((button) => button.getAttribute('aria-current'))).toEqual([
      null,
      'step',
      null,
    ]);

    buttons[2].click();
    expect(selected).toHaveBeenCalledWith(2);
  });
});

describe('MoveListComponent roving focus', () => {
  function renderList(count: number, currentIndex: number) {
    const sans = ['e4', 'e5', 'Nf3', 'Nc6', 'Bb5', 'a6'];
    const moves = Array.from({ length: count }, (_, i) =>
      move(Math.floor(i / 2) + 1, i % 2 ? 'BLACK' : 'WHITE', sans[i % sans.length]),
    );
    const fixture = TestBed.createComponent(MoveListComponent);
    fixture.componentRef.setInput('moves', moves);
    fixture.componentRef.setInput('currentIndex', currentIndex);
    const selected = vi.fn();
    fixture.componentInstance.moveSelected.subscribe(selected);
    fixture.detectChanges();
    const element: HTMLElement = fixture.nativeElement;
    const buttons = () => Array.from(element.querySelectorAll<HTMLButtonElement>('.move'));
    const stops = () => buttons().map((button) => button.getAttribute('tabindex'));
    return { fixture, element, buttons, stops, selected, moves };
  }

  function key(target: Element, name: string) {
    target.dispatchEvent(
      new KeyboardEvent('keydown', { key: name, bubbles: true, cancelable: true }),
    );
  }

  it('has one tab stop, on the shown move (the first move before any is shown)', () => {
    const { element, stops } = renderList(4, 2);
    expect(stops()).toEqual(['-1', '-1', '0', '-1']);
    // Firefox would add the scroll container as an extra stop; the moves already scroll it.
    expect(element.querySelector('.move-list-scroll')?.getAttribute('tabindex')).toBe('-1');
    TestBed.resetTestingModule();
    expect(renderList(4, -1).stops()).toEqual(['0', '-1', '-1', '-1']);
  });

  it('moves focus by ply with Left/Right, by move with Up/Down and to the ends with Home/End', () => {
    const { fixture, buttons, stops, selected } = renderList(6, 2);
    const focused = () => (document.activeElement as HTMLElement).getAttribute('aria-label');
    buttons()[2].focus();

    key(buttons()[2], 'ArrowRight');
    expect(focused()).toBe('2... Nc6');
    key(document.activeElement!, 'ArrowUp');
    expect(focused()).toBe('1... e5');
    key(document.activeElement!, 'ArrowDown');
    expect(focused()).toBe('2... Nc6');
    key(document.activeElement!, 'ArrowLeft');
    expect(focused()).toBe('2. Nf3');
    key(document.activeElement!, 'End');
    expect(focused()).toBe('3... a6');
    key(document.activeElement!, 'ArrowRight');
    expect(focused()).toBe('3... a6');
    key(document.activeElement!, 'Home');
    expect(focused()).toBe('1. e4');
    fixture.detectChanges();
    expect(stops()).toEqual(['0', '-1', '-1', '-1', '-1', '-1']);
    // Arrow keys only move focus; Enter or Space (native button activation) shows a position.
    expect(selected).not.toHaveBeenCalled();
  });

  it('does not scroll a live ply into view while focus is inside the list', () => {
    // The list shows 0–40 px; every move sits below it at 90–110 px, so revealing one scrolls.
    const rects = vi
      .spyOn(HTMLElement.prototype, 'getBoundingClientRect')
      .mockImplementation(function (this: HTMLElement) {
        return this.classList.contains('move-list-scroll')
          ? new DOMRect(0, 0, 100, 40)
          : new DOMRect(0, 90, 100, 20);
      });
    try {
      const { fixture, element, buttons, moves } = renderList(4, -1);
      const list = element.querySelector<HTMLElement>('.move-list-scroll')!;
      buttons()[0].focus();

      fixture.componentRef.setInput('moves', [...moves, move(3, 'WHITE', 'Bb5')]);
      fixture.componentRef.setInput('currentIndex', 4);
      fixture.detectChanges();
      expect(list.scrollTop).toBe(0);
      expect(document.activeElement).toBe(buttons()[0]);

      // Focus elsewhere (following the game): the next shown ply is revealed again.
      buttons()[0].blur();
      fixture.componentRef.setInput('currentIndex', 3);
      fixture.detectChanges();
      expect(list.scrollTop).toBe(70);
    } finally {
      rects.mockRestore();
    }
  });

  it('leaves modified arrow keys (Alt+Left is browser Back) to the browser', () => {
    const { buttons } = renderList(4, 1);
    buttons()[1].focus();
    for (const modifier of ['altKey', 'ctrlKey', 'metaKey', 'shiftKey']) {
      const event = new KeyboardEvent('keydown', {
        key: 'ArrowLeft',
        [modifier]: true,
        bubbles: true,
        cancelable: true,
      });
      buttons()[1].dispatchEvent(event);
      expect(event.defaultPrevented).toBe(false);
      expect(document.activeElement).toBe(buttons()[1]);
    }
  });
});
