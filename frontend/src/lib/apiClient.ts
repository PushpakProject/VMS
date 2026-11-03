import { ApiError, NetworkError, SessionExpiredError, type ProblemBody } from './errors';

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'DELETE';
  body?: unknown;
  headers?: Record<string, string>;
  /** Attach the access token and transparently refresh it once on 401. */
  auth?: boolean;
  timeoutMs?: number;
}

export interface AuthPayload {
  accessToken: string;
  expiresIn: number;
  user: { id: string; email: string; displayName: string; role: string };
}

type Listener = () => void;

const DEFAULT_TIMEOUT_MS = 15_000;

/**
 * Thin fetch wrapper. The access token lives only in memory (never localStorage). The refresh
 * token is an httpOnly cookie the browser sends only to /api/v1/auth. Concurrent 401s trigger a
 * single refresh (single-flight).
 */
export class ApiClient {
  private accessToken: string | null = null;
  private refreshing: Promise<AuthPayload> | null = null;
  private readonly sessionListeners = new Set<Listener>();
  private readonly networkListeners = new Set<(ok: boolean) => void>();

  setAccessToken(token: string | null): void {
    this.accessToken = token;
  }

  hasSession(): boolean {
    return this.accessToken !== null;
  }

  /** Called when the session can't be recovered (refresh failed). */
  onSessionLost(listener: Listener): () => void {
    this.sessionListeners.add(listener);
    return () => this.sessionListeners.delete(listener);
  }

  /** Observer hook for the connectivity monitor: reports every request's network outcome. */
  onNetworkResult(listener: (ok: boolean) => void): () => void {
    this.networkListeners.add(listener);
    return () => this.networkListeners.delete(listener);
  }

  async request<T>(path: string, options: RequestOptions = {}): Promise<T> {
    const { auth = true } = options;
    try {
      return await this.send<T>(path, options);
    } catch (error) {
      if (auth && error instanceof ApiError && error.status === 401) {
        await this.refresh();
        return this.send<T>(path, options);
      }
      throw error;
    }
  }

  /** Exchanges the refresh cookie for a new access token. Single-flight across callers. */
  refresh(): Promise<AuthPayload> {
    if (!this.refreshing) {
      this.refreshing = this.send<AuthPayload>('/api/v1/auth/refresh', {
        method: 'POST',
        auth: false,
        headers: { 'X-Requested-With': 'XMLHttpRequest' },
      })
        .then((payload) => {
          this.accessToken = payload.accessToken;
          return payload;
        })
        .catch((error: unknown) => {
          if (error instanceof ApiError && (error.status === 401 || error.status === 403)) {
            this.accessToken = null;
            this.sessionListeners.forEach((listener) => listener());
            throw new SessionExpiredError();
          }
          throw error;
        })
        .finally(() => {
          this.refreshing = null;
        });
    }
    return this.refreshing;
  }

  private async send<T>(path: string, options: RequestOptions): Promise<T> {
    const {
      method = 'GET',
      body,
      headers = {},
      auth = true,
      timeoutMs = DEFAULT_TIMEOUT_MS,
    } = options;
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    const finalHeaders: Record<string, string> = { Accept: 'application/json', ...headers };
    if (body !== undefined) {
      finalHeaders['Content-Type'] = 'application/json';
    }
    if (auth && this.accessToken) {
      finalHeaders.Authorization = `Bearer ${this.accessToken}`;
    }
    let response: Response;
    try {
      response = await fetch(path, {
        method,
        headers: finalHeaders,
        body: body === undefined ? undefined : JSON.stringify(body),
        credentials: 'same-origin',
        signal: controller.signal,
      });
    } catch {
      this.networkListeners.forEach((listener) => listener(false));
      throw new NetworkError();
    } finally {
      clearTimeout(timer);
    }
    this.networkListeners.forEach((listener) => listener(response.status < 500));
    if (!response.ok) {
      throw new ApiError(response.status, await readProblem(response));
    }
    if (response.status === 204) {
      return undefined as T;
    }
    return (await response.json()) as T;
  }
}

async function readProblem(response: Response): Promise<ProblemBody | null> {
  try {
    return (await response.json()) as ProblemBody;
  } catch {
    return null;
  }
}

export const apiClient = new ApiClient();
