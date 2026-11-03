import { ApiError, NetworkError, SessionExpiredError, messageOf } from '../../lib/errors';
import type { RetryPolicy } from '../../lib/retryPolicy';
import type { ConnectivityMonitor } from '../connectivity/connectivityMonitor';
import { baseContentType, isActive, transition, type UploadJob } from './uploadJob';
import type { UploadJobRepository } from './uploadJobRepository';
import { TransportError, type UploadTransport } from './uploadTransport';
import type { UploadsApi } from './uploadsApi';

export interface QueueSnapshot {
  jobs: UploadJob[];
  othersCount: number;
  paused: boolean;
}

export interface UploadQueueDeps {
  repo: UploadJobRepository;
  api: UploadsApi;
  transport: UploadTransport;
  connectivity: ConnectivityMonitor;
  retryPolicy: RetryPolicy;
  now?: () => number;
  locks?: LockManager | null;
  statusPollMs?: number;
}

const LOCK_NAME = 'vms-upload-queue';
const URL_SAFETY_MARGIN_MS = 60_000;
const MAX_FAST_URL_RETRIES = 3;

/**
 * Persistent, offline-safe upload queue.
 *
 * - Every job and its chunks live in IndexedDB until the server reports READY.
 * - One worker per browser (Web Locks), oldest recording first.
 * - Failures are classified: network -> backoff + jitter; expired URL -> re-sign; rejected -> fail.
 * - Only the signed-in user's jobs are processed (shared PCs).
 * - Observer: the UI subscribes to snapshots.
 */
export class UploadQueue {
  private owner: string | null = null;
  private paused = false;
  private draining = false;
  private drainAgain = false;
  private retryTimer: ReturnType<typeof setTimeout> | null = null;
  private pollTimer: ReturnType<typeof setTimeout> | null = null;
  private readonly progress = new Map<string, number>();
  private readonly writes = new Map<string, Promise<void>>();
  private readonly listeners = new Set<(snapshot: QueueSnapshot) => void>();
  private readonly now: () => number;
  private readonly locks: LockManager | null;
  private readonly statusPollMs: number;
  private readonly unsubscribeConnectivity: () => void;

  constructor(private readonly deps: UploadQueueDeps) {
    this.now = deps.now ?? Date.now;
    this.locks =
      deps.locks !== undefined
        ? deps.locks
        : typeof navigator !== 'undefined' && 'locks' in navigator
          ? navigator.locks
          : null;
    this.statusPollMs = deps.statusPollMs ?? 5_000;
    this.unsubscribeConnectivity = deps.connectivity.subscribe((state) => {
      if (state === 'ONLINE') {
        void this.wakeWaitingJobs();
      }
      void this.notify();
    });
  }

  /** Switches the active user; other users' jobs stay untouched on the device. */
  async setOwner(userId: string | null): Promise<void> {
    this.owner = userId;
    this.paused = false;
    if (userId) {
      await this.recoverInterrupted();
      this.kick();
    }
    await this.notify();
  }

  subscribe(listener: (snapshot: QueueSnapshot) => void): () => void {
    this.listeners.add(listener);
    void this.notify();
    return () => this.listeners.delete(listener);
  }

  async createJob(params: {
    localId: string;
    trackingId: string;
    mimeType: string;
    recordedAt: string;
  }): Promise<UploadJob> {
    if (!this.owner) {
      throw new Error('Not signed in');
    }
    const job: UploadJob = {
      ...params,
      ownerUserId: this.owner,
      contentType: baseContentType(params.mimeType),
      state: 'RECORDING',
      recording: true,
      chunkCount: 0,
      bytesRecorded: 0,
      durationMs: null,
      videoId: null,
      upload: null,
      progress: 0,
      attempts: 0,
      nextAttemptAt: 0,
      lastError: null,
      serverStatus: null,
    };
    await this.deps.repo.save(job);
    await this.notify();
    return job;
  }

  /** Chunk writes for one job are serialized so they land in order. */
  appendChunk(localId: string, blob: Blob): Promise<void> {
    const previous = this.writes.get(localId) ?? Promise.resolve();
    const next = previous.then(async () => {
      const job = await this.deps.repo.get(localId);
      if (!job) {
        return;
      }
      await this.deps.repo.appendChunk(localId, job.chunkCount, blob);
      await this.deps.repo.save({
        ...job,
        chunkCount: job.chunkCount + 1,
        bytesRecorded: job.bytesRecorded + blob.size,
      });
    });
    this.writes.set(
      localId,
      next.catch(() => undefined),
    );
    return next;
  }

  async finishRecording(localId: string, durationMs: number | null): Promise<void> {
    await (this.writes.get(localId) ?? Promise.resolve());
    this.writes.delete(localId);
    const job = await this.deps.repo.get(localId);
    if (!job) {
      return;
    }
    if (job.bytesRecorded === 0) {
      await this.deps.repo.save(
        transition(job, 'FAILED', { recording: false, lastError: 'Nothing was recorded.' }),
      );
    } else {
      await this.deps.repo.save(transition(job, 'PENDING', { recording: false, durationMs }));
    }
    await this.notify();
    this.kick();
  }

