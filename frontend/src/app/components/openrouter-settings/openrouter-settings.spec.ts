import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { OpenRouterSettings } from './openrouter-settings';
import { OpenRouterClient, OpenRouterStatus } from '../../services/openrouter-client';

const status: OpenRouterStatus = {
  key: { source: 'ui', maskedSuffix: '••••1234', lastValidatedAt: null, validation: 'valid' },
  canManage: true,
};

function client() {
  return {
    status: vi.fn(() => of(status)),
    manual: vi.fn(() => of(status)),
    delete: vi.fn(() => of({ ...status, key: { ...status.key, source: 'none' as const } })),
    test: vi.fn(() => of(status)),
  };
}

describe('OpenRouter settings', () => {
  it('shows an untested env key as connected even when its suffix is hidden', () => {
    const api = client();
    api.status.mockReturnValue(
      of({
        ...status,
        key: { source: 'env', maskedSuffix: null, lastValidatedAt: null, validation: 'unknown' },
      }),
    );
    TestBed.configureTestingModule({ providers: [{ provide: OpenRouterClient, useValue: api }] });
    const fixture = TestBed.createComponent(OpenRouterSettings);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Connected');
    expect(fixture.nativeElement.textContent).toContain('Validation: unknown');
    expect(fixture.nativeElement.textContent).not.toContain('Not connected');
    expect(fixture.nativeElement.textContent).not.toContain('Connect OpenRouter');
    expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeNull();
  });

  it('shows an invalid-key hint only for a failed validation, keeping the key connected', () => {
    const api = client();
    api.status.mockReturnValue(
      of({ ...status, key: { ...status.key, source: 'env', validation: 'invalid' } }),
    );
    TestBed.configureTestingModule({ providers: [{ provide: OpenRouterClient, useValue: api }] });
    const fixture = TestBed.createComponent(OpenRouterSettings);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain(
      'failed its last validation',
    );
    expect(fixture.nativeElement.textContent).not.toContain('Not connected');
    expect(fixture.nativeElement.textContent).not.toContain('Connect OpenRouter');
    fixture.componentInstance.status.set({
      ...status,
      key: { ...status.key, source: 'env', validation: 'unavailable' },
    });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Validation: unknown');
  });

  it('clears manual input immediately on both success and validation failure', () => {
    const api = client();
    const component = new OpenRouterSettings(api as unknown as OpenRouterClient);
    component.manualKey = 'fixture-private-key';
    component.save();
    expect(api.manual).toHaveBeenCalledWith('fixture-private-key');
    expect(component.manualKey).toBe('');
    api.manual.mockReturnValue(throwError(() => ({ error: { error: 'Rejected key' } })));
    component.manualKey = 'fixture-other-key';
    component.save();
    expect(component.manualKey).toBe('');
    expect(component.error()).toBe('Rejected key');
    expect(component.busy()).toBe(false);
  });

  it('renders masked status and write-only controls, with operator-managed controls hidden', async () => {
    // Material labels use ResizeObserver in the browser.
    vi.stubGlobal(
      'ResizeObserver',
      class {
        observe() {}
        unobserve() {}
        disconnect() {}
      },
    );
    const api = client();
    TestBed.configureTestingModule({ providers: [{ provide: OpenRouterClient, useValue: api }] });
    const fixture: ComponentFixture<OpenRouterSettings> =
      TestBed.createComponent(OpenRouterSettings);
    fixture.detectChanges();
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain('••••1234');
    expect(fixture.nativeElement.querySelector('input').type).toBe('password');
    expect(fixture.nativeElement.textContent).toContain('credit limit and expiration');
    fixture.componentInstance.status.set({ ...status, key: { ...status.key, source: 'env' } });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('input')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Operator-managed');
    vi.unstubAllGlobals();
  });

  it('shows claim instructions and hides key writes for another LAN browser', () => {
    const api = client();
    api.status.mockReturnValue(of({ ...status, canManage: false }));
    TestBed.configureTestingModule({ providers: [{ provide: OpenRouterClient, useValue: api }] });
    const fixture = TestBed.createComponent(OpenRouterSettings);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('one-time setup link');
    expect(fixture.nativeElement.querySelector('input')).toBeNull();
    expect(fixture.nativeElement.textContent).not.toContain('Validate and save');
    const testButton = fixture.nativeElement.querySelector('button');
    expect(testButton.disabled).toBe(true);
  });
});
