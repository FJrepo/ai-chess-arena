const storageKey = 'openrouter-return-url';

function safeReturnUrl(value: string | null): string {
  if (
    !value?.startsWith('/') ||
    value.startsWith('//') ||
    value.includes('\\') ||
    Array.from(value).some((character) => character.charCodeAt(0) < 32 || character === '\x7f')
  ) {
    return '/';
  }
  try {
    const url = new URL(value, window.location.origin);
    // Never retain setup tokens or authorization codes, or return to an exchange page.
    if (
      url.origin !== window.location.origin ||
      ['/setup', '/connect/callback'].includes(url.pathname)
    ) {
      return '/';
    }
    return value;
  } catch {
    return '/';
  }
}

export function rememberOpenRouterReturnUrl(url: string): void {
  try {
    sessionStorage.setItem(storageKey, safeReturnUrl(url));
  } catch {
    // Connecting still works when browser storage is unavailable.
  }
}

export function takeOpenRouterReturnUrl(): string {
  try {
    const url = sessionStorage.getItem(storageKey);
    sessionStorage.removeItem(storageKey);
    return safeReturnUrl(url);
  } catch {
    return '/';
  }
}