  async retry(localId: string): Promise<void> {
    const job = await this.deps.repo.get(localId);
    if (job?.state === 'FAILED' && job.bytesRecorded > 0) {
      await this.deps.repo.save(
        transition(job, 'PENDING', {
          attempts: 0,
          nextAttemptAt: 0,
          lastError: null,
          upload: null,
        }),
      );
      await this.notify();
      this.kick();
    }
  }

  async discard(localId: string): Promise<void> {
    await this.deps.repo.delete(localId);
    await this.notify();
  }

  previewBlob(job: UploadJob): Promise<Blob> {
    return this.deps.repo.assembleBlob(job.localId, job.contentType);
  }

  kick(): void {
    if (this.draining) {
      this.drainAgain = true;
      return;
    }
    void this.runExclusive();
  }

  dispose(): void {
    this.unsubscribeConnectivity();
    this.clearTimer('retry');
    this.clearTimer('poll');
    this.listeners.clear();
  }

  /** Recordings that were still open when the tab crashed are closed with what is on disk. */
  private async recoverInterrupted(): Promise<void> {
    if (!this.owner) {
      return;
    }
    const jobs = await this.deps.repo.listByOwner(this.owner);
    for (const job of jobs) {
      if (job.recording && !this.writes.has(job.localId)) {
        await this.finishRecording(job.localId, null);
      } else if (job.state === 'UPLOADING') {
        await this.deps.repo.save(transition(job, 'PENDING'));
      }
    }
  }

  private async runExclusive(): Promise<void> {
    if (this.locks) {
      await this.locks.request(LOCK_NAME, { ifAvailable: true }, async (lock) => {
        if (lock) {
          await this.drain();
        }
      });
    } else {
      await this.drain();
    }
  }

  private async drain(): Promise<void> {
    this.draining = true;
    try {
      do {
        this.drainAgain = false;
        let job = await this.nextEligible();
        while (job && this.canWork()) {
          await this.process(job);
          job = await this.nextEligible();
        }
      } while (this.drainAgain && this.canWork());
    } finally {
      this.draining = false;
      await this.scheduleTimers();
      await this.notify();
    }
  }

  private canWork(): boolean {
    return this.owner !== null && !this.paused && this.deps.connectivity.current === 'ONLINE';
  }

  private async nextEligible(): Promise<UploadJob | undefined> {
    if (!this.owner) {
      return undefined;
    }
    const now = this.now();
    const jobs = await this.deps.repo.listByOwner(this.owner);
    return jobs.find((job) => isActive(job) && job.nextAttemptAt <= now);
  }

  private async process(initial: UploadJob): Promise<void> {
    let job = initial;
    try {
      if (job.state !== 'COMPLETING') {
        if (!job.videoId || !this.hasValidTarget(job)) {
          const response = await this.deps.api.initiate(job.localId, {
            trackingId: job.trackingId,
            contentType: job.contentType,
            sizeBytes: job.bytesRecorded,
            recordedAt: job.recordedAt,
          });
          job = await this.save({
            ...job,
            videoId: response.videoId,
            upload: response.upload,
            serverStatus: response.status,
          });
          if (!response.upload) {
            job = await this.save(transition(job, 'COMPLETING'));
          }
        }
        if (job.upload && job.state !== 'COMPLETING') {
          job = await this.save(transition(job, 'UPLOADING', { lastError: null }));
          await this.uploadBytes(job);
          job = await this.save(transition(job, 'COMPLETING', { progress: 1 }));
        }
      }
      const status = await this.deps.api.complete(job.videoId as string, job.durationMs);
      job =
        status.status === 'FAILED'
          ? await this.save(
              transition(job, 'FAILED', {
                serverStatus: status.status,
                lastError: status.failureMessage ?? 'The upload was rejected.',
              }),
            )
          : await this.save(transition(job, 'UPLOADED_REMOTE', { serverStatus: status.status }));
      this.deps.connectivity.reportSuccess();
    } catch (error) {
      await this.handleFailure(job, error);
    } finally {
      this.progress.delete(job.localId);
    }
  }

  private async uploadBytes(job: UploadJob): Promise<void> {
    const blob = await this.deps.repo.assembleBlob(job.localId, job.contentType);
    let lastNotified = 0;
    await this.deps.transport.upload(blob, job.upload!, (fraction) => {
      this.progress.set(job.localId, fraction);
      const now = this.now();
      if (now - lastNotified > 250) {
        lastNotified = now;
        void this.notify();
      }
    });
  }

