import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';
import { OpenRouterSettings } from './openrouter-settings';
import { OpenRouterClient, OpenRouterStatus } from '../../services/openrouter-client';

const status: OpenRouterStatus = {
  key: { source: 'ui', maskedSuffix: '••••1234', lastValidatedAt: null, validation: 'valid' },
  canManage: true,
};
const missing: OpenRouterStatus = {
  ...status,
  key: { source: 'none', maskedSuffix: null, lastValidatedAt: null, validation: 'none' },
};

function client() {
  return {
    status: vi.fn(() => of(status)),
    start: vi.fn(() => of({ authUrl: 'https://openrouter.ai/auth?fixture' })),
    manual: vi.fn(() => of(status)),
    delete: vi.fn(() => of(missing)),
    test: vi.fn(() => of(status)),
  };
}

function render(current = status) {
  const api = client();
  api.status.mockReturnValue(of(current));
  TestBed.configureTestingModule({
    providers: [provideRouter([]), { provide: OpenRouterClient, useValue: api }],
  });
  const fixture = TestBed.createComponent(OpenRouterSettings);
  fixture.detectChanges();
  return { api, fixture, element: fixture.nativeElement as HTMLElement };
}

describe('OpenRouter settings', () => {
  beforeEach(() => {
    vi.stubGlobal(
      'ResizeObserver',
      class {
        observe() {}
        unobserve() {}
        disconnect() {}
      },
    );
    sessionStorage.clear();
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    sessionStorage.clear();
  });

  it('makes Connect the only primary action with no Test key and manual entry collapsed', () => {
    const { element } = render(missing);
    const primary = element.querySelectorAll('button[mat-flat-button]');
    expect(primary).toHaveLength(1);
    expect(primary[0].textContent?.trim()).toBe('Connect OpenRouter');
    expect(element.textContent).toContain(
      'You approve on OpenRouter and can set a credit limit and expiration there.',
    );
    expect(element.textContent).not.toContain('Test key');
    expect(element.querySelector('input')).toBeNull();
    expect(element.querySelector('.manual-toggle')?.getAttribute('aria-expanded')).toBe('false');
    expect(element.querySelector('.manual-toggle')?.textContent?.trim()).toBe(
      'Use an existing API key instead',
    );
  });

  it('expands an outlined write-only field with its label, hint and separate save button', async () => {
    const { fixture, element, api } = render(missing);
    element.querySelector<HTMLButtonElement>('.manual-toggle')!.click();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(element.querySelector('.manual-toggle')?.getAttribute('aria-expanded')).toBe('true');
    expect(element.querySelector('mat-form-field')?.getAttribute('appearance')).toBe('outline');
    expect(element.querySelector('mat-label')?.textContent).toBe('OpenRouter API key');
    expect(element.querySelector('mat-hint')?.textContent).toBe(
      'Stored only on this instance; never shown again',
    );
    expect(element.querySelector('input')?.type).toBe('password');
    const save = element.querySelector<HTMLButtonElement>('form > button')!;
    expect(save.textContent?.trim()).toBe('Validate and save');
    expect(save.disabled).toBe(true);
    expect(api.manual).not.toHaveBeenCalled();
    element.querySelector<HTMLButtonElement>('.manual-toggle')!.click();
    fixture.detectChanges();
    expect(element.querySelector('input')).toBeNull();
  });

  it('shows connected status followed by Test, Replace and Delete, with manual entry collapsed', () => {
    const { element } = render();
    expect(element.querySelector('.connection-status')?.textContent).toContain('Connected');
    expect(element.textContent).toContain('••••1234');
    expect(
      Array.from(element.querySelectorAll('.connection-actions button'), (button) =>
        button.textContent?.trim(),
      ),
    ).toEqual(['Test key', 'Replace connection', 'Delete local key']);
    expect(element.querySelector('#openrouter-delete-hint')?.textContent).toContain(
      'Revoke the key on OpenRouter',
    );
    expect(element.querySelector('.manual-toggle')?.textContent?.trim()).toBe(
      'Use a different API key',
    );
    expect(element.querySelector('input')).toBeNull();
  });

  it.each([missing, status])(
    'clears a pasted manual key on collapse and reopens an empty field (source $key.source)',
    async (current) => {
      const { fixture, element, api } = render(current);
      const toggle = element.querySelector<HTMLButtonElement>('.manual-toggle')!;
      toggle.click();
      fixture.detectChanges();
      await fixture.whenStable();
      const input = element.querySelector('input')!;
      input.value = 'fixture-private-key';
      input.dispatchEvent(new Event('input'));
      await fixture.whenStable();
      expect(fixture.componentInstance.manualKey).toBe('fixture-private-key');

      toggle.click();
      fixture.detectChanges();
      expect(element.querySelector('input')).toBeNull();
      expect(fixture.componentInstance.manualKey).toBe('');
      toggle.click();
      fixture.detectChanges();
      await fixture.whenStable();

      expect(element.querySelector('input')!.value).toBe('');
      expect(element.querySelector<HTMLButtonElement>('form > button')!.disabled).toBe(true);
      expect(api.manual).not.toHaveBeenCalled();
    },
  );

  it('clears abandoned manual input when settings are destroyed', () => {
    const { fixture } = render(missing);
    fixture.componentInstance.manualExpanded.set(true);
    fixture.componentInstance.manualKey = 'fixture-private-key';
    fixture.destroy();
    expect(fixture.componentInstance.manualKey).toBe('');
  });

  it('clears and collapses manual entry when switching to OAuth, even if starting fails', () => {
    const { fixture, api, element } = render(missing);
    const start = new Subject<{ authUrl: string }>();
    api.start.mockReturnValue(start);
    fixture.componentInstance.manualExpanded.set(true);
    fixture.componentInstance.manualKey = 'fixture-private-key';
    fixture.componentInstance.connect();
    expect(fixture.componentInstance.manualKey).toBe('');
    expect(fixture.componentInstance.manualExpanded()).toBe(false);
    start.error({ error: { error: 'Connection unavailable' } });
    fixture.detectChanges();
    expect(element.querySelector('input')).toBeNull();
    expect(fixture.componentInstance.error()).toBe('Connection unavailable');
  });

  it.each(['env', 'file'] as const)(
    'keeps an untested %s key read-only with Test as the only action',
    (source) => {
      const { element } = render({
        ...status,
        key: { source, maskedSuffix: null, lastValidatedAt: null, validation: 'unknown' },
      });
      expect(element.textContent).toContain('Connected');
      expect(element.textContent).toContain('Validation: unknown');
      expect(element.textContent).toContain('Operator-managed key');
      expect(element.textContent).not.toContain('Not connected');
      expect(element.querySelector('input')).toBeNull();
      const buttons = element.querySelectorAll('button');
      expect(buttons).toHaveLength(1);
      expect(buttons[0].textContent?.trim()).toBe('Test key');
      expect(buttons[0].disabled).toBe(false);
      expect(element.querySelector('[role="alert"]')).toBeNull();
    },
  );

  it('shows an invalid-key hint only for a failed validation, keeping the key connected', () => {
    const { fixture, element } = render({
      ...status,
      key: { ...status.key, source: 'env', validation: 'invalid' },
    });
    expect(element.querySelector('[role="alert"]')?.textContent).toContain(
      'failed its last validation',
    );
    expect(element.textContent).not.toContain('Not connected');
    expect(element.textContent).not.toContain('Connect OpenRouter');
    fixture.componentInstance.status.set({
      ...status,
      key: { ...status.key, source: 'env', validation: 'unavailable' },
    });
    fixture.detectChanges();
    expect(element.querySelector('[role="alert"]')).toBeNull();
    expect(element.textContent).toContain('Validation: unknown');
  });

  it('clears manual input immediately on both success and validation failure', () => {
    const api = client();
    const component = new OpenRouterSettings(api as unknown as OpenRouterClient, {} as Router);
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

  it('remembers the current route before leaving for OpenRouter', () => {
    const api = client();
    const url = '/tournaments/fixture?tab=games#round-2';
    const assign = vi.fn(() => {
      expect(sessionStorage.getItem('openrouter-return-url')).toBe(url);
    });
    vi.stubGlobal('window', { location: { origin: window.location.origin, assign } });
    const component = new OpenRouterSettings(api as unknown as OpenRouterClient, { url } as Router);
    component.connect();
    expect(api.start).toHaveBeenCalledOnce();
    expect(assign).toHaveBeenCalledWith('https://openrouter.ai/auth?fixture');
  });

  it('reports a start failure without changing the remembered route', () => {
    const api = client();
    api.start.mockReturnValue(throwError(() => ({ error: { error: 'Connection unavailable' } })));
    sessionStorage.setItem('openrouter-return-url', '/analytics');
    const component = new OpenRouterSettings(api as unknown as OpenRouterClient, {} as Router);
    component.connect();
    expect(component.error()).toBe('Connection unavailable');
    expect(component.busy()).toBe(false);
    expect(sessionStorage.getItem('openrouter-return-url')).toBe('/analytics');
  });

  it('shows claim instructions and hides key writes for another LAN browser', () => {
    const { element } = render({ ...status, canManage: false });
    expect(element.textContent).toContain('one-time setup link');
    expect(element.querySelector('input')).toBeNull();
    expect(element.textContent).not.toContain('Validate and save');
    const buttons = element.querySelectorAll('button');
    expect(buttons).toHaveLength(1);
    expect(buttons[0].disabled).toBe(true);
  });
});
