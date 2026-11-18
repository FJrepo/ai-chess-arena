import { handoffFocus } from './focus-handoff';

describe('handoffFocus', () => {
  let container: HTMLElement;
  let origin: HTMLButtonElement;
  let target: HTMLButtonElement;
  let displayName: HTMLInputElement;

  beforeEach(() => {
    container = document.createElement('div');
    origin = document.createElement('button');
    target = document.createElement('button');
    displayName = document.createElement('input');
    container.append(origin, target, displayName);
    document.body.append(container);
    origin.focus();
  });

  afterEach(() => {
    vi.restoreAllMocks();
    container.remove();
  });

  it('focuses the target when the origin is still focused', () => {
    expect(handoffFocus(origin)(target)).toBe(target);
    expect(document.activeElement).toBe(target);
  });

  it('focuses the target when the origin was removed and focus fell to body', () => {
    const moveFocus = handoffFocus(origin);
    origin.remove();
    expect(document.activeElement).toBe(document.body);

    expect(moveFocus(target)).toBe(target);
    expect(document.activeElement).toBe(target);
  });

  it('focuses the target when the origin was disabled and focus fell to body', () => {
    const moveFocus = handoffFocus(origin);
    origin.disabled = true;
    origin.blur();
    expect(document.activeElement).toBe(document.body);

    expect(moveFocus(target)).toBe(target);
    expect(document.activeElement).toBe(target);
  });

  it('focuses the target when a removed origin leaves no active element', () => {
    const moveFocus = handoffFocus(origin);
    origin.remove();
    vi.spyOn(document, 'activeElement', 'get').mockReturnValueOnce(null);

    expect(moveFocus(target)).toBe(target);
    expect(document.activeElement).toBe(target);
  });

  it('leaves focus on body when the origin is still connected and enabled', () => {
    const moveFocus = handoffFocus(origin);
    origin.blur();

    expect(moveFocus(target)).toBeUndefined();
    expect(document.activeElement).toBe(document.body);
  });

  it.each(['removed', 'disabled'] as const)(
    'cancels after the connected, enabled origin blurs and is later %s',
    async (state) => {
      const moveFocus = handoffFocus(origin);
      origin.blur();
      await Promise.resolve();
      expect(document.activeElement).toBe(document.body);

      if (state === 'removed') origin.remove();
      else origin.disabled = true;

      expect(moveFocus(target)).toBeUndefined();
      expect(document.activeElement).toBe(document.body);
    },
  );

  it.each(['connected', 'removed', 'disabled'] as const)(
    'preserves focus on Display Name when the origin is %s',
    (state) => {
      const moveFocus = handoffFocus(origin);
      displayName.focus();
      if (state === 'removed') origin.remove();
      if (state === 'disabled') origin.disabled = true;

      expect(moveFocus(target)).toBeUndefined();
      expect(document.activeElement).toBe(displayName);
    },
  );
});
