import { ConnectivityMonitor } from './connectivityMonitor';

describe('ConnectivityMonitor', () => {
  afterEach(() => vi.useRealTimers());

  it('goes offline after two consecutive failures, not one', () => {
    const monitor = new ConnectivityMonitor({ probe: async () => false });
    monitor.reportFailure();
    expect(monitor.current).toBe('ONLINE');
    monitor.reportFailure();
    expect(monitor.current).toBe('OFFLINE');
    monitor.dispose();
  });

  it('comes back online only after a successful probe', async () => {
    vi.useFakeTimers();
    let reachable = false;
    const changes: string[] = [];
    const monitor = new ConnectivityMonitor({ probe: async () => reachable, minProbeDelayMs: 100 });
    monitor.subscribe((state) => changes.push(state));

    monitor.browserWentOffline();
    await vi.advanceTimersByTimeAsync(100);
    expect(monitor.current).toBe('OFFLINE');

    reachable = true;
    await vi.advanceTimersByTimeAsync(400);
    expect(monitor.current).toBe('ONLINE');
    expect(changes).toEqual(['OFFLINE', 'ONLINE']);
    monitor.dispose();
  });

  it('does not trust the browser "online" event (Wi-Fi without internet)', async () => {
    const monitor = new ConnectivityMonitor({ probe: async () => false, initial: 'OFFLINE' });
    monitor.browserWentOnline();
    await Promise.resolve();
    await Promise.resolve();
    expect(monitor.current).toBe('OFFLINE');
    monitor.dispose();
  });
});
