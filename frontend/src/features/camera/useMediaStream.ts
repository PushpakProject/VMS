import { useCallback, useEffect, useRef, useState } from 'react';
import { cameraError, toCameraError, type CameraError } from './cameraErrors';

export interface MediaStreamState {
  stream: MediaStream | null;
  error: CameraError | null;
  starting: boolean;
  retry: () => void;
}

interface Options {
  deviceId: string | null;
  hasDevices: boolean;
  width: number;
  height: number;
}

/**
 * Opens the selected camera (and microphone if there is one). Falls back to video-only when no
 * microphone is available, and reports camera unplug as an error.
 */
export function useMediaStream({ deviceId, hasDevices, width, height }: Options): MediaStreamState {
  const [stream, setStream] = useState<MediaStream | null>(null);
  const [error, setError] = useState<CameraError | null>(null);
  const [starting, setStarting] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const current = useRef<MediaStream | null>(null);

  useEffect(() => {
    let cancelled = false;
    const stop = () => {
      current.current?.getTracks().forEach((track) => track.stop());
      current.current = null;
    };

    const open = async () => {
      if (!window.isSecureContext || !navigator.mediaDevices?.getUserMedia) {
        setError(cameraError('INSECURE_CONTEXT'));
        return;
      }
      // Always ask the browser: Safari (and Chrome before permission) list no cameras until
      // getUserMedia has been granted, so an empty device list must not stop us from asking.
      setStarting(true);
      try {
        const media = await openCamera(deviceId, width, height);
        if (cancelled) {
          media.getTracks().forEach((track) => track.stop());
          return;
        }
        stop();
        current.current = media;
        media.getVideoTracks().forEach((track) =>
          track.addEventListener('ended', () => {
            setStream(null);
            setError(cameraError('DISCONNECTED'));
          }),
        );
        setStream(media);
        setError(null);
      } catch (e) {
        if (!cancelled) {
          setStream(null);
          setError(toCameraError(e));
        }
      } finally {
        if (!cancelled) {
          setStarting(false);
        }
      }
    };

    void open();
    return () => {
      cancelled = true;
    };
    // hasDevices: re-try when a camera is plugged in after an error.
  }, [deviceId, hasDevices, width, height, attempt]);

  useEffect(
    () => () => {
      current.current?.getTracks().forEach((track) => track.stop());
    },
    [],
  );

  const retry = useCallback(() => setAttempt((n) => n + 1), []);

  return { stream, error, starting, retry };
}

/**
 * Tries the chosen camera with and without microphone, then any camera: a remembered camera that
 * is no longer present is reported by browsers as OverconstrainedError / NotFoundError.
 */
async function openCamera(
  deviceId: string | null,
  width: number,
  height: number,
): Promise<MediaStream> {
  const base: MediaTrackConstraints = {
    width: { ideal: width },
    height: { ideal: height },
    frameRate: { ideal: 30 },
  };
  const videos = deviceId ? [{ ...base, deviceId: { exact: deviceId } }, base] : [base];
  let lastError: unknown = new DOMException('No camera', 'NotFoundError');
  for (const video of videos) {
    for (const audio of [true, false]) {
      try {
        return await navigator.mediaDevices.getUserMedia({ video, audio });
      } catch (error) {
        lastError = error;
      }
    }
  }
  throw lastError;
}
