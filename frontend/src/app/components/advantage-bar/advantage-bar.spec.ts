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

  it('explains the unknown and checkmate states in a title and an accessible label', () => {
    const unknown = render({ cp: null, mate: null }).bar;
    expect(unknown.getAttribute('title')).toBe('Evaluation unavailable');
    expect(unknown.getAttribute('aria-label')).toBe('Evaluation unavailable');

    const whiteMated = render({ cp: null, mate: 0, checkmated: 'WHITE' }).bar;
    expect(whiteMated.getAttribute('title')).toBe('Black wins by checkmate');
    expect(whiteMated.getAttribute('aria-label')).toBe('Black wins by checkmate');

    const blackMated = render({ cp: null, mate: 0, checkmated: 'BLACK' }).bar;
    expect(blackMated.getAttribute('aria-label')).toBe('White wins by checkmate');
  });

  it('adds no title or label to a regular score', () => {
    const { bar } = render({ cp: 42, mate: null });

    expect(bar.hasAttribute('title')).toBe(false);
    expect(bar.hasAttribute('aria-label')).toBe(false);
  });
});
