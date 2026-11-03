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
      if (!hasDevices) {
        setStream(null);
        setError(cameraError('NO_CAMERA'));
        return;
      }
      setStarting(true);
      const video: MediaTrackConstraints = {
        width: { ideal: width },
        height: { ideal: height },
        frameRate: { ideal: 30 },
        ...(deviceId ? { deviceId: { exact: deviceId } } : {}),
      };
      try {
        let media: MediaStream;
        try {
          media = await navigator.mediaDevices.getUserMedia({ video, audio: true });
        } catch {
          media = await navigator.mediaDevices.getUserMedia({ video, audio: false });
        }
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
