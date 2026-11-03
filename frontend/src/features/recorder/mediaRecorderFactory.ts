/** Preferred formats, best first. Chrome/Edge record WebM; Safari records MP4. */
export const MIME_CANDIDATES = [
  'video/webm;codecs=vp9,opus',
  'video/webm;codecs=vp8,opus',
  'video/webm',
  'video/mp4;codecs=avc1,mp4a',
  'video/mp4',
];

export function pickMimeType(
  isSupported: (type: string) => boolean = (type) =>
    typeof MediaRecorder !== 'undefined' && MediaRecorder.isTypeSupported(type),
): string | null {
  return MIME_CANDIDATES.find((type) => isSupported(type)) ?? null;
}

export interface RecorderFactoryOptions {
  videoBitsPerSecond: number;
  audioBitsPerSecond?: number;
}

/** Factory: hides format negotiation and bitrate settings from the controller. */
export function createMediaRecorder(
  stream: MediaStream,
  mimeType: string,
  options: RecorderFactoryOptions,
): MediaRecorder {
  return new MediaRecorder(stream, {
    mimeType,
    videoBitsPerSecond: options.videoBitsPerSecond,
    audioBitsPerSecond: options.audioBitsPerSecond ?? 128_000,
  });
}
