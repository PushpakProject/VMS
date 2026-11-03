import type { UploadTarget } from './uploadJob';

/** Why an upload attempt failed, so the queue can decide what to do next. */
export type TransportFailure =
  /** No answer or a server error: retry with backoff. */
  | 'NETWORK'
  /** Signature expired or rejected: get a fresh URL and retry immediately. */
  | 'URL_EXPIRED'
  /** The storage refused the content (e.g. too large): do not retry. */
  | 'REJECTED';

export class TransportError extends Error {
  constructor(
    readonly failure: TransportFailure,
    readonly status: number,
  ) {
    super(`Upload failed (${failure}, HTTP ${status})`);
    this.name = 'TransportError';
  }
}

/**
 * Port for moving bytes to storage. M1 ships a single signed PUT; a resumable (chunked) transport
 * can replace it later without touching the queue or the UI (Strategy pattern).
 */
export interface UploadTransport {
  upload(blob: Blob, target: UploadTarget, onProgress: (fraction: number) => void): Promise<void>;
}

export function classifyStatus(status: number): TransportFailure {
  if (status === 0 || status >= 500 || status === 408 || status === 429) {
    return 'NETWORK';
  }
  if (status === 401 || status === 403) {
    return 'URL_EXPIRED';
  }
  return 'REJECTED';
}

/** Direct browser-to-storage PUT with exactly the headers the URL was signed with. */
export class SignedPutTransport implements UploadTransport {
  constructor(private readonly timeoutMs = 10 * 60_000) {}

  upload(blob: Blob, target: UploadTarget, onProgress: (fraction: number) => void): Promise<void> {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open(target.method, target.url);
      xhr.timeout = this.timeoutMs;
      Object.entries(target.headers).forEach(([name, value]) => xhr.setRequestHeader(name, value));
      xhr.upload.onprogress = (event) => {
        if (event.lengthComputable && event.total > 0) {
          onProgress(event.loaded / event.total);
        }
      };
      xhr.onload = () => {
        if (xhr.status >= 200 && xhr.status < 300) {
          onProgress(1);
          resolve();
        } else {
          reject(new TransportError(classifyStatus(xhr.status), xhr.status));
        }
      };
      xhr.onerror = () => reject(new TransportError('NETWORK', 0));
      xhr.ontimeout = () => reject(new TransportError('NETWORK', 0));
      xhr.onabort = () => reject(new TransportError('NETWORK', 0));
      xhr.send(blob);
    });
  }
}
