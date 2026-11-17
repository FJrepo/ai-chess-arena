import { WebSocketService } from './websocket.service';

class FakeSocket {
  static readonly CONNECTING = 0;
  static readonly OPEN = 1;
  static instances: FakeSocket[] = [];
  readyState = FakeSocket.CONNECTING;
  sent: string[] = [];
  onopen: (() => void) | null = null;
  onclose: (() => void) | null = null;
  onmessage: ((event: { data: string }) => void) | null = null;
  onerror: ((err: unknown) => void) | null = null;

  constructor(public url: string) {
    FakeSocket.instances.push(this);
  }

  send(data: string) {
    this.sent.push(data);
  }

  close() {}

  open() {
    this.readyState = FakeSocket.OPEN;
    this.onopen?.();
  }
}

describe('WebSocketService', () => {
  beforeEach(() => {
    FakeSocket.instances = [];
    vi.useFakeTimers();
    vi.stubGlobal('WebSocket', FakeSocket);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('signals a reconnect (not the first connect) and resubscribes to the current game', () => {
    const service = new WebSocketService({ open: vi.fn() } as any);
    const reconnects = vi.fn();
    service.reconnected().subscribe(reconnects);

    service.connect();
    FakeSocket.instances[0].open();
    service.subscribeToGame('g-1');
    expect(reconnects).not.toHaveBeenCalled();

    FakeSocket.instances[0].onclose?.();
    vi.advanceTimersByTime(3000);
    FakeSocket.instances[1].open();

    expect(reconnects).toHaveBeenCalledTimes(1);
    expect(FakeSocket.instances[1].sent).toContain(
      JSON.stringify({ type: 'subscribe', gameId: 'g-1' }),
    );
  });

  it('negotiates the game stream and uses heartbeats to check the committed revision', () => {
    const service = new WebSocketService({ open: vi.fn() } as any);
    const viewer = vi.fn();
    service.getMessages('g-1').subscribe(viewer);
    service.subscribeToGame('g-1', true);
    service.connect();
    const socket = FakeSocket.instances[0];
    socket.open();
    expect(socket.sent).toEqual([
      JSON.stringify({ type: 'subscribe', gameId: 'g-1', protocol: 2 }),
    ]);

    const ack = { type: 'subscribed', gameId: 'g-1', revision: 3 };
    socket.onmessage?.({ data: JSON.stringify(ack) });
    expect(viewer).toHaveBeenCalledWith(ack);
    vi.advanceTimersByTime(15000);
    expect(socket.sent.at(-1)).toBe(JSON.stringify({ type: 'heartbeat', gameId: 'g-1' }));

    const heartbeat = { type: 'heartbeat', gameId: 'g-1', revision: 4 };
    socket.onmessage?.({ data: JSON.stringify(heartbeat) });
    expect(viewer).toHaveBeenCalledWith(heartbeat);
    vi.advanceTimersByTime(17500);
    expect(viewer).toHaveBeenLastCalledWith({ type: 'subscriptionUnavailable', gameId: 'g-1' });
    service.disconnect();
  });

  it('signals snapshot fallback when a legacy server ignores the subscription handshake', () => {
    const service = new WebSocketService({ open: vi.fn() } as any);
    const viewer = vi.fn();
    service.getMessages('g-1').subscribe(viewer);
    service.subscribeToGame('g-1', true);
    service.connect();
    FakeSocket.instances[0].open();
    vi.advanceTimersByTime(2499);
    expect(viewer).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1);
    expect(viewer).toHaveBeenCalledExactlyOnceWith({
      type: 'subscriptionUnavailable',
      gameId: 'g-1',
    });
    service.disconnect();
  });

  it('delivers attempt/cost invalidations only to viewers of that game', () => {
    const service = new WebSocketService({ open: vi.fn() } as any);
    const viewer = vi.fn();
    service.getMessages('g-1').subscribe(viewer);
    service.connect();
    FakeSocket.instances[0].open();
    FakeSocket.instances[0].onmessage?.({
      data: JSON.stringify({ type: 'attemptsUpdated', gameId: 'g-1' }),
    });
    FakeSocket.instances[0].onmessage?.({
      data: JSON.stringify({ type: 'attemptsUpdated', gameId: 'g-2' }),
    });
    expect(viewer).toHaveBeenCalledExactlyOnceWith({ type: 'attemptsUpdated', gameId: 'g-1' });
    service.disconnect();
  });
});
