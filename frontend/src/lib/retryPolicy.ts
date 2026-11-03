/** Strategy for retry delays. Exponential backoff with jitter avoids synchronized retry storms. */
export interface RetryPolicy {
  delayMs(attempt: number): number;
}

export class ExponentialBackoff implements RetryPolicy {
  constructor(
    private readonly baseMs = 1_000,
    private readonly maxMs = 5 * 60_000,
    private readonly jitterRatio = 0.2,
    private readonly random: () => number = Math.random,
  ) {}

  /** attempt is 1-based: 1s, 2s, 4s, ... capped, each +/- 20 %. */
  delayMs(attempt: number): number {
    const exponential = Math.min(this.maxMs, this.baseMs * 2 ** Math.max(0, attempt - 1));
    const jitter = exponential * this.jitterRatio * (this.random() * 2 - 1);
    return Math.max(0, Math.round(exponential + jitter));
  }
}
