import { convertToParamMap, ActivatedRoute } from '@angular/router';
import { of, throwError } from 'rxjs';
import { OpenRouterClient } from '../../services/openrouter-client';
import { ConnectCallback } from './connect-callback';

function harness(params: Record<string, string>, path = 'connect/callback') {
  const client = {
    status: vi.fn(() => of({})),
    claim: vi.fn(() => of({})),
    complete: vi.fn(() => of({})),
  };
  const route = { snapshot: { queryParamMap: convertToParamMap(params), routeConfig: { path } } };
  const page = new ConnectCallback(
    route as unknown as ActivatedRoute,
    client as unknown as OpenRouterClient,
  );
  return { client, page };
}

describe('OpenRouter callback and claim', () => {
  afterEach(() => vi.restoreAllMocks());

  it('removes code and state from history before completing with the backend', () => {
    const { client, page } = harness({ code: 'fake-code', state: 'fake-state' });
    const scrub = vi.spyOn(window.history, 'replaceState');
    client.complete.mockImplementation(() => {
      expect(scrub).toHaveBeenCalled();
      return of({});
    });
    page.ngOnInit();
    expect(client.complete).toHaveBeenCalledWith('fake-code', 'fake-state');
    expect(page.message()).toContain('connected');
  });

  it('gets a browser cookie before redeeming a setup token and handles replay errors', () => {
    const { client, page } = harness({ token: 'fake-token' }, 'setup');
    client.claim.mockReturnValue(throwError(() => ({ error: { error: 'Token already used' } })));
    page.ngOnInit();
    expect(client.status).toHaveBeenCalled();
    expect(client.claim).toHaveBeenCalledWith('fake-token');
    expect(page.message()).toBe('Token already used');
  });

  it('does not exchange an incomplete or cancelled callback', () => {
    const { client, page } = harness({ code: 'fake-code' });
    page.ngOnInit();
    expect(client.complete).not.toHaveBeenCalled();
    expect(page.message()).toContain('cancelled');
  });
});
