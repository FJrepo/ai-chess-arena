/**
 * Check focus at the moment of each deferred move. The origin still owns focus if it is
 * active, or if its removal or disabling left focus on the body (or without an active element).
 * Return the newly focused element so it can own the next step of a handoff.
 */
export function handoffFocus(
  origin: HTMLElement | undefined,
  target: HTMLElement | undefined,
): HTMLElement | undefined {
  if (!origin || !target) return;

  const document = origin.ownerDocument;
  const active = document.activeElement;
  const focusFellBack = active === document.body || active === null;
  if (
    active === origin ||
    (focusFellBack && (!origin.isConnected || origin.matches(':disabled')))
  ) {
    target.focus();
    return document.activeElement === target ? target : undefined;
  }
  return undefined;
}
