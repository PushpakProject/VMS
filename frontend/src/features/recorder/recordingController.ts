import type { UploadQueue } from '../uploads/uploadQueue';
import {
  initialRecorderState,
  recorderTransition,
  type RecorderCommand,
  type RecorderEvent,
  type RecorderState,
} from './recorderMachine';

export interface ControllerSnapshot {
  machine: RecorderState;
  startedAt: number | null;
  elapsedMs: number;
  warning: boolean;
}

export interface RecordingControllerOptions {
  queue: Pick<UploadQueue, 'createJob' | 'appendChunk' | 'finishRecording'>;
  createRecorder: (stream: MediaStream, mimeType: string) => MediaRecorder;
  mimeType: string;
  maxDurationSec: number;
  warnAtSec: number;
  timesliceMs?: number;
  now?: () => number;
  newId?: () => string;
}

interface ActiveRecording {
  localId: string;
  recorder: MediaRecorder;
  startedAt: number;
}

/**
 * Executes the recorder state machine's commands against MediaRecorder. Commands run one after
 * another, so a fast "scan, scan" never interleaves stop/start. Chunks go to IndexedDB every
 * second, so a crash loses at most ~1 s.
 */
export class RecordingController {
  private machine: RecorderState = initialRecorderState;
  private stream: MediaStream | null = null;
  private active: ActiveRecording | null = null;
  private commands: Promise<void> = Promise.resolve();
  private ticker: ReturnType<typeof setInterval> | null = null;
  private snapshotCache: ControllerSnapshot;
  private readonly listeners = new Set<() => void>();
  private readonly now: () => number;
  private readonly newId: () => string;

  constructor(private readonly options: RecordingControllerOptions) {
    this.now = options.now ?? Date.now;
    this.newId = options.newId ?? (() => crypto.randomUUID());
    this.snapshotCache = this.buildSnapshot();
  }

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  getSnapshot = (): ControllerSnapshot => this.snapshotCache;

  setStream(stream: MediaStream | null): void {
    this.stream = stream;
    this.dispatch({ type: stream ? 'CAMERA_READY' : 'CAMERA_LOST' });
  }

  submitTrackingId(trackingId: string): void {
    this.dispatch({ type: 'TRACKING_ID_SUBMITTED', trackingId });
  }

  stop(): void {
    this.dispatch({ type: 'STOP_REQUESTED' });
  }

  /** Waits for queued start/stop work (used by tests and on unmount). */
  settled(): Promise<void> {
    return this.commands;
  }

  dispose(): void {
    this.dispatch({ type: 'STOP_REQUESTED' });
    this.stopTicker();
    this.listeners.clear();
  }

  private dispatch(event: RecorderEvent): void {
    const { state, commands } = recorderTransition(this.machine, event);
    this.machine = state;
    commands.forEach((command) => {
      this.commands = this.commands
        .then(() => this.execute(command))
        .catch((error: unknown) => {
          console.error('Recorder command failed', error);
          this.machine = {
            ...this.machine,
            phase: this.stream ? 'ready' : 'idle',
            activeTrackingId: null,
            notice: { kind: 'error', text: 'Recording failed to start. Please scan again.' },
          };
          this.emit();
        });
    });
    this.emit();
  }

  private async execute(command: RecorderCommand): Promise<void> {
    switch (command.type) {
      case 'START':
        await this.start(command.trackingId);
        break;
      case 'STOP':
        this.finishActive();
        break;
      case 'SWITCH':
        this.finishActive();
        await this.start(command.trackingId);
        break;
    }
  }

  private async start(trackingId: string): Promise<void> {
    const stream = this.stream;
    if (!stream) {
      throw new Error('No camera stream');
    }
    const localId = this.newId();
    await this.options.queue.createJob({
      localId,
      trackingId,
      mimeType: this.options.mimeType,
      recordedAt: new Date(this.now()).toISOString(),
    });
    const recorder = this.options.createRecorder(stream, this.options.mimeType);
    const startedAt = this.now();
    recorder.ondataavailable = (event: BlobEvent) => {
      if (event.data && event.data.size > 0) {
        void this.options.queue.appendChunk(localId, event.data);
      }
    };
    recorder.onstop = () => {
      void this.options.queue.finishRecording(localId, this.now() - startedAt);
    };
    recorder.start(this.options.timesliceMs ?? 1_000);
    this.active = { localId, recorder, startedAt };
    this.startTicker();
    this.emit();
  }

  private finishActive(): void {
    const active = this.active;
    this.active = null;
    this.stopTicker();
    if (active && active.recorder.state !== 'inactive') {
      active.recorder.stop();
    }
    this.emit();
  }

  private startTicker(): void {
    this.stopTicker();
    this.ticker = setInterval(() => this.tick(), 250);
  }

  private stopTicker(): void {
    if (this.ticker !== null) {
      clearInterval(this.ticker);
      this.ticker = null;
    }
  }

  private tick(): void {
    if (!this.active) {
      return;
    }
    if (this.now() - this.active.startedAt >= this.options.maxDurationSec * 1000) {
      this.dispatch({ type: 'MAX_DURATION_REACHED', maxDurationSec: this.options.maxDurationSec });
      return;
    }
    this.emit();
  }

  private buildSnapshot(): ControllerSnapshot {
    const startedAt = this.active?.startedAt ?? null;
    const elapsedMs = startedAt === null ? 0 : this.now() - startedAt;
    return {
      machine: this.machine,
      startedAt,
      elapsedMs,
      warning: startedAt !== null && elapsedMs >= this.options.warnAtSec * 1000,
    };
  }

  private emit(): void {
    this.snapshotCache = this.buildSnapshot();
    this.listeners.forEach((listener) => listener());
  }
}
