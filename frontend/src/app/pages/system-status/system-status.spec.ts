import { Observable, Subject, of, throwError } from 'rxjs';
import { SystemStatus } from './system-status';
import { ApiService, SystemStatusResponse } from '../../services/api.service';

describe('SystemStatus', () => {
  it('exposes healthy status details from the backend response', () => {
    const component = new SystemStatus(createApiService(() => of(healthyStatus())));

    component.ngOnInit();

    expect(component.backendReachable()).toBe(true);
    expect(component.backendVersion()).toBe('0.2.1');
    expect(component.openRouterSummary()).toContain('validated successfully');
    expect(component.stockfishSummary()).toContain('ready for live evaluations');
    expect(component.openRouterTone()).toBe('healthy');
    expect(
      component.dependencyLabel(component.openRouterTone(), 'Configured', 'Needs attention'),
    ).toBe('Configured');
    expect(component.loading()).toBe(false);
  });

  it('reports a failed dependency as degraded when the backend answers', () => {
    const component = new SystemStatus(
      createApiService(() =>
        of({
          ...healthyStatus(),
          openRouterValid: false,
          key: { ...healthyStatus().key, validation: 'invalid' },
          stockfishAvailable: false,
        }),
      ),
    );

    component.ngOnInit();

    expect(component.openRouterTone()).toBe('degraded');
    expect(component.stockfishTone()).toBe('degraded');
    expect(
      component.dependencyLabel(component.stockfishTone(), 'Eval online', 'Eval unavailable'),
    ).toBe('Eval unavailable');
  });

  it('shows an untested configured key as unknown without claiming it is invalid', () => {
    const status = healthyStatus();
    status.openRouterValid = false;
    status.key = { ...status.key, validation: 'unknown', lastValidatedAt: null };
    const component = new SystemStatus(createApiService(() => of(status)));
    component.refresh();
    expect(component.openRouterTone()).toBe('unknown');
    expect(component.openRouterSummary()).toContain('has not been checked');
    expect(component.openRouterSummary()).toContain('Test key');
  });

  it('reports no key as needing a connection', () => {
    const status = healthyStatus();
    status.openRouterValid = false;
    status.key = { source: 'none', maskedSuffix: null, validation: 'none', lastValidatedAt: null };
    const component = new SystemStatus(createApiService(() => of(status)));
    component.refresh();
    expect(component.openRouterTone()).toBe('degraded');
    expect(component.openRouterSummary()).toContain('Connect OpenRouter');
  });

  it('surfaces backend status fetch failures clearly', () => {
    const component = new SystemStatus(createApiService(() => throwError(() => new Error('boom'))));

    component.ngOnInit();

    expect(component.backendReachable()).toBe(false);
    expect(component.error()).toBe('Backend system status is unavailable.');
  });

  it('stops loading after an error so the page can be refreshed again', () => {
    const component = new SystemStatus(createApiService(() => throwError(() => new Error('boom'))));

    component.refresh();

    expect(component.loading()).toBe(false);
  });

  it('shows OpenRouter and Stockfish as unknown while the backend is unreachable', () => {
    let fail = false;
    const component = new SystemStatus(
      createApiService(() => (fail ? throwError(() => new Error('down')) : of(healthyStatus()))),
    );
    component.refresh();
    expect(component.openRouterTone()).toBe('healthy');

    fail = true;
    component.refresh();

    expect(component.openRouterTone()).toBe('unknown');
    expect(component.stockfishTone()).toBe('unknown');
    expect(
      component.dependencyLabel(component.openRouterTone(), 'Configured', 'Needs attention'),
    ).toBe('Unknown');
    expect(
      component.dependencyLabel(component.stockfishTone(), 'Eval online', 'Eval unavailable'),
    ).toBe('Unknown');
    expect(component.openRouterSummary()).toContain('unknown');
    expect(component.stockfishSummary()).toContain('unknown');
  });

  it('is loading while the request is pending', () => {
    const pending = new Subject<SystemStatusResponse>();
    const component = new SystemStatus(createApiService(() => pending));

    component.refresh();
    expect(component.loading()).toBe(true);

    pending.next(healthyStatus());
    pending.complete();
    expect(component.loading()).toBe(false);
  });
});

function healthyStatus(): SystemStatusResponse {
  return {
    backendVersion: '0.2.1',
    openRouterValid: true,
    key: {
      source: 'ui',
      maskedSuffix: '••••-key',
      validation: 'valid',
      lastValidatedAt: '2026-03-06T18:00:00Z',
    },
    stockfishAvailable: true,
    stockfishReason: null,
    checkedAt: '2026-03-06T18:00:00Z',
  };
}

function createApiService(getSystemStatus: () => Observable<SystemStatusResponse>): ApiService {
  return { getSystemStatus } as unknown as ApiService;
}
