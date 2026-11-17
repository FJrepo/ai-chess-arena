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
