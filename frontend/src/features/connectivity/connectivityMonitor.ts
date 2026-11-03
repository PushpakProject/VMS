export type Connectivity = 'ONLINE' | 'OFFLINE';

type Listener = (state: Connectivity) => void;

export interface ConnectivityMonitorOptions {
  probe: () => Promise<boolean>;
  failureThreshold?: number;
  minProbeDelayMs?: number;
  maxProbeDelayMs?: number;
  initial?: Connectivity;
}

/**
 * Decides "online" from real request outcomes, not just navigator.onLine (which says "online" on
 * Wi-Fi without internet). Two consecutive failures mark OFFLINE; a background probe with backoff
 * (2 s -> 30 s) brings it back ONLINE. Observers (upload queue, banner) subscribe to changes.
 */
export class ConnectivityMonitor {
  private state: Connectivity;
  private consecutiveFailures = 0;
  private probeTimer: ReturnType<typeof setTimeout> | null = null;
  private probeDelay: number;
  private readonly listeners = new Set<Listener>();
  private readonly probe: () => Promise<boolean>;
  private readonly failureThreshold: number;
  private readonly minProbeDelayMs: number;
  private readonly maxProbeDelayMs: number;

  constructor(options: ConnectivityMonitorOptions) {
    this.probe = options.probe;
    this.failureThreshold = options.failureThreshold ?? 2;
    this.minProbeDelayMs = options.minProbeDelayMs ?? 2_000;
    this.maxProbeDelayMs = options.maxProbeDelayMs ?? 30_000;
    this.probeDelay = this.minProbeDelayMs;
    this.state = options.initial ?? 'ONLINE';
  }

  get current(): Connectivity {
    return this.state;
  }

  subscribe(listener: Listener): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  reportSuccess(): void {
    this.consecutiveFailures = 0;
    this.set('ONLINE');
  }

  reportFailure(): void {
    this.consecutiveFailures++;
    if (this.consecutiveFailures >= this.failureThreshold) {
      this.set('OFFLINE');
    }
  }

  /** Browser said "offline": trust it immediately. */
  browserWentOffline(): void {
    this.set('OFFLINE');
  }

  /** Browser said "online": verify with a probe before believing it. */
  browserWentOnline(): void {
    this.probeDelay = this.minProbeDelayMs;
    void this.runProbe();
  }

  dispose(): void {
    this.clearProbe();
    this.listeners.clear();
  }

  private set(next: Connectivity): void {
    if (next === 'OFFLINE') {
      this.scheduleProbe();
    } else {
      this.clearProbe();
      this.probeDelay = this.minProbeDelayMs;
    }
    if (next !== this.state) {
      this.state = next;
      this.listeners.forEach((listener) => listener(next));
    }
  }

  private scheduleProbe(): void {
    if (this.probeTimer !== null) {
      return;
    }
    this.probeTimer = setTimeout(() => {
      this.probeTimer = null;
      void this.runProbe();
    }, this.probeDelay);
  }

  private async runProbe(): Promise<void> {
    let ok: boolean;
    try {
      ok = await this.probe();
    } catch {
      ok = false;
    }
    if (ok) {
      this.reportSuccess();
    } else {
      this.probeDelay = Math.min(this.maxProbeDelayMs, this.probeDelay * 2);
      this.set('OFFLINE');
    }
  }

  private clearProbe(): void {
    if (this.probeTimer !== null) {
      clearTimeout(this.probeTimer);
      this.probeTimer = null;
    }
  }
}
