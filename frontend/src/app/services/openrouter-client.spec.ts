import { HttpClient } from '@angular/common/http';
import { Subject } from 'rxjs';
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
  client.status().subscribe();
  expect(http.get).toHaveBeenCalledTimes(2);
});
