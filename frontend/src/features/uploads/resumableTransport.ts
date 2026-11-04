import type { UploadTarget } from './uploadJob';
import { SignedPutTransport, TransportError, type UploadTransport } from './uploadTransport';

const DEFAULT_CHUNK = 4 * 1024 * 1024;

export interface PieceRequest {
  url: string;
  contentRange: string;
  body: Blob | null;
  onProgress?: (loaded: number) => void;
}

export interface PieceResponse {
  status: number;
  /** Value of the `Range` header on 308 responses, e.g. `bytes=0-4194303`. */
  range: string | null;
}

export type PieceSender = (request: PieceRequest) => Promise<PieceResponse>;

/** Bytes storage has confirmed, from a `Range: bytes=0-N` header (absent = nothing yet). */
export function committedBytes(range: string | null): number {
  const match = range ? /bytes=0-(\d+)/.exec(range) : null;
  return match ? Number(match[1]) + 1 : 0;
}

/**
 * GCS resumable-upload protocol. First asks storage how much it already has
 * (`Content-Range: bytes * /TOTAL`), then sends pieces from that offset. After a network drop the
 * next attempt resumes from the last byte storage confirmed instead of re-sending the clip.
 * A missing or expired session (404/410) asks the queue for a new one.
 */
export class ResumableTransport implements UploadTransport {
  constructor(private readonly send: PieceSender = xhrSend) {}

  async upload(blob: Blob, target: UploadTarget, onProgress: (fraction: number) => void) {
    const total = blob.size;
    const chunk = target.chunkSizeBytes ?? DEFAULT_CHUNK;
    let offset = await this.queryOffset(target.url, total);
    onProgress(offset / total);
    while (offset < total) {
      const end = Math.min(offset + chunk, total);
      const start = offset;
      const response = await this.send({
        url: target.url,
        contentRange: `bytes ${start}-${end - 1}/${total}`,
        body: blob.slice(start, end),
        onProgress: (loaded) => onProgress((start + loaded) / total),
      });
      if (response.status === 200 || response.status === 201) {
        onProgress(1);
        return;
      }
      if (response.status !== 308) {
        throw toError(response.status);
      }
      offset = committedBytes(response.range);
      onProgress(offset / total);
    }
  }

  private async queryOffset(url: string, total: number): Promise<number> {
    const response = await this.send({ url, contentRange: `bytes */${total}`, body: null });
    if (response.status === 200 || response.status === 201) {
      return total;
    }
    if (response.status !== 308) {
      throw toError(response.status);
    }
    return committedBytes(response.range);
  }
}

function toError(status: number): TransportError {
  if (status === 404 || status === 410) {
    return new TransportError('URL_EXPIRED', status);
  }
  if (status === 0 || status >= 500 || status === 408 || status === 429) {
    return new TransportError('NETWORK', status);
  }
  return new TransportError('REJECTED', status);
}

function xhrSend(request: PieceRequest): Promise<PieceResponse> {
  return new Promise((resolve) => {
    const xhr = new XMLHttpRequest();
    xhr.open('PUT', request.url);
    xhr.timeout = 5 * 60_000;
    xhr.setRequestHeader('Content-Range', request.contentRange);
    xhr.upload.onprogress = (event) => request.onProgress?.(event.loaded);
    xhr.onload = () => resolve({ status: xhr.status, range: xhr.getResponseHeader('Range') });
    const failed = () => resolve({ status: 0, range: null });
    xhr.onerror = failed;
    xhr.ontimeout = failed;
    xhr.onabort = failed;
    xhr.send(request.body);
  });
}

/** Strategy selection per target: the queue stays unaware of the upload protocol. */
export class ProtocolRoutingTransport implements UploadTransport {
  constructor(
    private readonly simple: UploadTransport = new SignedPutTransport(),
    private readonly resumable: UploadTransport = new ResumableTransport(),
  ) {}

  upload(blob: Blob, target: UploadTarget, onProgress: (fraction: number) => void) {
    return (target.protocol === 'RESUMABLE' ? this.resumable : this.simple).upload(
      blob,
      target,
      onProgress,
    );
  }
}
