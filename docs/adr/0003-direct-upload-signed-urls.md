# ADR 0003: Direct browser-to-GCS upload with size-capped V4 signed URLs

**Status:** accepted

## Context
Clips are about 40 MB. Routing them through the API costs memory, bandwidth and threads for no benefit. The bucket must stay private, and the browser must not hold GCP credentials.

## Decision
- The API issues a V4 signed `PUT` URL:
  - valid for 15 minutes
  - bound to one server-generated key
  - signed headers: `content-type` and `x-goog-content-length-range: 0,104857600`
- Signing uses the VM service account through IAM signBlob, so no key file exists.
- `/complete` stats the real object before accepting it.
- Playback uses a 5-minute signed GET, issued only for READY (watermarked) videos.
- **Default protocol is resumable:**
  - The API opens a GCS resumable session server-side (signed `POST` with `x-goog-resumable: start` and `X-Upload-Content-Length`), so GCS enforces the exact declared size. The browser only receives the session URI.
  - The browser sends 4 MiB pieces with `Content-Range` and resumes from the `Range` GCS confirms.
  - Uploads sit behind an `UploadTransport` port, and `VMS_UPLOAD_PROTOCOL=SIMPLE` falls back to one size-capped PUT.

## Consequences
- The API stays small and stateless. GCS handles concurrency and durability.
- A leaked URL is nearly useless: one object, one method, short-lived, size-capped.
- A network drop re-sends at most one 4 MiB piece. Session URIs are write capabilities for one object; they're never logged or stored on the server.
- The signed headers must be sent exactly as signed, and the bucket CORS must allow them. Both are verified by tests and the smoke test.
