import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { convertToParamMap, ActivatedRoute, Router, provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';
import { OpenRouterClient } from '../../services/openrouter-client';
import { ConnectCallback } from './connect-callback';

function harness(params: Record<string, string>, path = 'connect/callback') {
  const client = {
    status: vi.fn(() => of({})),
    claim: vi.fn(() => of({})),
    complete: vi.fn(() => of({})),
    start: vi.fn(() => of({ authUrl: 'https://openrouter.ai/auth?fixture' })),
  };
  const route = { snapshot: { queryParamMap: convertToParamMap(params), routeConfig: { path } } };
  const router = { navigateByUrl: vi.fn(() => Promise.resolve(true)) };
  const snackbar = { open: vi.fn() };
  const page = new ConnectCallback(
    route as unknown as ActivatedRoute,
    client as unknown as OpenRouterClient,
    router as unknown as Router,
    snackbar as unknown as MatSnackBar,
  );
  return { client, page, router, snackbar, route };
}

describe('OpenRouter callback and claim', () => {
  beforeEach(() => sessionStorage.clear());
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    sessionStorage.clear();
  });

  it('scrubs code and state before completing, then returns to the stored route with a snackbar', () => {
    const { client, page, router, snackbar } = harness({ code: 'fake-code', state: 'fake-state' });
    const url = '/tournaments/fixture?tab=games#round-2';
    sessionStorage.setItem('openrouter-return-url', url);
    const scrub = vi.spyOn(window.history, 'replaceState');
    client.complete.mockImplementation(() => {
      expect(scrub).toHaveBeenCalledWith(window.history.state, '', window.location.pathname);
      return of({});
    });
    page.ngOnInit();
    expect(client.complete).toHaveBeenCalledWith('fake-code', 'fake-state');
    expect(router.navigateByUrl).toHaveBeenCalledWith(url, { replaceUrl: true });
    expect(snackbar.open).toHaveBeenCalledWith('OpenRouter connected', 'Dismiss', {
      duration: 5000,
    });
    expect(sessionStorage.getItem('openrouter-return-url')).toBeNull();
    expect(page.failed()).toBe(false);
  });

  it.each([null, 'https://example.com', '//example.com', '/\\example.com', 'tournaments'])(
    'returns to / for an absent or unsafe stored route %j',
    (url) => {
      const { page, router } = harness({ code: 'fake-code', state: 'fake-state' });
      if (url !== null) sessionStorage.setItem('openrouter-return-url', url);
      page.ngOnInit();
      expect(router.navigateByUrl).toHaveBeenCalledWith('/', { replaceUrl: true });
    },
  );

  it('does not redirect or consume the return route when completion arrives after destruction', () => {
    const { client, route, router, snackbar } = harness({ code: 'fake-code', state: 'fake-state' });
    const completion = new Subject<object>();
    client.complete.mockReturnValue(completion);
    sessionStorage.setItem('openrouter-return-url', '/analytics');
    TestBed.configureTestingModule({
      providers: [
        { provide: ActivatedRoute, useValue: route },
        { provide: OpenRouterClient, useValue: client },
        { provide: Router, useValue: router },
        { provide: MatSnackBar, useValue: snackbar },
      ],
    });
    const fixture = TestBed.createComponent(ConnectCallback);
    fixture.detectChanges();
    expect(client.complete).toHaveBeenCalledOnce();
    fixture.destroy();

    completion.next({});
    completion.complete();

    expect(router.navigateByUrl).not.toHaveBeenCalled();
    expect(snackbar.open).not.toHaveBeenCalled();
    expect(sessionStorage.getItem('openrouter-return-url')).toBe('/analytics');
    expect(fixture.componentInstance.message()).toBe('Finishing connection…');
  });

  it('keeps the return route on error and shows a styled fallback with retry and tournaments actions', () => {
    const { client, route, snackbar } = harness({ code: 'fake-code', state: 'fake-state' });
    client.complete.mockReturnValue(throwError(() => ({ error: { error: 'State expired' } })));
    sessionStorage.setItem('openrouter-return-url', '/tournaments/new');
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: ActivatedRoute, useValue: route },
        { provide: OpenRouterClient, useValue: client },
        { provide: MatSnackBar, useValue: snackbar },
      ],
    });
    const fixture = TestBed.createComponent(ConnectCallback);
    fixture.detectChanges();
    const element: HTMLElement = fixture.nativeElement;
    expect(element.querySelector('mat-card')?.getAttribute('appearance')).toBe('outlined');
    expect(element.querySelector('[role="alert"]')?.textContent?.trim()).toBe('State expired');
    expect(element.querySelector('button')?.textContent?.trim()).toBe('Try again');
    expect(element.querySelector('a')?.textContent?.trim()).toBe('Back to tournaments');
    expect(element.querySelector('a')?.getAttribute('href')).toBe('/tournaments');
    expect(sessionStorage.getItem('openrouter-return-url')).toBe('/tournaments/new');
    expect(snackbar.open).not.toHaveBeenCalled();
  });

  it('restarts OAuth on retry, preserving the original return route', () => {
    const { page, client } = harness({});
    const assign = vi.fn();
    vi.stubGlobal('window', { location: { assign } });
    sessionStorage.setItem('openrouter-return-url', '/tournaments/new');
    page.failed.set(true);
    page.retry();
    expect(client.start).toHaveBeenCalledOnce();
    expect(assign).toHaveBeenCalledWith('https://openrouter.ai/auth?fixture');
    expect(sessionStorage.getItem('openrouter-return-url')).toBe('/tournaments/new');
    expect(page.failed()).toBe(false);
  });

  it('shows an actionable error when retry cannot start', () => {
    const { page, client, router } = harness({});
    client.start.mockReturnValue(
      throwError(() => ({ error: { error: 'Connection unavailable' } })),
    );
    page.retry();
    expect(page.failed()).toBe(true);
    expect(page.message()).toBe('Connection unavailable');
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  it('gets a browser cookie before redeeming a setup token and handles replay errors', () => {
    const { client, page, router, snackbar } = harness({ token: 'fake-token' }, 'setup');
    client.claim.mockImplementation(() => {
      expect(client.status).toHaveBeenCalled();
      return throwError(() => ({ error: { error: 'Token already used' } }));
    });
    page.ngOnInit();
    expect(client.claim).toHaveBeenCalledWith('fake-token');
    expect(page.message()).toBe('Token already used');
    expect(page.failed()).toBe(true);
    expect(router.navigateByUrl).not.toHaveBeenCalled();
    expect(snackbar.open).not.toHaveBeenCalled();
  });

  it('preserves successful setup claiming and its settings action', () => {
    const { page, client, router } = harness({ token: 'fake-token' }, 'setup');
    page.ngOnInit();
    expect(client.claim).toHaveBeenCalledWith('fake-token');
    expect(page.claimed()).toBe(true);
    expect(page.message()).toBe('Instance claimed. Open settings to connect OpenRouter.');
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  it('rejects missing setup tokens without calling the backend', () => {
    const { page, client } = harness({}, 'setup');
    page.ngOnInit();
    expect(client.claim).not.toHaveBeenCalled();
    expect(page.message()).toContain('setup token is required');
    expect(page.failed()).toBe(true);
  });

  it('does not exchange or navigate away from an incomplete or cancelled callback', () => {
    const { client, page, router } = harness({ code: 'fake-code' });
    page.ngOnInit();
    expect(client.complete).not.toHaveBeenCalled();
    expect(router.navigateByUrl).not.toHaveBeenCalled();
    expect(page.message()).toContain('cancelled');
    expect(page.failed()).toBe(true);
  });
});
