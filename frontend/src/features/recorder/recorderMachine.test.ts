import {
  formatDuration,
  initialRecorderState,
  recorderTransition,
  type RecorderEvent,
  type RecorderState,
} from './recorderMachine';

function run(events: RecorderEvent[], start: RecorderState = initialRecorderState) {
  let state = start;
  const commands = [];
  for (const event of events) {
    const result = recorderTransition(state, event);
    state = result.state;
    commands.push(...result.commands);
  }
  return { state, commands };
}

describe('recorder state machine', () => {
  it('starts recording automatically when a valid Tracking ID is scanned', () => {
    const { state, commands } = run([
      { type: 'CAMERA_READY' },
      { type: 'TRACKING_ID_SUBMITTED', trackingId: ' TRK-001 ' },
    ]);
    expect(state.phase).toBe('recording');
    expect(state.activeTrackingId).toBe('TRK-001');
    expect(commands).toEqual([{ type: 'START', trackingId: 'TRK-001' }]);
  });

  it('scan-to-scan: the next ID switches without a Stop click', () => {
    const { state, commands } = run([
      { type: 'CAMERA_READY' },
      { type: 'TRACKING_ID_SUBMITTED', trackingId: 'TRK-001' },
      { type: 'TRACKING_ID_SUBMITTED', trackingId: 'TRK-002' },
    ]);
    expect(state.activeTrackingId).toBe('TRK-002');
    expect(commands[1]).toEqual({ type: 'SWITCH', trackingId: 'TRK-002' });
    expect(state.notice?.text).toContain('Saved TRK-001');
  });

  it('ignores an accidental double scan of the same ID', () => {
    const { state, commands } = run([
      { type: 'CAMERA_READY' },
      { type: 'TRACKING_ID_SUBMITTED', trackingId: 'TRK-001' },
      { type: 'TRACKING_ID_SUBMITTED', trackingId: 'TRK-001' },
    ]);
    expect(commands).toHaveLength(1);
    expect(state.phase).toBe('recording');
    expect(state.notice?.kind).toBe('info');
  });

  it('rejects malformed IDs without touching the recording', () => {
    const { state, commands } = run([
      { type: 'CAMERA_READY' },
      { type: 'TRACKING_ID_SUBMITTED', trackingId: 'bad id!' },
    ]);
    expect(commands).toHaveLength(0);
    expect(state.phase).toBe('ready');
    expect(state.notice?.kind).toBe('error');
  });

  it('refuses to record without a camera', () => {
    const { state, commands } = run([{ type: 'TRACKING_ID_SUBMITTED', trackingId: 'TRK-001' }]);
    expect(commands).toHaveLength(0);
    expect(state.notice?.text).toMatch(/camera/i);
  });

  it('auto-stops at the maximum duration and asks for the next ID', () => {
    const { state, commands } = run([
      { type: 'CAMERA_READY' },
      { type: 'TRACKING_ID_SUBMITTED', trackingId: 'TRK-001' },
      { type: 'MAX_DURATION_REACHED', maxDurationSec: 120 },
    ]);
    expect(state.phase).toBe('ready');
    expect(commands.at(-1)).toEqual({ type: 'STOP' });
    expect(state.notice?.text).toContain('2:00');
  });

  it('manual stop saves the clip', () => {
    const { state, commands } = run([
      { type: 'CAMERA_READY' },
      { type: 'TRACKING_ID_SUBMITTED', trackingId: 'TRK-001' },
      { type: 'STOP_REQUESTED' },
    ]);
    expect(state.phase).toBe('ready');
    expect(commands.at(-1)).toEqual({ type: 'STOP' });
  });

  it('stops and keeps the partial clip when the camera is unplugged', () => {
    const { state, commands } = run([
      { type: 'CAMERA_READY' },
      { type: 'TRACKING_ID_SUBMITTED', trackingId: 'TRK-001' },
      { type: 'CAMERA_LOST' },
    ]);
    expect(state.phase).toBe('idle');
    expect(commands.at(-1)).toEqual({ type: 'STOP' });
    expect(state.notice?.kind).toBe('error');
  });

  it('formats durations as m:ss', () => {
    expect(formatDuration(0)).toBe('0:00');
    expect(formatDuration(105_400)).toBe('1:45');
    expect(formatDuration(120_000)).toBe('2:00');
  });
});
