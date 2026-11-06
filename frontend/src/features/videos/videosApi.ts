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

/** Fetches every page of the current filters and returns all videos. Used for CSV export. */
export async function fetchAllVideos(
  api: ApiClient,
  filters: Omit<VideoFilters, 'page'>,
): Promise<Video[]> {
  const all: Video[] = [];
  let page = 0;
  for (;;) {
    const result = await listVideos(api, { ...filters, page });
    all.push(...result.content);
    if (page + 1 >= result.totalPages) break;
    page++;
  }
  return all;
}

/** Serialises a list of videos as a CSV string with RFC 4180 quoting. */
export function toCsv(videos: Video[]): string {
  const headers = [
    'Tracking ID',
    'Status',
    'Duration',
    'Size (MB)',
    'Recorded At',
    'Processed At',
    'Failure Code',
    'Failure Message',
  ];
  const quote = (v: string) => `"${v.replace(/"/g, '""')}"`;
  const rows = videos.map((v) => [
    v.trackingId,
    v.status,
    v.durationMs !== null ? formatDuration(v.durationMs) : '',
    v.sizeBytes !== null ? (v.sizeBytes / 1_048_576).toFixed(1) : '',
    v.recordedAt ? new Date(v.recordedAt).toLocaleString() : '',
    v.processedAt ? new Date(v.processedAt).toLocaleString() : '',
    v.failureCode ?? '',
    v.failureMessage ?? '',
  ]);
  return [headers, ...rows].map((r) => r.map(String).map(quote).join(',')).join('\r\n');
}

function formatDuration(ms: number): string {
  const totalSeconds = Math.max(0, Math.floor(ms / 1000));
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${seconds.toString().padStart(2, '0')}`;
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
