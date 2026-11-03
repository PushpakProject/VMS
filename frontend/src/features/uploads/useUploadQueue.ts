import { useEffect, useState } from 'react';
import { useServices } from '../../app/ServicesContext';
import type { QueueSnapshot } from './uploadQueue';

const EMPTY: QueueSnapshot = { jobs: [], othersCount: 0, paused: false };

export function useUploadQueue(): QueueSnapshot {
  const { queue } = useServices();
  const [snapshot, setSnapshot] = useState<QueueSnapshot>(EMPTY);
  useEffect(() => queue.subscribe(setSnapshot), [queue]);
  return snapshot;
}
