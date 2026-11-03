/** Problem response returned by the API (RFC 7807). */
export interface ProblemBody {
  code?: string;
  detail?: string;
  requestId?: string;
  errors?: { field: string; message: string }[];
}

/** The server answered with an error status. */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly requestId: string | undefined;
  readonly fieldErrors: { field: string; message: string }[];

  constructor(status: number, body: ProblemBody | null) {
    super(body?.detail ?? `Request failed (${status})`);
    this.name = 'ApiError';
    this.status = status;
    this.code = body?.code ?? 'UNKNOWN';
    this.requestId = body?.requestId;
    this.fieldErrors = body?.errors ?? [];
  }

  get retryable(): boolean {
    return this.status >= 500 || this.status === 429 || this.status === 408;
  }
}

/** The request never got an answer (offline, DNS, timeout, CORS). Always retryable. */
export class NetworkError extends Error {
  constructor(message = 'Network unavailable') {
    super(message);
    this.name = 'NetworkError';
  }
}

/** The session is gone (refresh failed). The user must sign in again. */
export class SessionExpiredError extends Error {
  constructor() {
    super('Your session has expired. Please sign in again.');
    this.name = 'SessionExpiredError';
  }
}

export function messageOf(error: unknown): string {
  if (error instanceof Error) {
    return error.message;
  }
  return 'Something went wrong.';
}
