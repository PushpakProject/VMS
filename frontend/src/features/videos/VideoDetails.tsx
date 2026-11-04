import { useQuery } from '@tanstack/react-query';
import { useServices } from '../../app/ServicesContext';
import { StatusBadge } from '../../components/StatusBadge';
import { messageOf } from '../../lib/errors';
import { formatDuration } from '../recorder/recorderMachine';
import { TERMINAL_STATUSES, getVideo } from './videosApi';

/** Processing history of one video: every watermark attempt, per processing run. */
export function VideoDetails({ videoId, onClose }: { videoId: string; onClose: () => void }) {
  const { api } = useServices();
  const details = useQuery({
    queryKey: ['video', videoId],
    queryFn: () => getVideo(api, videoId),
    refetchInterval: (query) =>
      query.state.data && !TERMINAL_STATUSES.includes(query.state.data.status) ? 3_000 : false,
  });
  const video = details.data;

  return (
    <div className="modal" role="dialog" aria-label="Video details">
      <div className="modal-body">
        {details.isPending && <p className="muted">Loading…</p>}
        {details.isError && (
          <div className="alert alert-error">
            Could not load details: {messageOf(details.error)}
          </div>
        )}
        {video && (
          <>
            <h3>
              <span className="mono">{video.trackingId}</span> <StatusBadge status={video.status} />
            </h3>
            <dl className="details">
              <dt>Recorded</dt>
              <dd>{new Date(video.recordedAt ?? video.createdAt).toLocaleString()}</dd>
              <dt>Duration</dt>
              <dd>{video.durationMs !== null ? formatDuration(video.durationMs) : '—'}</dd>
              <dt>Size</dt>
              <dd>
                {video.sizeBytes !== null ? `${(video.sizeBytes / 1_048_576).toFixed(1)} MB` : '—'}
              </dd>
              {video.failureMessage && (
                <>
                  <dt>Failure</dt>
                  <dd className="error-text">
                    {video.failureMessage} ({video.failureCode})
                  </dd>
                </>
              )}
            </dl>
            <h4>Watermark attempts</h4>
            {video.attempts.length === 0 ? (
              <p className="muted small">No attempts yet.</p>
            ) : (
              <table className="videos">
                <thead>
                  <tr>
                    <th>Run</th>
                    <th>Attempt</th>
                    <th>Status</th>
                    <th>Started</th>
                    <th>Finished</th>
                    <th>Error</th>
                  </tr>
                </thead>
                <tbody>
                  {video.attempts.map((a) => (
                    <tr key={`${a.run}-${a.attempt}`}>
                      <td>{a.run}</td>
                      <td>{a.attempt}</td>
                      <td>
                        <StatusBadge status={a.status === 'COMPLETED' ? 'READY' : a.status} />
                      </td>
                      <td>{new Date(a.startedAt).toLocaleTimeString()}</td>
                      <td>{a.finishedAt ? new Date(a.finishedAt).toLocaleTimeString() : '—'}</td>
                      <td className="small">{a.errorMessage ?? '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </>
        )}
        <button className="btn" onClick={onClose}>
          Close
        </button>
      </div>
    </div>
  );
}
