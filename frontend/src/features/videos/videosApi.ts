import type { ApiClient } from '../../lib/apiClient';

export interface VideoAttempt {
  run: number;
  attempt: number;
  status: string;
  errorCode: string | null;
  errorMessage: string | null;
  startedAt: string;
  finishedAt: string | null;
}

export interface Video {
  id: string;
  trackingId: string;
  status: 'UPLOADING' | 'UPLOADED' | 'PROCESSING' | 'READY' | 'FAILED' | 'ABANDONED';
  contentType: string;
  sizeBytes: number | null;
  durationMs: number | null;
  failureCode: string | null;
  failureMessage: string | null;
  retryable: boolean;
  recordedAt: string | null;
  uploadedAt: string | null;
  processedAt: string | null;
  createdAt: string;
  attempts: VideoAttempt[];
}

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface VideoFilters {
  page: number;
  trackingId: string;
  status: string;
}

export const TERMINAL_STATUSES = ['READY', 'FAILED', 'ABANDONED'];

export function listVideos(api: ApiClient, filters: VideoFilters): Promise<Page<Video>> {
  const params = new URLSearchParams({ page: String(filters.page), size: '20' });
  if (filters.trackingId) {
    params.set('trackingId', filters.trackingId);
  }
  if (filters.status) {
    params.set('status', filters.status);
  }
  return api.request(`/api/v1/videos?${params.toString()}`);
}

export function getVideo(api: ApiClient, id: string): Promise<Video> {
  return api.request(`/api/v1/videos/${encodeURIComponent(id)}`);
}

export function getPlaybackUrl(
  api: ApiClient,
  id: string,
): Promise<{ url: string; expiresAt: string }> {
  return api.request(`/api/v1/videos/${encodeURIComponent(id)}/playback-url`);
}

export function retryVideo(api: ApiClient, id: string): Promise<Video> {
  return api.request(`/api/v1/videos/${encodeURIComponent(id)}/retry`, { method: 'POST' });
}

export function reprocessVideo(api: ApiClient, id: string): Promise<Video> {
  return api.request(`/api/v1/admin/videos/${encodeURIComponent(id)}/reprocess`, {
    method: 'POST',
  });
}

export function deleteVideo(api: ApiClient, id: string): Promise<void> {
  return api.request(`/api/v1/videos/${encodeURIComponent(id)}`, { method: 'DELETE' });
}
