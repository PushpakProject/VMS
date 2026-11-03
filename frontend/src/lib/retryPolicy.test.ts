import { ExponentialBackoff } from './retryPolicy';

describe('ExponentialBackoff', () => {
  it('doubles from 1 s and caps at 5 min without jitter', () => {
    const policy = new ExponentialBackoff(1_000, 300_000, 0.2, () => 0.5);
    expect([1, 2, 3, 4].map((a) => policy.delayMs(a))).toEqual([1_000, 2_000, 4_000, 8_000]);
    expect(policy.delayMs(20)).toBe(300_000);
  });

  it('applies at most +/- 20 % jitter', () => {
    const low = new ExponentialBackoff(1_000, 300_000, 0.2, () => 0);
    const high = new ExponentialBackoff(1_000, 300_000, 0.2, () => 0.999999);
    expect(low.delayMs(3)).toBe(3_200);
    expect(high.delayMs(3)).toBe(4_800);
  });
});
