# 01 · Business requirements

## Problem
Operators at a packing or dispatch station record a **1–2 minute** video for each parcel. The video is identified by a **Tracking ID** and kept as branded (watermarked) evidence for disputes.

Three constraints shape the design:
- Operators scan IDs one after another, so recording must need **no Start/Stop clicks**.
- Recordings must **never be lost** when the network drops.
- Recordings must reach the cloud quickly.

## Actors
- **Operator (USER):** signs in, picks a camera, scans Tracking IDs, sees and retries their own videos.
- **Admin (ADMIN):** sees all videos and can reprocess any failed one.
- **System:** GCS bucket, Kafka broker, watermark worker.

## Functional requirements and traceability
| ID | Requirement (brief) | Acceptance criteria | Code | Tests |
| --- | --- | --- | --- | --- |
| FR-1 | Username/password login | BCrypt; generic error; lock for 15 min after 5 failures | `auth/application/strategy/PasswordAuthenticationStrategy` | `PasswordAuthenticationStrategyTest` |
| FR-2 | Google SSO | ID token verified on the server (signature, iss, aud, exp, email_verified); account linked by email | `GoogleAuthenticationStrategy`, `GoogleIdTokenVerifierAdapter`, `GoogleSignInButton.tsx` | manual |
| FR-3 | Camera selection | Lists cameras, remembers the choice, reacts to hot-plug | `useCameraDevices.ts` | manual |
| FR-4 | Error if no camera | Specific messages: none / denied / busy / insecure / unplugged | `cameraErrors.ts`, `useMediaStream.ts` | `cameraErrors.test.ts` |
| FR-5 | Tracking ID input | Autofocus; Enter or scanner submits; `^[A-Za-z0-9_-]{3,64}$`; a double scan is ignored | `TrackingIdInput.tsx`, `TrackingId.java` | `TrackingIdInput.test.tsx`, `VideoTest` |
| FR-6 | Automated start/stop (business requirement) | Scan starts recording; next scan saves and starts the next clip; manual Stop; auto-stop at 2:00 with a warning at 1:45; works offline | `recorderMachine.ts`, `recordingController.ts` | `recorderMachine.test.ts`, `recordingController.test.ts` |
| FR-7 | Live view | Mirrored preview, REC, elapsed/remaining time, Tracking ID | `LivePreview.tsx` | manual |
| FR-8 | Picture-in-picture | Toggle, hidden if unsupported | `LivePreview.tsx` | manual |
| FR-9 | Upload via pre-signed URL | Browser → GCS directly; URL bound to object, method, content type, expiry, size | `GcsStorageAdapter`, `uploadTransport.ts` | `GcsStorageAdapterTest` |
| FR-10 | List uploaded videos | Paged, filters, status badges, playback only when READY | `VideoController`, `VideosPage.tsx` | `VideoServiceTest`, `VideoControllerSecurityTest` |
| FR-11 | Retry, no loss offline | 1 s chunks in IndexedDB; persistent queue with backoff + jitter; resumes on reconnect, reload, crash | `uploadQueue.ts`, `connectivityMonitor.ts` | `uploadQueue.test.ts`, `connectivityMonitor.test.ts` |
| FR-12 | Watermark bottom-right + replace | ffmpeg overlay; final MP4 written create-only; raw deleted | `WatermarkJob`, `FfmpegCommandBuilder` | `WatermarkJobTest`, `WatermarkJobFfmpegIntegrationTest` |
| FR-13 | Queue + retry with Kafka | Retry topics 10 s/60 s/300 s, then DLT, then FAILED; permanent errors skip retries; owner retry / admin reprocess | `VideoUploadedListener`, `VideoService.retry` | `VideoUploadedListenerTest`, `VideoServiceTest` |
| FR-14 | Per-user queue, real connectivity | Jobs upload only for their owner; probe-based online detection; logout warning | `uploadQueue.ts`, `App.tsx` | `uploadQueue.test.ts` |

| Deliverable | Where |
| --- | --- |
| D-1 Frontend + both services deployed on a cloud server | GCE VM, `infra/` |
| D-2 Application URL | `REVIEWER-GUIDE.md` |
| D-3 Source repository with setup instructions | `README.md`, `docs/05-Deployment-Runbook.md` |
| D-4 Test login credentials | `REVIEWER-GUIDE.md` (from Secret Manager) |

## Non-functional requirements
- **Security:**
  - OWASP Top 10 (see `06-Security.md`), HTTPS.
  - Least-privilege IAM; no key files.
  - Ownership checks everywhere.
- **Reliability:**
  - A crash loses at most ~1 s of video.
  - At-least-once messaging with idempotent consumers; outbox (no dual writes).
- **Performance** (measured on a laptop, 2 threads):
  - A 60 s 720p clip watermarks in 6.2 s; a 120 s clip in 12.1 s.
  - A 2-minute clip at 2.5 Mbps is about 40 MB.
- **Maintainability:** package-by-feature, hexagonal ports, unit tests on domain and application logic.

## Sizing (1–2 minute clips)
| Item | Value |
| --- | --- |
| Capture | 1280×720 @ 30 fps, 2.5 Mbps video + 128 kbps audio |
| File | ~20 MB/min, ~40 MB for 2 min; hard limit 100 MB (in the signature and re-checked) |
| Duration | Client auto-stops at 120 s; server/ffprobe accept up to 130 s |
| Offline buffer | ~1.6 GB per hour of continuous recording; warning at 80% of the browser quota |
| Throughput | ~12 s per 2-minute clip per worker, so 1 worker covers several stations |
