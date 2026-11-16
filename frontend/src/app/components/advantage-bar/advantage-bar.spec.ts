import { TestBed } from '@angular/core/testing';
import { AdvantageBar } from './advantage-bar';

function render(inputs: { cp?: number | null; mate?: number | null; checkmated?: string | null }) {
  const fixture = TestBed.createComponent(AdvantageBar);
  Object.entries(inputs).forEach(([name, value]) => fixture.componentRef.setInput(name, value));
  fixture.detectChanges();
  const element: HTMLElement = fixture.nativeElement;
  return {
    bar: element.querySelector('.eval-bar') as HTMLElement,
    text: element.querySelector('.eval-text')?.textContent?.trim(),
  };
}

describe('AdvantageBar', () => {
  it('marks a missing evaluation as unknown instead of showing 0.0', () => {
    const { bar, text } = render({ cp: null, mate: null });

    expect(text).toBe('–');
    expect(bar.classList).toContain('unknown');
    expect(bar.classList).not.toContain('checkmate');
  });

  it('shows a stored 0.0 as a known score', () => {
    const { bar, text } = render({ cp: 0, mate: null });

    expect(text).toBe('0.0');
    expect(bar.classList).not.toContain('unknown');
  });

  it('shows a delivered checkmate as the result', () => {
    const { bar, text } = render({ cp: null, mate: 0, checkmated: 'BLACK' });

    expect(text).toBe('1-0');
    expect(bar.classList).toContain('checkmate');
    expect(bar.classList).not.toContain('unknown');
  });

  it('exposes the bar as an image whose accessible name explains the unknown and checkmate states', () => {
    const unknown = render({ cp: null, mate: null }).bar;
    expect(unknown.getAttribute('role')).toBe('img');
    expect(accessibleName(unknown)).toBe('Evaluation unavailable');
    expect(unknown.getAttribute('title')).toBe('Evaluation unavailable');

    const whiteMated = render({ cp: null, mate: 0, checkmated: 'WHITE' }).bar;
    expect(whiteMated.getAttribute('role')).toBe('img');
    expect(accessibleName(whiteMated)).toBe('Black wins by checkmate');
    expect(whiteMated.getAttribute('title')).toBe('Black wins by checkmate');

    const blackMated = render({ cp: null, mate: 0, checkmated: 'BLACK' }).bar;
    expect(accessibleName(blackMated)).toBe('White wins by checkmate');
  });

  it('names a regular score for screen readers without adding a title', () => {
    const score = render({ cp: 42, mate: null }).bar;
    expect(score.getAttribute('role')).toBe('img');
    expect(accessibleName(score)).toBe('Evaluation: +0.4');
    expect(score.hasAttribute('title')).toBe(false);

    const mate = render({ cp: null, mate: -2 }).bar;
    expect(accessibleName(mate)).toBe('Evaluation: Black mates in 2');
  });
});

/**
 * Accessible name of an element whose role does not take its name from content (such as "img"),
 * following the accessible-name computation: aria-labelledby, then aria-label, then title.
 */
function accessibleName(element: HTMLElement): string {
  const labelledBy = element.getAttribute('aria-labelledby');
  if (labelledBy) {
    return labelledBy
      .split(/\s+/)
      .map((id) => element.ownerDocument.getElementById(id)?.textContent?.trim() ?? '')
      .join(' ');
  }
  const label = element.getAttribute('aria-label')?.trim();
  if (label) {
    return label;
  }
  return element.getAttribute('title')?.trim() ?? '';
}
