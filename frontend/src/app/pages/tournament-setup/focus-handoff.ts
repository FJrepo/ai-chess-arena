import { DestroyRef, Injector } from '@angular/core';

/**
 * Start tracking while the origin is focused. Leaving a connected, enabled origin cancels
 * the handoff, even when focus goes to the body. Removal or disabling can leave focus on
 * the body (or without an active element) and still allow the move if the origin owned it.
 * The returned function stops tracking and returns the focused target on success.
 */
export function handoffFocus(
  origin: HTMLElement | undefined,
  injector?: Injector,
): (target: HTMLElement | undefined) => HTMLElement | undefined {
  if (!origin || origin.ownerDocument.activeElement !== origin) return () => undefined;

  const document = origin.ownerDocument;
  let ownsFocus = true;
  const originCanKeepFocus = () => origin.isConnected && !origin.matches(':disabled');
  const cancel = () => {
    if (!ownsFocus) return;
    ownsFocus = false;
    origin.removeEventListener('focusout', onFocusOut);
    unregisterDestroy?.();
  };
  const onFocusOut = (event: FocusEvent) => {
    if (event.relatedTarget) {
      cancel();
    } else {
      // Chromium fires focusout during removal, while the origin is still connected.
      queueMicrotask(() => {
        if (originCanKeepFocus()) cancel();
      });
    }
  };
  const unregisterDestroy = injector?.get(DestroyRef).onDestroy(cancel);
  origin.addEventListener('focusout', onFocusOut);

  return (target) => {
    const active = document.activeElement;
    const focusFellBack = active === document.body || active === null;
    const canMove = ownsFocus && (active === origin || (focusFellBack && !originCanKeepFocus()));
    cancel();
    if (!target || !canMove) return;

    target.focus();
    return document.activeElement === target ? target : undefined;
  };
}
