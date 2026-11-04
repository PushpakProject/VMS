import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { useServices } from '../../app/ServicesContext';
import { useAuth } from '../auth/AuthContext';
import { StatusBadge } from '../../components/StatusBadge';
import { messageOf } from '../../lib/errors';
import { formatDuration } from '../recorder/recorderMachine';
import {
  TERMINAL_STATUSES,
  deleteVideo,
  getPlaybackUrl,
  listVideos,
  reprocessVideo,
  retryVideo,
  type Video,
} from './videosApi';
import { VideoDetails } from './VideoDetails';

const STATUSES = ['', 'UPLOADING', 'UPLOADED', 'PROCESSING', 'READY', 'FAILED', 'ABANDONED'];
const TRACKING_FILTER = /^[A-Za-z0-9_-]{0,64}$/;

function useDebounced<T>(value: T, delayMs: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delayMs);
    return () => clearTimeout(timer);
  }, [value, delayMs]);
  return debounced;
}

export function VideosPage() {
  const { api } = useServices();
  const { user } = useAuth();
  const isAdmin = user?.role === 'ADMIN';
  const queryClient = useQueryClient();
  const [detailsId, setDetailsId] = useState<string | null>(null);
  const [page, setPage] = useState(0);
  const [trackingInput, setTrackingInput] = useState('');
  const [status, setStatus] = useState('');
  const trackingId = useDebounced(trackingInput, 300);
  const [player, setPlayer] = useState<{ url: string; trackingId: string } | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  const videos = useQuery({
    queryKey: ['videos', page, trackingId, status],
    queryFn: () => listVideos(api, { page, trackingId, status }),
    placeholderData: keepPreviousData,
    // Poll only while something is still being uploaded or watermarked.
    refetchInterval: (query) =>
      query.state.data?.content.some((v) => !TERMINAL_STATUSES.includes(v.status)) ? 5_000 : false,
  });

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['videos'] });
  const retry = useMutation({
    mutationFn: (id: string) => retryVideo(api, id),
    onSuccess: invalidate,
    onError: (e) => setActionError(messageOf(e)),
  });
  const reprocess = useMutation({
    mutationFn: (id: string) => reprocessVideo(api, id),
    onSuccess: invalidate,
    onError: (e) => setActionError(messageOf(e)),
  });
  const remove = useMutation({
    mutationFn: (id: string) => deleteVideo(api, id),
    onSuccess: invalidate,
    onError: (e) => setActionError(messageOf(e)),
  });

  const play = async (video: Video) => {
    setActionError(null);
    try {
      const { url } = await getPlaybackUrl(api, video.id);
      setPlayer({ url, trackingId: video.trackingId });
    } catch (e) {
      setActionError(messageOf(e));
    }
  };

  const data = videos.data;

  return (
    <main className="page">
      <section className="card">
        <div className="list-header">
          <h1>Uploaded videos</h1>
          <div className="filters">
            <label>
              Tracking ID
              <input
                className="mono"
                value={trackingInput}
                onChange={(e) => {
                  if (TRACKING_FILTER.test(e.target.value)) {
                    setTrackingInput(e.target.value);
                    setPage(0);
                  }
                }}
                placeholder="Search"
              />
            </label>
            <label>
              Status
              <select
                value={status}
                onChange={(e) => {
                  setStatus(e.target.value);
                  setPage(0);
                }}
              >
                {STATUSES.map((s) => (
                  <option key={s} value={s}>
                    {s ? s.charAt(0) + s.slice(1).toLowerCase() : 'All'}
                  </option>
                ))}
              </select>
            </label>
          </div>
        </div>

        {actionError && (
          <div className="alert alert-error" role="alert">
            {actionError}
          </div>
        )}
        {videos.isError && (
          <div className="alert alert-error" role="alert">
            Could not load videos: {messageOf(videos.error)}
          </div>
        )}
        {videos.isPending && <p className="muted">Loading…</p>}
        {data && data.content.length === 0 && <p className="muted">No videos yet.</p>}

        {data && data.content.length > 0 && (
          <table className="videos">
            <thead>
              <tr>
                <th>Tracking ID</th>
                <th>Recorded</th>
                <th>Duration</th>
                <th>Status</th>
                <th aria-label="Actions" />
              </tr>
            </thead>
            <tbody>
              {data.content.map((video) => (
                <tr key={video.id}>
                  <td className="mono">{video.trackingId}</td>
                  <td>{new Date(video.recordedAt ?? video.createdAt).toLocaleString()}</td>
                  <td>{video.durationMs !== null ? formatDuration(video.durationMs) : '—'}</td>
                  <td>
                    <StatusBadge status={video.status} />
                    {video.failureMessage && (
                      <div className="small error-text">{video.failureMessage}</div>
                    )}
                  </td>
                  <td className="row-actions">
                    {video.status === 'READY' && (
                      <button
                        className="btn btn-small btn-primary"
                        onClick={() => void play(video)}
                      >
                        Play
                      </button>
                    )}
                    {video.status === 'FAILED' && video.retryable && (
                      <button
                        className="btn btn-small"
                        disabled={retry.isPending}
                        onClick={() => retry.mutate(video.id)}
                      >
                        Retry
                      </button>
                    )}
                    {isAdmin && video.status === 'FAILED' && (
                      <button
                        className="btn btn-small"
                        disabled={reprocess.isPending}
                        onClick={() => reprocess.mutate(video.id)}
                      >
                        Reprocess
                      </button>
                    )}
                    <button className="btn btn-small" onClick={() => setDetailsId(video.id)}>
                      Details
                    </button>
                    <button
                      className="btn btn-small btn-danger"
                      disabled={remove.isPending}
                      onClick={() => {
                        if (window.confirm(`Delete video ${video.trackingId}?`)) {
                          remove.mutate(video.id);
                        }
                      }}
                    >
                      Delete
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        {data && data.totalPages > 1 && (
          <div className="pager">
            <button
              className="btn btn-small"
              disabled={page === 0}
              onClick={() => setPage(page - 1)}
            >
              Previous
            </button>
            <span className="muted">
              Page {data.page + 1} of {data.totalPages}
            </span>
            <button
              className="btn btn-small"
              disabled={page + 1 >= data.totalPages}
              onClick={() => setPage(page + 1)}
            >
              Next
            </button>
          </div>
        )}
      </section>

      {detailsId && <VideoDetails videoId={detailsId} onClose={() => setDetailsId(null)} />}

      {player && (
        <div className="modal" role="dialog" aria-label={`Video ${player.trackingId}`}>
          <div className="modal-body">
            <h3 className="mono">{player.trackingId}</h3>
            <video src={player.url} controls autoPlay className="player" />
            <button className="btn" onClick={() => setPlayer(null)}>
              Close
            </button>
          </div>
        </div>
      )}
    </main>
  );
}
