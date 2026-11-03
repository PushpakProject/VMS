import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { loadRuntimeConfig, type RuntimeConfig } from '../config/runtimeConfig';
import { getServices, type Services } from './services';

interface AppContext extends Services {
  config: RuntimeConfig;
}

const Context = createContext<AppContext | null>(null);

export function ServicesProvider({ children }: { children: ReactNode }) {
  const [value, setValue] = useState<AppContext | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    Promise.all([getServices(), loadRuntimeConfig()])
      .then(([services, config]) => {
        if (!cancelled) {
          setValue({ ...services, config });
        }
      })
      .catch(() => {
        if (!cancelled) {
          setError('This browser cannot store recordings (IndexedDB unavailable).');
        }
      });
    return () => {
      cancelled = true;
    };
  }, []);

  if (error) {
    return <div className="centered alert alert-error">{error}</div>;
  }
  if (!value) {
    return <div className="centered muted">Loading…</div>;
  }
  return <Context.Provider value={value}>{children}</Context.Provider>;
}

export function useServices(): AppContext {
  const value = useContext(Context);
  if (!value) {
    throw new Error('useServices must be used inside ServicesProvider');
  }
  return value;
}
