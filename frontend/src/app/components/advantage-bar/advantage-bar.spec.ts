import { TestBed } from '@angular/core/testing';
import { AdvantageBar } from './advantage-bar';

function render(inputs: {
  cp?: number | null;
  mate?: number | null;
  checkmated?: string | null;
  flipped?: boolean;
}) {
  const fixture = TestBed.createComponent(AdvantageBar);
  Object.entries(inputs).forEach(([name, value]) => fixture.componentRef.setInput(name, value));
  fixture.detectChanges();
  const element: HTMLElement = fixture.nativeElement;
  return {
    bar: element.querySelector('.eval-bar') as HTMLElement,
    label: element.querySelector('.eval-text') as HTMLElement,
    text: element.querySelector('.eval-text')?.textContent?.trim(),
  };
}

/** The component's compiled styles, whitespace collapsed. */
function componentStyles(): string {
  return ((AdvantageBar as unknown as { ɵcmp: { styles: string[] } }).ɵcmp.styles ?? [])
    .join('\n')
    .replace(/\s+/g, ' ');
}

/** The declarations of the first compiled rule whose selector list matches. */
function rule(selector: RegExp): string {
  return componentStyles().match(new RegExp(`${selector.source}[^{]*\\{([^}]*)\\}`))?.[1] ?? '';
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

  it.each([
    [{ cp: 12340 }, '+123.4', 'Evaluation: +123.4', null],
    [{ cp: -12340 }, '-123.4', 'Evaluation: -123.4', null],
    [{ cp: 100000 }, '+1.0e3', 'Evaluation: +1000.0', 'Evaluation: +1000.0'],
    [{ cp: -2147483648 }, '-2.1e7', 'Evaluation: -21474836.5', 'Evaluation: -21474836.5'],
    [
      { mate: -12345 },
      '-M1e4',
      'Evaluation: Black mates in 12345',
      'Evaluation: Black mates in 12345',
    ],
  ] as const)(
    'fits the score for %j and keeps its full accessible value',
    (inputs, visible, exact, title) => {
      const { bar, text } = render(inputs);
      expect(text).toBe(visible);
      expect(accessibleName(bar)).toBe(exact);
      expect(bar.getAttribute('title')).toBe(title);
    },
  );

  it("puts the score at the leading side's end of the bar", () => {
    const white = render({ cp: 900 });
    expect(white.label.classList).toContain('white-end');
    expect(white.label.classList).not.toContain('black-end');
    expect(white.bar.classList).not.toContain('flipped');

    const black = render({ cp: -250 });
    expect(black.label.classList).toContain('black-end');
    expect(black.label.classList).not.toContain('white-end');

    // White's end is the bottom; on a flipped board (Black at the bottom) it is the top.
    expect(rule(/\.eval-text\.white-end/)).toMatch(/bottom: ?var\(--app-space-2\)/);
    expect(rule(/\.eval-text\.black-end/)).toMatch(/top: ?var\(--app-space-2\)/);
    const flipped = render({ cp: 900, flipped: true });
    expect(flipped.bar.classList).toContain('flipped');
    expect(flipped.label.classList).toContain('white-end');
    expect(rule(/\.eval-bar\.flipped\S* \.eval-text\.white-end/)).toMatch(
      /top: ?var\(--app-space-2\);? ?bottom: ?auto/,
    );
    expect(rule(/\.eval-bar\.flipped\S* \.eval-text\.black-end/)).toMatch(
      /top: ?auto;? ?bottom: ?var\(--app-space-2\)/,
    );
  });

  it('labels the score at 11 px or more with tabular numbers', () => {
    expect(rule(/\.advantage-container/)).toMatch(
      /font: ?800 0\.6875rem var\(--app-font-numeric\)/,
    );
    expect(rule(/\.advantage-container/)).toMatch(/font-variant-numeric: ?tabular-nums/);
  });

  it("draws White's share and its label with the evaluation token, softer in dark mode", () => {
    expect(rule(/\.white-bar/)).toMatch(/background: ?var\(--app-eval-white\)/);
    expect(rule(/\.eval-text\.white-end/)).toMatch(/background: ?var\(--app-eval-white\)/);
    expect(componentStyles()).not.toMatch(/background: ?var\(--app-piece-white\)/);
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
