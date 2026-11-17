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
