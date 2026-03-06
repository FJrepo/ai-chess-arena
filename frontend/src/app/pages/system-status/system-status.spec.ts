import { of, throwError } from 'rxjs';
import { SystemStatus } from './system-status';
import { ApiService, SystemStatusResponse } from '../../services/api.service';

describe('SystemStatus', () => {
  it('exposes healthy status details from the backend response', () => {
    const component = new SystemStatus(
      createApiService({
        backendVersion: '0.2.1',
        openRouterValid: true,
        stockfishAvailable: true,
        stockfishReason: null,
        checkedAt: '2026-03-06T18:00:00Z',
      }),
    );

    component.ngOnInit();

    expect(component.backendReachable()).toBe(true);
    expect(component.backendVersion()).toBe('0.2.1');
    expect(component.openRouterSummary()).toContain('validated successfully');
    expect(component.stockfishSummary()).toContain('ready for live evaluations');
  });

  it('surfaces backend status fetch failures clearly', () => {
    const component = new SystemStatus(createFailingApiService());

    component.ngOnInit();

    expect(component.backendReachable()).toBe(false);
    expect(component.error()).toBe('Backend system status is unavailable.');
  });
});

function createApiService(status: SystemStatusResponse): ApiService {
  return {
    getSystemStatus: () => of(status),
  } as unknown as ApiService;
}

function createFailingApiService(): ApiService {
  return {
    getSystemStatus: () => throwError(() => new Error('boom')),
  } as unknown as ApiService;
}
