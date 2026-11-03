import { waitFor } from '@testing-library/react';
import { openVmsDb } from '../../lib/db';
import { ApiError, NetworkError } from '../../lib/errors';
import type { RetryPolicy } from '../../lib/retryPolicy';
import { ConnectivityMonitor } from '../connectivity/connectivityMonitor';
import type { UploadTarget } from './uploadJob';
import { UploadJobRepository } from './uploadJobRepository';
import { UploadQueue } from './uploadQueue';
import { TransportError, type UploadTransport } from './uploadTransport';
import type { UploadsApi } from './uploadsApi';

const noDelay: RetryPolicy = { delayMs: () => 0 };

function target(): UploadTarget {
  return {
    url: 'https://storage.example/raw/a.webm?sig',
    method: 'PUT',
    headers: { 'Content-Type': 'video/webm' },
    expiresAt: new Date(Date.now() + 15 * 60_000).toISOString(),
  };
}

let dbCounter = 0;

async function setup(overrides: { transport?: UploadTransport; api?: Partial<UploadsApi> } = {}) {
  const db = await openVmsDb(`test-${++dbCounter}`);
  const repo = new UploadJobRepository(db);
  const api: UploadsApi = {
    initiate: vi.fn(async () => ({ videoId: 'video-1', status: 'UPLOADING', upload: target() })),
    complete: vi.fn(async () => ({
      id: 'video-1',
      status: 'UPLOADED',
      failureCode: null,
      failureMessage: null,
    })),
    status: vi.fn(async () => ({
      id: 'video-1',
      status: 'READY',
      failureCode: null,
      failureMessage: null,
    })),
    ...overrides.api,
  };
  const transport: UploadTransport = overrides.transport ?? {
    upload: vi.fn(async (_blob, _target, onProgress) => onProgress(1)),
  };
  const connectivity = new ConnectivityMonitor({ probe: async () => true });
  const queue = new UploadQueue({
    repo,
    api,
    transport,
    connectivity,
    retryPolicy: noDelay,
    locks: null,
    statusPollMs: 10,
  });
  await queue.setOwner('user-a');
  return { queue, repo, api, transport, connectivity };
}

async function record(queue: UploadQueue, localId: string, bytes = 2_000) {
  await queue.createJob({
    localId,
    trackingId: `TRK-${localId}`,
    mimeType: 'video/webm;codecs=vp9,opus',
    recordedAt: new Date().toISOString(),
  });
  await queue.appendChunk(localId, new Blob([new Uint8Array(bytes / 2)]));
  await queue.appendChunk(localId, new Blob([new Uint8Array(bytes / 2)]));
  await queue.finishRecording(localId, 60_000);
}

describe('UploadQueue', () => {
  it('uploads, completes and frees the local copy once the server says READY', async () => {
    const { queue, repo, api, transport } = await setup();

    await record(queue, 'a');

    await waitFor(async () => expect(await repo.get('a')).toBeUndefined());
    expect(api.initiate).toHaveBeenCalledWith('a', expect.objectContaining({ sizeBytes: 2_000 }));
    const blob = vi.mocked(transport.upload).mock.calls[0]?.[0];
    expect(blob?.size).toBe(2_000);
    expect(blob?.type).toBe('video/webm');
    expect(api.complete).toHaveBeenCalledWith('video-1', 60_000);
    queue.dispose();
  });

  it('keeps the recording and retries after a network failure', async () => {
    let calls = 0;
    const transport: UploadTransport = {
      upload: vi.fn(async () => {
        calls++;
        if (calls === 1) {
          throw new TransportError('NETWORK', 0);
        }
      }),
    };
    const { queue, repo } = await setup({ transport });

    await record(queue, 'a');

    await waitFor(async () => expect(await repo.get('a')).toBeUndefined());
    expect(calls).toBe(2);
    queue.dispose();
  });

  it('requests a fresh signed URL when the old one expired', async () => {
    let calls = 0;
    const transport: UploadTransport = {
      upload: vi.fn(async () => {
        calls++;
        if (calls === 1) {
          throw new TransportError('URL_EXPIRED', 403);
        }
      }),
    };
    const { queue, repo, api } = await setup({ transport });

    await record(queue, 'a');

    await waitFor(async () => expect(await repo.get('a')).toBeUndefined());
    expect(api.initiate).toHaveBeenCalledTimes(2);
    queue.dispose();
  });

  it('waits while offline and resumes when connectivity returns', async () => {
    const { queue, repo, connectivity, transport } = await setup();
    connectivity.browserWentOffline();

    await record(queue, 'a');
    await new Promise((r) => setTimeout(r, 30));
    expect(transport.upload).not.toHaveBeenCalled();
    expect((await repo.get('a'))?.state).toBe('PENDING');

    connectivity.reportSuccess();
    await waitFor(async () => expect(await repo.get('a')).toBeUndefined());
    queue.dispose();
  });

  it('marks validation errors as failed instead of retrying forever', async () => {
    const { queue, repo } = await setup({
      api: {
        initiate: vi.fn(async () => {
          throw new ApiError(415, { code: 'UNSUPPORTED_MEDIA_TYPE', detail: 'Not supported' });
        }),
      },
    });

    await record(queue, 'a');

    await waitFor(async () => expect((await repo.get('a'))?.state).toBe('FAILED'));
    expect((await repo.get('a'))?.lastError).toBe('Not supported');
    queue.dispose();
  });

  it('retries transient API failures with backoff', async () => {
    let calls = 0;
    const { queue, repo } = await setup({
      api: {
        initiate: vi.fn(async () => {
          calls++;
          if (calls === 1) {
            throw new NetworkError();
          }
          return { videoId: 'video-1', status: 'UPLOADING', upload: target() };
        }),
      },
    });

    await record(queue, 'a');

    await waitFor(async () => expect(await repo.get('a')).toBeUndefined());
    expect(calls).toBe(2);
    queue.dispose();
  });

  it("never uploads another user's recordings", async () => {
    const { queue, repo, transport } = await setup();
    await queue.setOwner('user-b');
    await queue.createJob({
      localId: 'b1',
      trackingId: 'TRK-B1',
      mimeType: 'video/webm',
      recordedAt: new Date().toISOString(),
    });
    // user-b signs out before the upload; user-a signs in on the same PC.
    await queue.setOwner('user-a');
    await new Promise((r) => setTimeout(r, 30));

    expect(transport.upload).not.toHaveBeenCalled();
    expect((await repo.get('b1'))?.ownerUserId).toBe('user-b');
    expect(await repo.countOthers('user-a')).toBe(1);
    queue.dispose();
  });

  it('recovers a recording that was interrupted by a crash', async () => {
    const { queue, repo } = await setup();
    await queue.setOwner(null);
    await repo.save({
      localId: 'crash',
      ownerUserId: 'user-a',
      trackingId: 'TRK-CRASH',
      mimeType: 'video/webm',
      contentType: 'video/webm',
      recordedAt: new Date().toISOString(),
      state: 'RECORDING',
      recording: true,
      chunkCount: 1,
      bytesRecorded: 500,
      durationMs: null,
      videoId: null,
      upload: null,
      progress: 0,
      attempts: 0,
      nextAttemptAt: 0,
      lastError: null,
      serverStatus: null,
    });
    await repo.appendChunk('crash', 0, new Blob([new Uint8Array(500)]));

    await queue.setOwner('user-a');

    await waitFor(async () => expect(await repo.get('crash')).toBeUndefined());
    queue.dispose();
  });
});
