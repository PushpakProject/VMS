export type CameraErrorKind =
  | 'NO_CAMERA'
  | 'PERMISSION_DENIED'
  | 'CAMERA_BUSY'
  | 'INSECURE_CONTEXT'
  | 'DISCONNECTED'
  | 'UNKNOWN';

export interface CameraError {
  kind: CameraErrorKind;
  message: string;
  hint: string;
}

const MESSAGES: Record<CameraErrorKind, Omit<CameraError, 'kind'>> = {
  NO_CAMERA: {
    message: 'No camera connected.',
    hint: 'Plug in a webcam — it will be detected automatically.',
  },
  PERMISSION_DENIED: {
    message: 'Camera access was blocked.',
    hint: 'Click the camera icon in the address bar, allow access, then press Retry.',
  },
  CAMERA_BUSY: {
    message: 'The camera is in use by another application.',
    hint: 'Close other apps or tabs using the camera (Teams, Zoom…), then press Retry.',
  },
  INSECURE_CONTEXT: {
    message: 'The camera needs a secure (HTTPS) connection.',
    hint: 'Open the app over https:// or on localhost.',
  },
  DISCONNECTED: {
    message: 'The camera was disconnected.',
    hint: 'Reconnect it or pick another camera.',
  },
  UNKNOWN: {
    message: 'The camera could not be started.',
    hint: 'Try another camera or press Retry.',
  },
};

export function cameraError(kind: CameraErrorKind): CameraError {
  return { kind, ...MESSAGES[kind] };
}

/** Maps getUserMedia DOMExceptions to specific, actionable messages. */
export function toCameraError(error: unknown): CameraError {
  const name = error instanceof DOMException || error instanceof Error ? error.name : '';
  switch (name) {
    case 'NotFoundError':
    case 'OverconstrainedError':
    case 'DevicesNotFoundError':
      return cameraError('NO_CAMERA');
    case 'NotAllowedError':
    case 'PermissionDeniedError':
      return cameraError('PERMISSION_DENIED');
    case 'SecurityError':
      return cameraError(window.isSecureContext ? 'PERMISSION_DENIED' : 'INSECURE_CONTEXT');
    case 'NotReadableError':
    case 'TrackStartError':
    case 'AbortError':
      return cameraError('CAMERA_BUSY');
    default:
      return cameraError('UNKNOWN');
  }
}
