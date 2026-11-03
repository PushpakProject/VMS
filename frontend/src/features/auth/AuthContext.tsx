import { useQueryClient } from '@tanstack/react-query';
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import { useServices } from '../../app/ServicesContext';
import type { AuthPayload } from '../../lib/apiClient';
import { NetworkError } from '../../lib/errors';

export type AuthUser = AuthPayload['user'];

type Status = 'loading' | 'anonymous' | 'authenticated' | 'offline';

interface AuthContextValue {
  status: Status;
  user: AuthUser | null;
  login: (login: string, password: string) => Promise<void>;
  loginWithGoogle: (idToken: string) => Promise<void>;
  logout: () => Promise<void>;
}

const AuthCtx = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const { api, queue } = useServices();
  const queryClient = useQueryClient();
  const [status, setStatus] = useState<Status>('loading');
  const [user, setUser] = useState<AuthUser | null>(null);

  const establish = useCallback(
    async (payload: AuthPayload) => {
      api.setAccessToken(payload.accessToken);
      setUser(payload.user);
      setStatus('authenticated');
      await queue.setOwner(payload.user.id);
    },
    [api, queue],
  );

  useEffect(() => {
    let cancelled = false;
    api
      .refresh()
      .then(async (payload) => {
        if (!cancelled) {
          await establish(payload);
        }
      })
      .catch((error: unknown) => {
        if (!cancelled) {
          setStatus(error instanceof NetworkError ? 'offline' : 'anonymous');
        }
      });
    const unsubscribe = api.onSessionLost(() => {
      setUser(null);
      setStatus('anonymous');
      void queue.setOwner(null);
    });
    return () => {
      cancelled = true;
      unsubscribe();
    };
  }, [api, queue, establish]);

  const login = useCallback(
    async (loginName: string, password: string) => {
      const payload = await api.request<AuthPayload>('/api/v1/auth/login', {
        method: 'POST',
        auth: false,
        body: { login: loginName, password },
      });
      await establish(payload);
    },
    [api, establish],
  );

  const loginWithGoogle = useCallback(
    async (idToken: string) => {
      const payload = await api.request<AuthPayload>('/api/v1/auth/google', {
        method: 'POST',
        auth: false,
        body: { idToken },
      });
      await establish(payload);
    },
    [api, establish],
  );

  const logout = useCallback(async () => {
    try {
      await api.request('/api/v1/auth/logout', {
        method: 'POST',
        auth: false,
        headers: { 'X-Requested-With': 'XMLHttpRequest' },
      });
    } finally {
      api.setAccessToken(null);
      await queue.setOwner(null);
      queryClient.clear();
      setUser(null);
      setStatus('anonymous');
    }
  }, [api, queue, queryClient]);

  const value = useMemo(
    () => ({ status, user, login, loginWithGoogle, logout }),
    [status, user, login, loginWithGoogle, logout],
  );
  return <AuthCtx.Provider value={value}>{children}</AuthCtx.Provider>;
}

export function useAuth(): AuthContextValue {
  const value = useContext(AuthCtx);
  if (!value) {
    throw new Error('useAuth must be used inside AuthProvider');
  }
  return value;
}
