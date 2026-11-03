/**
 * Recorder state machine (pure: no browser APIs, fully unit-tested).
 *
 * Scan-to-scan: a valid Tracking ID starts recording; the next one stops the current clip and
 * immediately starts a new one. Manual Stop and the max-duration timer also stop.
 * The returned commands are executed by the recording controller.
 */
export type RecorderPhase = 'idle' | 'ready' | 'recording';

export interface RecorderState {
  phase: RecorderPhase;
  activeTrackingId: string | null;
  lastTrackingId: string | null;
  notice: { kind: 'info' | 'warning' | 'error'; text: string } | null;
}

export type RecorderEvent =
  | { type: 'CAMERA_READY' }
  | { type: 'CAMERA_LOST' }
  | { type: 'TRACKING_ID_SUBMITTED'; trackingId: string }
  | { type: 'STOP_REQUESTED' }
  | { type: 'MAX_DURATION_REACHED'; maxDurationSec: number };

export type RecorderCommand =
  { type: 'START'; trackingId: string } | { type: 'STOP' } | { type: 'SWITCH'; trackingId: string };

export const TRACKING_ID_PATTERN = /^[A-Za-z0-9_-]{3,64}$/;

export const initialRecorderState: RecorderState = {
  phase: 'idle',
  activeTrackingId: null,
  lastTrackingId: null,
  notice: null,
};

export interface Transition {
  state: RecorderState;
  commands: RecorderCommand[];
}

export function recorderTransition(state: RecorderState, event: RecorderEvent): Transition {
  switch (event.type) {
    case 'CAMERA_READY':
      return {
        state: { ...state, phase: state.phase === 'idle' ? 'ready' : state.phase },
        commands: [],
      };

    case 'CAMERA_LOST':
      return {
        state: {
          ...state,
          phase: 'idle',
          activeTrackingId: null,
          notice:
            state.phase === 'recording'
              ? { kind: 'error', text: 'Camera disconnected. The recording so far was saved.' }
              : state.notice,
        },
        commands: state.phase === 'recording' ? [{ type: 'STOP' }] : [],
      };

    case 'TRACKING_ID_SUBMITTED': {
      const trackingId = event.trackingId.trim();
      if (!TRACKING_ID_PATTERN.test(trackingId)) {
        return {
          state: {
            ...state,
            notice: {
              kind: 'error',
              text: 'Tracking ID must be 3–64 letters, digits, "-" or "_".',
            },
          },
          commands: [],
        };
      }
      if (state.phase === 'idle') {
        return {
          state: { ...state, notice: { kind: 'error', text: 'Select a working camera first.' } },
          commands: [],
        };
      }
      const current = state.phase === 'recording' ? state.activeTrackingId : state.lastTrackingId;
      if (trackingId === current) {
        return {
          state: {
            ...state,
            notice: { kind: 'info', text: `${trackingId} was just scanned — ignored.` },
          },
          commands: [],
        };
      }
      const next: RecorderState = {
        phase: 'recording',
        activeTrackingId: trackingId,
        lastTrackingId: trackingId,
        notice:
          state.phase === 'recording'
            ? { kind: 'info', text: `Saved ${state.activeTrackingId}. Recording ${trackingId}.` }
            : { kind: 'info', text: `Recording ${trackingId}.` },
      };
      return {
        state: next,
        commands: [
          state.phase === 'recording'
            ? { type: 'SWITCH', trackingId }
            : { type: 'START', trackingId },
        ],
      };
    }

    case 'STOP_REQUESTED':
      if (state.phase !== 'recording') {
        return { state, commands: [] };
      }
      return {
        state: {
          ...state,
          phase: 'ready',
          activeTrackingId: null,
          notice: {
            kind: 'info',
            text: `Saved ${state.activeTrackingId}. Scan the next Tracking ID.`,
          },
        },
        commands: [{ type: 'STOP' }],
      };

    case 'MAX_DURATION_REACHED':
      if (state.phase !== 'recording') {
        return { state, commands: [] };
      }
      return {
        state: {
          ...state,
          phase: 'ready',
          activeTrackingId: null,
          notice: {
            kind: 'warning',
            text: `Maximum ${formatDuration(event.maxDurationSec * 1000)} reached — scan the next Tracking ID.`,
          },
        },
        commands: [{ type: 'STOP' }],
      };
  }
}

export function formatDuration(ms: number): string {
  const totalSeconds = Math.max(0, Math.floor(ms / 1000));
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${seconds.toString().padStart(2, '0')}`;
}
