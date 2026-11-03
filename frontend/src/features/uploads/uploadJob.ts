/**
 * Client-side upload lifecycle (state machine). Persisted in IndexedDB so it survives refreshes.
 *
 *   PENDING -> UPLOADING -> COMPLETING -> UPLOADED_REMOTE -> DONE
 *      ^          |             |
 *      +---- WAITING_FOR_NETWORK +            any -> FAILED (user: retry or discard)
 */
export type UploadState =
  | 'RECORDING'
  | 'PENDING'
  | 'WAITING_FOR_NETWORK'
  | 'UPLOADING'
  | 'COMPLETING'
  | 'UPLOADED_REMOTE'
  | 'DONE'
  | 'FAILED';

export interface UploadTarget {
  url: string;
  method: string;
  headers: Record<string, string>;
  expiresAt: string;
}

export interface UploadJob {
  localId: string;
  ownerUserId: string;
  trackingId: string;
  mimeType: string;
  contentType: string;
  recordedAt: string;
  state: UploadState;
  /** True while MediaRecorder is still writing chunks for this job. */
  recording: boolean;
  chunkCount: number;
  bytesRecorded: number;
  durationMs: number | null;
  videoId: string | null;
  upload: UploadTarget | null;
  progress: number;
  attempts: number;
  nextAttemptAt: number;
  lastError: string | null;
  serverStatus: string | null;
}

const ALLOWED: Record<UploadState, UploadState[]> = {
  RECORDING: ['PENDING', 'FAILED'],
  PENDING: ['UPLOADING', 'COMPLETING', 'UPLOADED_REMOTE', 'WAITING_FOR_NETWORK', 'FAILED'],
  WAITING_FOR_NETWORK: ['PENDING', 'UPLOADING', 'COMPLETING', 'FAILED'],
  UPLOADING: ['COMPLETING', 'PENDING', 'WAITING_FOR_NETWORK', 'FAILED'],
  COMPLETING: ['UPLOADED_REMOTE', 'PENDING', 'WAITING_FOR_NETWORK', 'FAILED'],
  UPLOADED_REMOTE: ['DONE', 'FAILED'],
  DONE: [],
  FAILED: ['PENDING'],
};

export function canTransition(from: UploadState, to: UploadState): boolean {
  return from === to || ALLOWED[from].includes(to);
}

/** Returns a copy in the new state, or throws for an illegal transition (a programming error). */
export function transition(
  job: UploadJob,
  to: UploadState,
  patch: Partial<UploadJob> = {},
): UploadJob {
  if (!canTransition(job.state, to)) {
    throw new Error(`Illegal upload transition ${job.state} -> ${to}`);
  }
  return { ...job, ...patch, state: to };
}

export const ACTIVE_STATES: UploadState[] = [
  'PENDING',
  'WAITING_FOR_NETWORK',
  'UPLOADING',
  'COMPLETING',
];

export function isActive(job: UploadJob): boolean {
  return !job.recording && ACTIVE_STATES.includes(job.state);
}

export function baseContentType(mimeType: string): string {
  return (mimeType.split(';')[0] ?? '').trim().toLowerCase();
}
