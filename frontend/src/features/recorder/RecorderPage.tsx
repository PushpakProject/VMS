import { useEffect, useMemo, useSyncExternalStore } from 'react';
import { useServices } from '../../app/ServicesContext';
import { useCameraDevices } from '../camera/useCameraDevices';
import { useMediaStream } from '../camera/useMediaStream';
import { UploadQueuePanel } from '../uploads/UploadQueuePanel';
import { LivePreview } from './LivePreview';
import { createMediaRecorder, pickMimeType } from './mediaRecorderFactory';
import { RecordingController } from './recordingController';
import { TrackingIdInput } from './TrackingIdInput';

export function RecorderPage() {
  const { queue, config } = useServices();
  const cameras = useCameraDevices();
  const media = useMediaStream({
    deviceId: cameras.selectedId,
    hasDevices: cameras.devices.length > 0,
    width: config.videoWidth,
    height: config.videoHeight,
  });
  const mimeType = useMemo(() => pickMimeType(), []);

  const controller = useMemo(
    () =>
      mimeType
        ? new RecordingController({
            queue,
            mimeType,
            maxDurationSec: config.maxDurationSec,
            warnAtSec: config.warnAtSec,
            createRecorder: (stream, type) =>
              createMediaRecorder(stream, type, { videoBitsPerSecond: config.videoBitsPerSecond }),
          })
        : null,
    [queue, mimeType, config],
  );
  useEffect(() => () => controller?.dispose(), [controller]);

  useEffect(() => {
    controller?.setStream(media.stream);
    // Permission was granted: device labels become available.
    if (media.stream) {
      void cameras.refresh();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [controller, media.stream]);

  const idleSnapshot = useMemo(
    () => ({
      machine: {
        phase: 'idle' as const,
        activeTrackingId: null,
        lastTrackingId: null,
        notice: null,
      },
      startedAt: null,
      elapsedMs: 0,
      warning: false,
    }),
    [],
  );
  const snapshot = useSyncExternalStore(
    controller ? controller.subscribe : () => () => undefined,
    controller ? controller.getSnapshot : () => idleSnapshot,
  );
  const { machine } = snapshot;
  const recording = machine.phase === 'recording';

  if (!mimeType) {
    return (
      <main className="page">
        <div className="alert alert-error">
          This browser cannot record video. Please use the latest Chrome or Edge.
        </div>
      </main>
    );
  }

  return (
    <main className="page recorder">
      <section className="card recorder-main">
        <div className="camera-bar">
          <label className="camera-select">
            Camera
            <select
              value={cameras.selectedId ?? ''}
              onChange={(e) => cameras.select(e.target.value)}
              disabled={recording || cameras.devices.length === 0}
            >
              {cameras.devices.length === 0 && <option value="">No camera found</option>}
              {cameras.devices.map((device, index) => (
                <option key={device.deviceId || index} value={device.deviceId}>
                  {device.label || `Camera ${index + 1}`}
                </option>
              ))}
            </select>
          </label>
          {recording && (
            <button className="btn btn-danger" onClick={() => controller?.stop()}>
              Stop
            </button>
          )}
        </div>

        {media.error && (
          <div className="alert alert-error" role="alert">
            <strong>{media.error.message}</strong> {media.error.hint}
            {media.error.detail && <span className="small muted">({media.error.detail})</span>}
            <button className="btn btn-small" onClick={media.retry}>
              Retry
            </button>
          </div>
        )}
        {media.starting && <p className="muted">Starting camera…</p>}

        <LivePreview
          stream={media.stream}
          recording={recording}
          trackingId={machine.activeTrackingId}
          elapsedMs={snapshot.elapsedMs}
          maxDurationSec={config.maxDurationSec}
          warning={snapshot.warning}
        />

        <TrackingIdInput
          disabled={!media.stream}
          onSubmit={(trackingId) => controller?.submitTrackingId(trackingId)}
        />
        {machine.notice && (
          <div className={`alert alert-${machine.notice.kind}`} role="status">
            {machine.notice.text}
          </div>
        )}
      </section>
      <UploadQueuePanel />
    </main>
  );
}
