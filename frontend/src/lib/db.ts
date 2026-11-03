import { openDB, type DBSchema, type IDBPDatabase } from 'idb';
import type { UploadJob } from '../features/uploads/uploadJob';

/** Chunks are stored as ArrayBuffers: portable across browsers (older Safari can't store Blobs). */
export interface ChunkRecord {
  localId: string;
  seq: number;
  data: ArrayBuffer;
}

interface VmsDb extends DBSchema {
  jobs: {
    key: string;
    value: UploadJob;
    indexes: { byOwner: string };
  };
  chunks: {
    key: [string, number];
    value: ChunkRecord;
    indexes: { byLocalId: string };
  };
}

export type VmsDatabase = IDBPDatabase<VmsDb>;

const DB_NAME = 'vms';
const DB_VERSION = 1;

/**
 * IndexedDB holds every recording chunk until the server reports the video READY, so nothing is
 * lost on refresh, crash or network loss.
 */
export function openVmsDb(name = DB_NAME): Promise<VmsDatabase> {
  return openDB<VmsDb>(name, DB_VERSION, {
    upgrade(db) {
      const jobs = db.createObjectStore('jobs', { keyPath: 'localId' });
      jobs.createIndex('byOwner', 'ownerUserId');
      const chunks = db.createObjectStore('chunks', { keyPath: ['localId', 'seq'] });
      chunks.createIndex('byLocalId', 'localId');
    },
  });
}
