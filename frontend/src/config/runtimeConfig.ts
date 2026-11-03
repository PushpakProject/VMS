import { apiClient } from '../lib/apiClient';

/** Limits and settings served by the API so client and server never disagree. */
export interface RuntimeConfig {
  maxDurationSec: number;
  warnAtSec: number;
  maxUploadBytes: number;
  allowedContentTypes: string[];
  videoBitsPerSecond: number;
  videoWidth: number;
  videoHeight: number;
  googleClientId: string | null;
}

export const FALLBACK_CONFIG: RuntimeConfig = {
  maxDurationSec: 120,
  warnAtSec: 105,
  maxUploadBytes: 100 * 1024 * 1024,
  allowedContentTypes: ['video/webm', 'video/mp4'],
  videoBitsPerSecond: 2_500_000,
  videoWidth: 1280,
  videoHeight: 720,
  googleClientId: null,
};

export async function loadRuntimeConfig(): Promise<RuntimeConfig> {
  try {
    return await apiClient.request<RuntimeConfig>('/api/v1/config/public', { auth: false });
  } catch {
    // Offline at start-up: recording still works with the defaults; uploads resume later.
    return FALLBACK_CONFIG;
  }
}
