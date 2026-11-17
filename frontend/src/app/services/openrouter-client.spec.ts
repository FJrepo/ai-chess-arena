import { HttpClient } from '@angular/common/http';
import { Subject, of, throwError } from 'rxjs';
import { OpenRouterClient, OpenRouterStatus } from './openrouter-client';

const status: OpenRouterStatus = {
  key: { source: 'none', maskedSuffix: null, lastValidatedAt: null, validation: 'none' },
  canManage: true,
};

it('shares concurrent initial status requests to avoid competing browser cookies, then reads fresh status', () => {
  const response = new Subject<OpenRouterStatus>();
  const http = { get: vi.fn(() => response) };
  const client = new OpenRouterClient(http as unknown as HttpClient);
  const app = vi.fn();
  const route = vi.fn();
  client.status().subscribe(app);
  client.status().subscribe(route);
  expect(http.get).toHaveBeenCalledTimes(1);
  response.next(status);
  response.complete();
  expect(app).toHaveBeenCalledWith(status);
  expect(route).toHaveBeenCalledWith(status);
  expect(client.keyStatus()).toEqual(status.key);
  client.status().subscribe();
  expect(http.get).toHaveBeenCalledTimes(2);
});

it.each([
  ['manual save', (client: OpenRouterClient) => client.manual('fixture-private-key')],
  ['callback', (client: OpenRouterClient) => client.complete('fixture-code', 'fixture-state')],
  ['test', (client: OpenRouterClient) => client.test()],
  ['delete', (client: OpenRouterClient) => client.delete()],
  ['claim', (client: OpenRouterClient) => client.claim('fixture-token')],
] as const)('publishes the latest key metadata after %s', (operation, request) => {
  const response = new Subject<OpenRouterStatus>();
  const initial: OpenRouterStatus = {
    ...status,
    key: { ...status.key, source: 'ui', validation: 'unknown' },
  };
  const http = { get: () => of(initial), post: () => response, delete: () => response };
  const client = new OpenRouterClient(http as unknown as HttpClient);
  client.status().subscribe();
  request(client).subscribe();
  const next: OpenRouterStatus = {
    ...status,
    key: operation === 'delete' ? status.key : { ...status.key, source: 'ui', validation: 'valid' },
  };
  response.next(next);
  expect(client.keyStatus()).toEqual(next.key);
});

it('preserves the last known key metadata when a save fails', () => {
  const http = {
    get: () => of(status),
    post: () => throwError(() => new Error('Rejected fixture')),
  };
  const client = new OpenRouterClient(http as unknown as HttpClient);
  client.status().subscribe();
  client.manual('fixture-private-key').subscribe({ error: () => {} });
  expect(client.keyStatus()).toEqual(status.key);
});

it.each([
  ['callback', (client: OpenRouterClient) => client.complete('fixture-code', 'fixture-state')],
  ['manual save', (client: OpenRouterClient) => client.manual('fixture-private-key')],
  ['delete', (client: OpenRouterClient) => client.delete()],
  ['test', (client: OpenRouterClient) => client.test()],
  ['claim', (client: OpenRouterClient) => client.claim('fixture-token')],
] as const)(
  'preserves a successful %s when the settings status GET resolves afterwards',
  (operation, mutate) => {
    const pendingStatus = new Subject<OpenRouterStatus>();
    const http = {
      get: () => pendingStatus,
      post: () => of(next),
      delete: () => of(next),
    };
    const client = new OpenRouterClient(http as unknown as HttpClient);
    const connected: OpenRouterStatus = {
      ...status,
      key: { ...status.key, source: 'ui', validation: 'valid' },
    };
    const initial = operation === 'delete' || operation === 'test' ? connected : status;
    const next: OpenRouterStatus =
      operation === 'delete'
        ? status
        : operation === 'test'
          ? { ...connected, key: { ...connected.key, validation: 'invalid' } }
          : connected;
    client.reconcileKeyStatus(initial.key);
    const received = vi.fn();
    client.status().subscribe(received);
    mutate(client).subscribe();
    expect(client.keyStatus()).toEqual(next.key);

    pendingStatus.next(initial);
    pendingStatus.complete();
    expect(received).toHaveBeenCalledExactlyOnceWith(initial);
    expect(client.keyStatus()).toEqual(next.key);
  },
);

it.each(['manual', 'callback'] as const)(
  'notifies an open dialog only after a successful %s connection, with metadata already updated',
  (operation) => {
    const response = new Subject<OpenRouterStatus>();
    const http = { post: () => response };
    const client = new OpenRouterClient(http as unknown as HttpClient);
    const connected: OpenRouterStatus = {
      ...status,
      key: { ...status.key, source: 'ui', validation: 'valid' },
    };
    const saved = vi.fn(() => expect(client.keyStatus()).toEqual(connected.key));
    client.connectionSaved.subscribe(saved);
    const request =
      operation === 'manual'
        ? client.manual('fixture-private-key')
        : client.complete('fixture-code', 'fixture-state');
    request.subscribe();
    expect(saved).not.toHaveBeenCalled();
    response.next(connected);
    expect(saved).toHaveBeenCalledExactlyOnceWith(undefined);
  },
);

it('keeps a dialog open on failed connections, status loads, tests, claiming and deletion', () => {
  const connected: OpenRouterStatus = { ...status, key: { ...status.key, source: 'ui' } };
  const http = {
    get: () => of(connected),
    post: vi.fn(() => of(connected)),
    delete: () => of(status),
  };
  const client = new OpenRouterClient(http as unknown as HttpClient);
  const saved = vi.fn();
  client.connectionSaved.subscribe(saved);
  client.status().subscribe();
  client.test().subscribe();
  client.claim('fixture-token').subscribe();
  client.delete().subscribe();
  http.post.mockReturnValue(throwError(() => new Error('Rejected fixture')));
  client.manual('fixture-private-key').subscribe({ error: () => {} });
  client.complete('fixture-code', 'fixture-state').subscribe({ error: () => {} });
  expect(saved).not.toHaveBeenCalled();
});
