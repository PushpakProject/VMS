import { apiClient, type ApiClient } from '../lib/apiClient';
import { openVmsDb } from '../lib/db';
import { ExponentialBackoff } from '../lib/retryPolicy';
import { ConnectivityMonitor } from '../features/connectivity/connectivityMonitor';
import { UploadJobRepository } from '../features/uploads/uploadJobRepository';
import { UploadQueue } from '../features/uploads/uploadQueue';
import { ProtocolRoutingTransport } from '../features/uploads/resumableTransport';
import { createUploadsApi } from '../features/uploads/uploadsApi';

export interface Services {
  api: ApiClient;
  connectivity: ConnectivityMonitor;
  queue: UploadQueue;
}

async function probe(): Promise<boolean> {
  try {
    const response = await fetch('/api/v1/ping', { cache: 'no-store' });
    return response.ok;
  } catch {
    return false;
  }
}

let services: Promise<Services> | null = null;

/** Composition root: wires the adapters together once per tab. */
export function getServices(): Promise<Services> {
  if (!services) {
    services = (async () => {
      const connectivity = new ConnectivityMonitor({
        probe,
        initial: navigator.onLine ? 'ONLINE' : 'OFFLINE',
      });
      apiClient.onNetworkResult((ok) =>
        ok ? connectivity.reportSuccess() : connectivity.reportFailure(),
      );
      window.addEventListener('online', () => connectivity.browserWentOnline());
      window.addEventListener('offline', () => connectivity.browserWentOffline());

      const db = await openVmsDb();
      if (navigator.storage?.persist) {
        void navigator.storage.persist();
      }
      const queue = new UploadQueue({
        repo: new UploadJobRepository(db),
        api: createUploadsApi(apiClient),
        transport: new ProtocolRoutingTransport(),
        connectivity,
        retryPolicy: new ExponentialBackoff(),
      });
      return { api: apiClient, connectivity, queue };
    })();
  }
  return services;
}
