import type { ApiClient } from '../../lib/apiClient';
import type { UploadTarget } from './uploadJob';

export interface InitiateUploadResponse {
  videoId: string;
  status: string;
  upload: UploadTarget | null;
}

export interface VideoStatusResponse {
  id: string;
  status: string;
  failureCode: string | null;
  failureMessage: string | null;
}

/** The slice of the API the upload queue needs (a port, easy to fake in tests). */
export interface UploadsApi {
  initiate(
    localId: string,
    body: { trackingId: string; contentType: string; sizeBytes: number; recordedAt: string },
  ): Promise<InitiateUploadResponse>;
  complete(videoId: string, durationMs: number | null): Promise<VideoStatusResponse>;
  status(videoId: string): Promise<VideoStatusResponse>;
}

export function createUploadsApi(client: ApiClient): UploadsApi {
  return {
    initiate: (localId, body) =>
      client.request('/api/v1/videos', {
        method: 'POST',
        headers: { 'Idempotency-Key': localId },
        body,
      }),
    complete: (videoId, durationMs) =>
      client.request(`/api/v1/videos/${encodeURIComponent(videoId)}/complete`, {
        method: 'POST',
        body: { durationMs },
      }),
    status: (videoId) => client.request(`/api/v1/videos/${encodeURIComponent(videoId)}`),
  };
}
