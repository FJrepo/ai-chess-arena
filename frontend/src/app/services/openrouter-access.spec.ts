import { of, throwError } from 'rxjs';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { OpenRouterAccess, ConnectOpenRouterDialog } from './openrouter-access';
import { OpenRouterClient, OpenRouterStatus } from './openrouter-client';
import { TournamentSetup } from '../pages/tournament-setup/tournament-setup';
import { TournamentBracket } from '../pages/tournament-bracket/tournament-bracket';
import { ApiService } from './api.service';
import { ActivatedRoute, Router } from '@angular/router';
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
