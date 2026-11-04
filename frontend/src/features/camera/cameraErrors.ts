export type CameraErrorKind =
  | 'NO_CAMERA'
  | 'PERMISSION_DENIED'
  | 'SYSTEM_BLOCKED'
  | 'CAMERA_BUSY'
  | 'INSECURE_CONTEXT'
  | 'DISCONNECTED'
  | 'UNKNOWN';

export interface CameraError {
  kind: CameraErrorKind;
  message: string;
  hint: string;
  /** Browser error name, shown small for troubleshooting (e.g. NotAllowedError). */
  detail?: string;
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
  SYSTEM_BLOCKED: {
    message: 'Your operating system is blocking the camera for this browser.',
    hint: 'macOS: System Settings → Privacy & Security → Camera → turn on your browser, then quit and reopen the browser. Windows: Settings → Privacy → Camera.',
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
  // DOMException is not an Error subclass in every environment: read fields structurally.
  const { name: rawName, message: rawMessage } = (error ?? {}) as {
    name?: unknown;
    message?: unknown;
  };
  const name = typeof rawName === 'string' ? rawName : '';
  const text = typeof rawMessage === 'string' ? rawMessage.toLowerCase() : '';
  return { ...classify(name, text), detail: name || undefined };
}

function classify(name: string, text: string): CameraError {
  // Chrome on macOS reports an OS-level block as "Permission denied by system".
  if (text.includes('by system') || text.includes('system denied')) {
    return cameraError('SYSTEM_BLOCKED');
  }
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
