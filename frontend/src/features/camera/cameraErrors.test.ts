import { toCameraError } from './cameraErrors';

describe('toCameraError', () => {
  it.each([
    ['NotFoundError', 'NO_CAMERA'],
    ['OverconstrainedError', 'NO_CAMERA'],
    ['NotAllowedError', 'PERMISSION_DENIED'],
    ['NotReadableError', 'CAMERA_BUSY'],
    ['AbortError', 'CAMERA_BUSY'],
    ['WeirdError', 'UNKNOWN'],
  ])('maps %s to %s', (name, kind) => {
    const error = toCameraError(new DOMException('x', name));
    expect(error.kind).toBe(kind);
    expect(error.message).not.toHaveLength(0);
    expect(error.hint).not.toHaveLength(0);
  });

  it('shows "No camera connected" for a missing device', () => {
    expect(toCameraError(new DOMException('x', 'NotFoundError')).message).toBe(
      'No camera connected.',
    );
  });
});