  private async handleFailure(job: UploadJob, error: unknown): Promise<void> {
    const fresh = (await this.deps.repo.get(job.localId)) ?? job;
    if (error instanceof SessionExpiredError) {
      this.paused = true;
      await this.save(transition(fresh, 'PENDING'));
      return;
    }
    if (error instanceof TransportError && error.failure === 'URL_EXPIRED') {
      const attempts = fresh.attempts + 1;
      await this.save(
        transition(fresh, 'PENDING', {
          upload: null,
          attempts,
          nextAttemptAt:
            attempts <= MAX_FAST_URL_RETRIES
              ? 0
              : this.now() + this.deps.retryPolicy.delayMs(attempts),
          lastError: 'Upload link expired; requesting a new one.',
        }),
      );
      return;
    }
    if (error instanceof TransportError && error.failure === 'REJECTED') {
      await this.save(
        transition(fresh, 'FAILED', { lastError: 'Storage rejected the file (is it too large?).' }),
      );
      return;
    }
    if (error instanceof ApiError && !error.retryable) {
      if (error.code === 'UPLOAD_NOT_FOUND') {
        await this.save(transition(fresh, 'PENDING', { upload: null, videoId: fresh.videoId }));
        return;
      }
      await this.save(transition(fresh, 'FAILED', { lastError: error.message }));
      return;
    }
    const isNetwork =
      error instanceof NetworkError ||
      (error instanceof TransportError && error.failure === 'NETWORK');
    if (isNetwork) {
      this.deps.connectivity.reportFailure();
    }
    const attempts = fresh.attempts + 1;
    const offline = this.deps.connectivity.current === 'OFFLINE';
    await this.save(
      transition(fresh, offline ? 'WAITING_FOR_NETWORK' : 'PENDING', {
        attempts,
        nextAttemptAt: this.now() + this.deps.retryPolicy.delayMs(attempts),
        lastError: offline ? 'Waiting for network…' : messageOf(error),
      }),
    );
  }

  private hasValidTarget(job: UploadJob): boolean {
    return (
      job.upload !== null && Date.parse(job.upload.expiresAt) - URL_SAFETY_MARGIN_MS > this.now()
    );
  }

  private async wakeWaitingJobs(): Promise<void> {
    if (!this.owner) {
      return;
    }
    const jobs = await this.deps.repo.listByOwner(this.owner);
    for (const job of jobs) {
      if (job.state === 'WAITING_FOR_NETWORK') {
        await this.deps.repo.save(transition(job, 'PENDING', { nextAttemptAt: 0 }));
      }
    }
    this.kick();
  }

  /** Server-side status for uploaded videos: READY frees the local copy. */
  private async pollRemote(): Promise<void> {
    if (!this.owner || this.paused) {
      return;
    }
    const jobs = await this.deps.repo.listByOwner(this.owner);
    for (const job of jobs.filter((j) => j.state === 'UPLOADED_REMOTE' && j.videoId)) {
      try {
        const status = await this.deps.api.status(job.videoId as string);
        if (status.status === 'READY') {
          await this.deps.repo.delete(job.localId);
        } else if (status.status === 'FAILED' || status.status === 'ABANDONED') {
          await this.save(
            transition(job, 'FAILED', {
              serverStatus: status.status,
              lastError: status.failureMessage ?? 'Processing failed.',
            }),
          );
        } else if (status.status !== job.serverStatus) {
          await this.save({ ...job, serverStatus: status.status });
        }
      } catch {
        // Polling is best effort; the next tick tries again.
      }
    }
    await this.scheduleTimers();
    await this.notify();
  }

  private async scheduleTimers(): Promise<void> {
    if (!this.owner) {
      return;
    }
    const jobs = await this.deps.repo.listByOwner(this.owner);
    const waiting = jobs.filter((j) => isActive(j)).map((j) => j.nextAttemptAt);
    this.clearTimer('retry');
    if (waiting.length > 0 && !this.paused) {
      const delay = Math.max(0, Math.min(...waiting) - this.now());
      this.retryTimer = setTimeout(() => this.kick(), delay);
    }
    this.clearTimer('poll');
    if (jobs.some((j) => j.state === 'UPLOADED_REMOTE')) {
      this.pollTimer = setTimeout(() => void this.pollRemote(), this.statusPollMs);
    }
  }

  private clearTimer(which: 'retry' | 'poll'): void {
    const timer = which === 'retry' ? this.retryTimer : this.pollTimer;
    if (timer !== null) {
      clearTimeout(timer);
    }
    if (which === 'retry') {
      this.retryTimer = null;
    } else {
      this.pollTimer = null;
    }
  }

  private async save(job: UploadJob): Promise<UploadJob> {
    await this.deps.repo.save(job);
    await this.notify();
    return job;
  }

  private async notify(): Promise<void> {
    if (this.listeners.size === 0) {
      return;
    }
    const owner = this.owner;
    const jobs = owner ? await this.deps.repo.listByOwner(owner) : [];
    const othersCount = owner ? await this.deps.repo.countOthers(owner) : 0;
    const snapshot: QueueSnapshot = {
      jobs: jobs.map((job) => ({
        ...job,
        progress: this.progress.get(job.localId) ?? job.progress,
      })),
      othersCount,
      paused: this.paused,
    };
    this.listeners.forEach((listener) => listener(snapshot));
  }
}
