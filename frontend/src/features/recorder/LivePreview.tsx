import { useEffect, useRef, useState } from 'react';
import { formatDuration } from './recorderMachine';

interface Props {
  stream: MediaStream | null;
  recording: boolean;
  trackingId: string | null;
  elapsedMs: number;
  maxDurationSec: number;
  warning: boolean;
}

/** Live camera view with REC overlay, countdown and picture-in-picture. */
export function LivePreview({
  stream,
  recording,
  trackingId,
  elapsedMs,
  maxDurationSec,
  warning,
}: Props) {
  const video = useRef<HTMLVideoElement>(null);
  const [inPip, setInPip] = useState(false);
  const pipSupported = typeof document !== 'undefined' && document.pictureInPictureEnabled;

  useEffect(() => {
    if (video.current) {
      video.current.srcObject = stream;
    }
  }, [stream]);

  useEffect(() => {
    const element = video.current;
    if (!element) {
      return;
    }
    const enter = () => setInPip(true);
    const leave = () => setInPip(false);
    element.addEventListener('enterpictureinpicture', enter);
    element.addEventListener('leavepictureinpicture', leave);
    return () => {
      element.removeEventListener('enterpictureinpicture', enter);
      element.removeEventListener('leavepictureinpicture', leave);
    };
  }, []);

  const togglePip = async () => {
    try {
      if (document.pictureInPictureElement) {
        await document.exitPictureInPicture();
      } else {
        await video.current?.requestPictureInPicture();
      }
    } catch {
      // PiP can be refused (e.g. no metadata yet); the inline view keeps working.
    }
  };

  const remainingMs = Math.max(0, maxDurationSec * 1000 - elapsedMs);

  return (
    <div className="preview">
      <video ref={video} className="preview-video" autoPlay muted playsInline />
      {!stream && <div className="preview-empty">No camera</div>}
      {recording && (
        <div className={`rec-overlay ${warning ? 'rec-warning' : ''}`} aria-live="polite">
          <span className="rec-dot" aria-hidden /> REC <span className="mono">{trackingId}</span>
          <span className="mono">
            {formatDuration(elapsedMs)} / {formatDuration(maxDurationSec * 1000)}
          </span>
          {warning && <span>{Math.ceil(remainingMs / 1000)} s left</span>}
        </div>
      )}
      {pipSupported && stream && (
        <button className="btn btn-small pip-button" onClick={() => void togglePip()}>
          {inPip ? 'Exit picture-in-picture' : 'Picture-in-picture'}
        </button>
      )}
    </div>
  );
}
