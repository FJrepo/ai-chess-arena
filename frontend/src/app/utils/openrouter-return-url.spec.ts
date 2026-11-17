import { rememberOpenRouterReturnUrl, takeOpenRouterReturnUrl } from './openrouter-return-url';

describe('OpenRouter return route', () => {
  beforeEach(() => sessionStorage.clear());
  afterEach(() => vi.restoreAllMocks());

  it('stores only the route, preserves query and fragment and consumes it once', () => {
    const url = '/tournaments/fixture?tab=games#round-2';
    rememberOpenRouterReturnUrl(url);
    expect(sessionStorage.length).toBe(1);
    expect(takeOpenRouterReturnUrl()).toBe(url);
    expect(sessionStorage.length).toBe(0);
    expect(takeOpenRouterReturnUrl()).toBe('/');
  });

  it.each([
    'https://example.com/outside',
    '//example.com/outside',
    '///example.com/outside',
    '/\\example.com/outside',
    'javascript:alert(1)',
    'tournaments',
    '',
    '/\n/outside',
    '/setup?token=fixture-token',
    '/connect/callback?code=fixture-code&state=fixture-state',
  ])('rejects unsafe route %j when saving and when reading tampered storage', (url) => {
    rememberOpenRouterReturnUrl(url);
    expect(sessionStorage.getItem('openrouter-return-url')).toBe('/');
    sessionStorage.setItem('openrouter-return-url', url);
    expect(takeOpenRouterReturnUrl()).toBe('/');
    expect(sessionStorage.getItem('openrouter-return-url')).toBeNull();
  });

  it('continues with a safe fallback when session storage is blocked', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('Storage blocked');
    });
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('Storage blocked');
    });
    expect(() => rememberOpenRouterReturnUrl('/tournaments/new')).not.toThrow();
    expect(takeOpenRouterReturnUrl()).toBe('/');
  });
});
