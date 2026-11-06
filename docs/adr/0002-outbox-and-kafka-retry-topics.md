# ADR 0002: Transactional outbox + Kafka retry topics and DLT

**Status:** accepted

## Context
After an upload is verified, the API must start watermarking exactly once in effect. Publishing to Kafka right after the database commit is a dual write: if Kafka is down, the event is lost and the video stays UPLOADED forever. Processing can fail transiently (GCS 5xx, encoder crash) or permanently (corrupt file).

## Decision
**Outbox**
- `VideoService.completeUpload` writes the status change and an `outbox_events` row in one transaction.
- `OutboxRelay` publishes pending rows every second (`FOR UPDATE SKIP LOCKED`) and marks a row published only after the broker acks it.

**Retries and dead-letter topic**
- The watermark consumer uses `@RetryableTopic`: 4 attempts, backoff 10 s → 60 s → 300 s, then the DLT.
- `NonRetryableProcessingException` (corrupt input, too long, too large) skips retries.
- Every attempt publishes STARTED / RETRYING / COMPLETED / FAILED on `video.watermark.result.v1`.

**Idempotency** (delivery is at-least-once, so every consumer must be idempotent)
- API: a `processed_events` table, the state machine, and run numbers.
- Worker: skip if the watermarked object already exists; the final write is create-only.

## Consequences
- No lost events, and poison messages don't block the partition.
- Per-video ordering via `key = videoId`.
- The cost is an extra table plus a relay. Monitor the outbox backlog and DLT size (future alerts).
