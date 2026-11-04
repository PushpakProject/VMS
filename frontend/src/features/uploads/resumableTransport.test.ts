import { ResumableTransport, committedBytes, type PieceRequest } from './resumableTransport';
import type { UploadTarget } from './uploadJob';
import { TransportError } from './uploadTransport';

const MiB = 1024 * 1024;

/** In-memory fake of a GCS resumable session that can "drop the network" on demand. */
function fakeSession(total: number) {
  let committed = 0;
  let dropNextPiece = false;
  const requests: string[] = [];
  const send = async (request: PieceRequest) => {
    requests.push(request.contentRange);
    const range = () => (committed > 0 ? `bytes=0-${committed - 1}` : null);
    if (request.body === null) {
      return committed === total ? { status: 200, range: null } : { status: 308, range: range() };
    }
    if (dropNextPiece) {
      dropNextPiece = false;
      committed += request.body.size; // server stored it, but the response is lost
      return { status: 0, range: null };
    }
    const start = Number(/bytes (\d+)-/.exec(request.contentRange)?.[1]);
    if (start !== committed) {
      return { status: 308, range: range() };
    }
    committed += request.body.size;
    return committed === total ? { status: 200, range: null } : { status: 308, range: range() };
  };
  return {
    send,
    requests,
    dropNext: () => (dropNextPiece = true),
    committed: () => committed,
  };
}

const target = (chunk = 4 * MiB): UploadTarget => ({
  protocol: 'RESUMABLE',
  url: 'https://storage.example/session/1',
  method: 'PUT',
  headers: {},
  expiresAt: new Date(Date.now() + 86_400_000).toISOString(),
  chunkSizeBytes: chunk,
});

describe('ResumableTransport', () => {
  it('uploads in pieces with Content-Range and reports progress', async () => {
    const total = 9 * MiB;
    const session = fakeSession(total);
    const progress: number[] = [];

    await new ResumableTransport(session.send).upload(
      new Blob([new Uint8Array(total)]),
      target(),
      (p) => progress.push(p),
    );

    expect(session.requests).toEqual([
      `bytes */${total}`,
      `bytes 0-${4 * MiB - 1}/${total}`,
      `bytes ${4 * MiB}-${8 * MiB - 1}/${total}`,
      `bytes ${8 * MiB}-${total - 1}/${total}`,
    ]);
    expect(progress.at(-1)).toBe(1);
  });

  it('resumes after a dropped connection without re-sending confirmed bytes', async () => {
    const total = 12 * MiB;
    const session = fakeSession(total);
    const blob = new Blob([new Uint8Array(total)]);
    const transport = new ResumableTransport(session.send);

    session.dropNext();
    await expect(transport.upload(blob, target(), () => undefined)).rejects.toMatchObject({
      failure: 'NETWORK',
    });
    expect(session.committed()).toBe(4 * MiB);

    session.requests.length = 0;
    await transport.upload(blob, target(), () => undefined);

    expect(session.requests[0]).toBe(`bytes */${total}`);
    expect(session.requests[1]).toBe(`bytes ${4 * MiB}-${8 * MiB - 1}/${total}`);
    expect(session.committed()).toBe(total);
  });

  it('asks for a new session when the old one is gone', async () => {
    const transport = new ResumableTransport(async () => ({ status: 404, range: null }));
    const error = await transport
      .upload(new Blob([new Uint8Array(10)]), target(), () => undefined)
      .catch((e: unknown) => e);
    expect(error).toBeInstanceOf(TransportError);
    expect((error as TransportError).failure).toBe('URL_EXPIRED');
  });

  it('parses the committed range header', () => {
    expect(committedBytes(null)).toBe(0);
    expect(committedBytes('bytes=0-4194303')).toBe(4 * MiB);
  });
});
