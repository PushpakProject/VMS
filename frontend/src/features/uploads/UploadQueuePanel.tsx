import { useEffect, useState } from 'react';
import { useServices } from '../../app/ServicesContext';
import { StatusBadge } from '../../components/StatusBadge';
import { formatDuration } from '../recorder/recorderMachine';
import type { UploadJob } from './uploadJob';
import { useUploadQueue } from './useUploadQueue';

const QUOTA_WARNING_RATIO = 0.8;

function formatBytes(bytes: number): string {
  if (bytes < 1024 * 1024) {
    return `${Math.round(bytes / 1024)} KB`;
  }
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function useStorageWarning(jobCount: number): string | null {
  const [warning, setWarning] = useState<string | null>(null);
  useEffect(() => {
    navigator.storage
      ?.estimate?.()
      .then(({ usage = 0, quota = 0 }) => {
        setWarning(
          quota > 0 && usage / quota > QUOTA_WARNING_RATIO
            ? `Device storage is ${Math.round((usage / quota) * 100)}% full. Reconnect so queued videos can upload.`
            : null,
        );
      })
      .catch(() => setWarning(null));
  }, [jobCount]);
  return warning;
}

/** Recordings still on this device, with their upload state and manual controls. */
export function UploadQueuePanel() {
  const { queue } = useServices();
  const { jobs, othersCount, paused } = useUploadQueue();
  const storageWarning = useStorageWarning(jobs.length);
  const [preview, setPreview] = useState<{ url: string; trackingId: string } | null>(null);

  useEffect(
    () => () => {
      if (preview) {
        URL.revokeObjectURL(preview.url);
      }
    },
    [preview],
  );

  const openPreview = async (job: UploadJob) => {
    const blob = await queue.previewBlob(job);
    setPreview({ url: URL.createObjectURL(blob), trackingId: job.trackingId });
  };

  return (
    <section className="card" aria-labelledby="queue-title">
      <h2 id="queue-title">On this device ({jobs.length})</h2>
      {paused && <div className="alert alert-warning">Sign in again to continue uploading.</div>}
      {storageWarning && <div className="alert alert-warning">{storageWarning}</div>}
      {othersCount > 0 && (
        <p className="muted small">
          {othersCount} recording(s) from another user are waiting on this device.
        </p>
      )}
      {jobs.length === 0 ? (
        <p className="muted">All recordings are uploaded.</p>
      ) : (
        <ul className="queue">
          {jobs.map((job) => (
            <li key={job.localId} className="queue-item">
              <div className="queue-row">
                <strong className="mono">{job.trackingId}</strong>
                <StatusBadge status={job.recording ? 'RECORDING' : job.state} />
              </div>
              <div className="queue-row muted small">
                <span>{new Date(job.recordedAt).toLocaleTimeString()}</span>
                <span>
                  {formatBytes(job.bytesRecorded)}
                  {job.durationMs !== null && ` · ${formatDuration(job.durationMs)}`}
                </span>
              </div>
              {job.state === 'UPLOADING' && (
                <progress max={1} value={job.progress} aria-label="Upload progress" />
              )}
              {job.lastError && job.state !== 'UPLOADED_REMOTE' && (
                <p className="small error-text">{job.lastError}</p>
              )}
              {!job.recording && (
                <div className="queue-actions">
                  <button className="btn btn-small" onClick={() => void openPreview(job)}>
                    Preview
                  </button>
                  {job.state === 'FAILED' && (
                    <>
                      <button
                        className="btn btn-small"
                        onClick={() => void queue.retry(job.localId)}
                      >
                        Retry
                      </button>
                      <button
                        className="btn btn-small btn-danger"
                        onClick={() => {
                          if (window.confirm(`Discard the local recording ${job.trackingId}?`)) {
                            void queue.discard(job.localId);
                          }
                        }}
                      >
                        Discard
                      </button>
                    </>
                  )}
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
      {preview && (
        <div className="modal" role="dialog" aria-label={`Local preview ${preview.trackingId}`}>
          <div className="modal-body">
            <h3>Local preview · {preview.trackingId}</h3>
            <p className="muted small">Not watermarked yet — stored only on this device.</p>
            <video src={preview.url} controls autoPlay className="player" />
            <button className="btn" onClick={() => setPreview(null)}>
              Close
            </button>
          </div>
        </div>
      )}
    </section>
  );
}
