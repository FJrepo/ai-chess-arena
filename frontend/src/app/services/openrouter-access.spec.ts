import { of, throwError } from 'rxjs';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { OpenRouterAccess, ConnectOpenRouterDialog } from './openrouter-access';
import { OpenRouterClient, OpenRouterStatus } from './openrouter-client';
import { TournamentSetup } from '../pages/tournament-setup/tournament-setup';
import { TournamentList } from '../pages/tournament-list/tournament-list';
import { TournamentBracket } from '../pages/tournament-bracket/tournament-bracket';
import { ApiService } from './api.service';
import { ActivatedRoute, Router, provideRouter } from '@angular/router';
import { Game } from '../models/tournament.model';

const missing: OpenRouterStatus = {
  key: { source: 'none', maskedSuffix: null, lastValidatedAt: null, validation: 'none' },
  canManage: true,
};

function harness(status = missing) {
  const client = { status: vi.fn(() => of(status)) };
  const dialog = { open: vi.fn() };
  const snackbar = { open: vi.fn() };
  const access = new OpenRouterAccess(
    client as unknown as OpenRouterClient,
    dialog as unknown as MatDialog,
    snackbar as unknown as MatSnackBar,
  );
  return { client, dialog, snackbar, access };
}

describe('OpenRouter action prompts', () => {
  it('opens the shared dialog directly without a status preflight', () => {
    const { access, client, dialog } = harness();
    access.openConnectDialog();
    expect(client.status).not.toHaveBeenCalled();
    expect(dialog.open).toHaveBeenCalledWith(ConnectOpenRouterDialog, {
      width: '560px',
      maxWidth: '95vw',
    });
  });

  it('opens the shared prompt instead of performing a no-key action', () => {
    const { access, dialog } = harness();
    const action = vi.fn();
    access.withKey(action);
    expect(action).not.toHaveBeenCalled();
    expect(dialog.open).toHaveBeenCalledWith(ConnectOpenRouterDialog, expect.any(Object));
  });

  it('permits configured keys and checks again for the next action', () => {
    const { access, client, dialog } = harness({
      ...missing,
      key: { ...missing.key, source: 'ui' },
    });
    const action = vi.fn();
    access.withKey(action);
    client.status.mockReturnValue(of(missing));
    access.withKey(action);
    expect(action).toHaveBeenCalledTimes(1);
    expect(dialog.open).toHaveBeenCalledTimes(1);
  });

  it.each(['env', 'file', 'ui'] as const)(
    'allows actions with an untested %s key without opening a connection prompt',
    (source) => {
      const { access, dialog } = harness({
        ...missing,
        key: { ...missing.key, source, validation: 'unknown' },
      });
      const action = vi.fn();
      access.withKey(action);
      expect(action).toHaveBeenCalledOnce();
      expect(dialog.open).not.toHaveBeenCalled();
    },
  );

  it('does not prompt connection for a present key whose last validation failed', () => {
    const { access, dialog } = harness({
      ...missing,
      key: { ...missing.key, source: 'env', validation: 'invalid' },
    });
    const action = vi.fn();
    access.withKey(action);
    expect(action).toHaveBeenCalledOnce();
    expect(dialog.open).not.toHaveBeenCalled();
  });

  it('fails closed when connection status cannot be loaded', () => {
    const { access, client, snackbar } = harness();
    client.status.mockReturnValue(throwError(() => new Error('offline')));
    const action = vi.fn();
    access.withKey(action);
    expect(action).not.toHaveBeenCalled();
    expect(snackbar.open).toHaveBeenCalled();
  });

  it('uses the prompt from setup Create without entering a submitting state', () => {
    const { access, dialog } = harness();
    const api = { createTournament: vi.fn() };
    const setup = new TournamentSetup(
      api as unknown as ApiService,
      {} as Router,
      {} as ActivatedRoute,
      access,
    );
    setup.create();
    expect(dialog.open).toHaveBeenCalled();
    expect(api.createTournament).not.toHaveBeenCalled();
    expect(setup.creating()).toBe(false);
  });

  it('uses the same prompt for bracket Run and Battle', () => {
    const { access, dialog } = harness();
    const api = { runTournament: vi.fn(), startGame: vi.fn() };
    const bracket = new TournamentBracket(
      {} as ActivatedRoute,
      {} as Router,
      api as unknown as ApiService,
      {} as MatSnackBar,
      access,
    );
    bracket.runTournament();
    bracket.startGame({ id: 'fake-game' } as Game);
    expect(dialog.open).toHaveBeenCalledTimes(2);
    expect(api.runTournament).not.toHaveBeenCalled();
    expect(api.startGame).not.toHaveBeenCalled();
    expect(bracket.runBusy()).toBe(false);
  });
});

