import { useSyncExternalStore } from 'react';
import { useServices } from '../../app/ServicesContext';
import type { Connectivity } from './connectivityMonitor';

export function useConnectivity(): Connectivity {
  const { connectivity } = useServices();
  return useSyncExternalStore(
    (onChange) => connectivity.subscribe(onChange),
    () => connectivity.current,
  );
}
