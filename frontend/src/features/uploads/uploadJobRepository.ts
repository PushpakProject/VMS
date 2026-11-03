import type { VmsDatabase } from '../../lib/db';
import type { UploadJob } from './uploadJob';

/** Repository over IndexedDB: jobs plus their recorded chunks. */
export class UploadJobRepository {
  constructor(private readonly db: VmsDatabase) {}

  save(job: UploadJob): Promise<string> {
    return this.db.put('jobs', job);
  }

  get(localId: string): Promise<UploadJob | undefined> {
    return this.db.get('jobs', localId);
  }

  async listByOwner(ownerUserId: string): Promise<UploadJob[]> {
    const jobs = await this.db.getAllFromIndex('jobs', 'byOwner', ownerUserId);
    return jobs.sort((a, b) => a.recordedAt.localeCompare(b.recordedAt));
  }

  /** Jobs of other users on this device are never uploaded; only their count is shown. */
  async countOthers(ownerUserId: string): Promise<number> {
    const all = await this.db.getAll('jobs');
    return all.filter((j) => j.ownerUserId !== ownerUserId && j.state !== 'DONE').length;
  }

  async appendChunk(localId: string, seq: number, blob: Blob): Promise<void> {
    const data = await blob.arrayBuffer();
    await this.db.put('chunks', { localId, seq, data });
  }

  async assembleBlob(localId: string, contentType: string): Promise<Blob> {
    const chunks = await this.db.getAllFromIndex('chunks', 'byLocalId', localId);
    chunks.sort((a, b) => a.seq - b.seq);
    return new Blob(
      chunks.map((c) => c.data),
      { type: contentType },
    );
  }

  async delete(localId: string): Promise<void> {
    const tx = this.db.transaction(['jobs', 'chunks'], 'readwrite');
    const keys = await tx.objectStore('chunks').index('byLocalId').getAllKeys(localId);
    await Promise.all([
      ...keys.map((key) => tx.objectStore('chunks').delete(key)),
      tx.objectStore('jobs').delete(localId),
      tx.done,
    ]);
  }
}