describe.each([
  ['setup', TournamentSetup],
  ['list', TournamentList],
] as const)('%s connection banner', (_name, page) => {
  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
    TestBed.inject(MatDialog).closeAll();
    vi.unstubAllGlobals();
  });

  async function render(status: OpenRouterStatus | null) {
    vi.stubGlobal(
      'ResizeObserver',
      class {
        observe() {}
        unobserve() {}
        disconnect() {}
      },
    );
    TestBed.configureTestingModule({
      imports: [page],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: ApiService,
          useValue: {
            getTournaments: () => of([]),
            getSystemStatus: () =>
              status
                ? of({ key: status.key, stockfishAvailable: true })
                : throwError(() => new Error('offline')),
            checkOpenRouterStatus: () =>
              status ? of({ key: status.key }) : throwError(() => new Error('offline')),
            getPromptTemplate: () => of({ template: 'Rules', version: 'v2', hash: 'hash' }),
            getOpenings: () => of([]),
            getModels: () =>
              of({ data: [{ id: 'acme/model', name: 'Fixture', provider: 'acme' }] }),
          },
        },
      ],
    });
    const fixture = TestBed.createComponent<TournamentSetup | TournamentList>(page);
    fixture.detectChanges();
    await fixture.whenStable();
    return fixture;
  }

  it.each(['missing', 'invalid'] as const)(
    'opens the shared dialog from the %s-key banner and clears it immediately after saving',
    async (state) => {
      const initial: OpenRouterStatus =
        state === 'missing'
          ? missing
          : { ...missing, key: { ...missing.key, source: 'ui', validation: 'invalid' } };
      const fixture = await render(initial);
      const element: HTMLElement = fixture.nativeElement;
      const banner = element.querySelector<HTMLElement>('.openrouter-status')!;
      expect(banner.classList.contains(state === 'missing' ? 'info' : 'warn')).toBe(true);
      expect(banner.getAttribute('role')).toBe(state === 'missing' ? 'status' : 'alert');
      expect(banner.querySelector('mat-icon')!.textContent).toBe(
        state === 'missing' ? 'info' : 'warning',
      );
      const button = banner.querySelector('button')!;
      expect(button.textContent!.trim()).toBe(
        state === 'missing' ? 'Connect OpenRouter' : 'Fix connection',
      );
      if (state === 'missing') {
        expect(banner.textContent).toContain(
          'Connect your OpenRouter account to create and run tournaments.',
        );
      }
      const dialogs = TestBed.inject(MatDialog);
      button.click();
      fixture.detectChanges();
      const http = TestBed.inject(HttpTestingController);
      http.expectOne('/api/config/openrouter').flush(initial);
      fixture.detectChanges();
      await fixture.whenStable();
      expect(dialogs.openDialogs[0].componentInstance).toBeInstanceOf(ConnectOpenRouterDialog);

      const dialogElement = document.querySelector('app-connect-openrouter-dialog')!;
      const input = dialogElement.querySelector('input')!;
      const form = dialogElement.querySelector('form')!;
      input.value = 'fixture-private-key';
      input.dispatchEvent(new Event('input'));
      await fixture.whenStable();
      form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
      http.expectOne('/api/config/openrouter/key').flush({
        ...missing,
        key: { ...missing.key, source: 'ui', validation: 'valid' },
      });
      fixture.detectChanges();
      await fixture.whenStable();

      expect(element.querySelector('.openrouter-status')).toBeNull();
      expect(dialogs.openDialogs).toHaveLength(1);
    },
  );

  it.each(['valid', 'unknown', 'unavailable'] as const)(
    'shows no banner for a connected key with %s validation',
    async (validation) => {
      const fixture = await render({
        ...missing,
        key: { ...missing.key, source: 'ui', validation },
      });
      expect(fixture.nativeElement.querySelector('.openrouter-status')).toBeNull();
    },
  );

  it('shows no connection banner when status is unknown', async () => {
    const fixture = await render(null);
    expect(fixture.nativeElement.querySelector('.openrouter-status')).toBeNull();
  });
});
