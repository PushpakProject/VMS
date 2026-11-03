import { RecordingController } from './recordingController';

class FakeRecorder {
  state: 'inactive' | 'recording' = 'inactive';
  ondataavailable: ((event: BlobEvent) => void) | null = null;
  onstop: (() => void) | null = null;

  start() {
    this.state = 'recording';
  }

  emit(bytes: number) {
    this.ondataavailable?.({ data: new Blob([new Uint8Array(bytes)]) } as BlobEvent);
  }

  stop() {
    this.state = 'inactive';
    this.emit(10);
    this.onstop?.();
  }
}

function setup() {
  let now = 1_000_000;
  const recorders: FakeRecorder[] = [];
  const queue = {
    createJob: vi.fn().mockResolvedValue(undefined),
    appendChunk: vi.fn().mockResolvedValue(undefined),
    finishRecording: vi.fn().mockResolvedValue(undefined),
  };
  let id = 0;
  const controller = new RecordingController({
    queue: queue as never,
    mimeType: 'video/webm;codecs=vp9,opus',
    maxDurationSec: 120,
    warnAtSec: 105,
    now: () => now,
    newId: () => `job-${++id}`,
    createRecorder: () => {
      const recorder = new FakeRecorder();
      recorders.push(recorder);
      return recorder as unknown as MediaRecorder;
    },
  });
  controller.setStream({} as MediaStream);
  return { controller, queue, recorders, advance: (ms: number) => (now += ms) };
}

describe('RecordingController', () => {
  afterEach(() => vi.useRealTimers());

  it('scan-to-scan stops the first recorder and starts a second one', async () => {
    const { controller, queue, recorders } = setup();

    controller.submitTrackingId('TRK-001');
    await controller.settled();
    controller.submitTrackingId('TRK-002');
    await controller.settled();

    expect(recorders).toHaveLength(2);
    expect(recorders[0]?.state).toBe('inactive');
    expect(recorders[1]?.state).toBe('recording');
    expect(queue.createJob).toHaveBeenCalledTimes(2);
    expect(queue.finishRecording).toHaveBeenCalledWith('job-1', expect.any(Number));
    expect(queue.appendChunk).toHaveBeenCalledWith('job-1', expect.any(Blob));
  });

  it('auto-stops at the maximum duration and warns near the end', async () => {
    vi.useFakeTimers();
    const { controller, queue, advance } = setup();
    controller.submitTrackingId('TRK-001');
    await controller.settled();

    advance(106_000);
    vi.advanceTimersByTime(300);
    expect(controller.getSnapshot().warning).toBe(true);

    advance(15_000);
    vi.advanceTimersByTime(300);
    await controller.settled();
    expect(controller.getSnapshot().machine.phase).toBe('ready');
    expect(queue.finishRecording).toHaveBeenCalledTimes(1);
  });

  it('reports a failure to start without leaving a stuck recording state', async () => {
    const { controller, queue } = setup();
    queue.createJob.mockRejectedValueOnce(new Error('IndexedDB full'));
    vi.spyOn(console, 'error').mockImplementation(() => undefined);

    controller.submitTrackingId('TRK-001');
    await controller.settled();

    const { machine } = controller.getSnapshot();
    expect(machine.phase).toBe('ready');
    expect(machine.notice?.kind).toBe('error');
  });
});
