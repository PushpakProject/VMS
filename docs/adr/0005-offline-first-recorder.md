# ADR 0005: Offline-first recorder and scan-to-scan automation

**Status:** accepted

## Context
- **Business requirement:** no repeated Start/Stop clicks after entering a Tracking ID.
- **Brief:** recordings must not be lost when offline.
- **Usage:** operators scan barcodes in quick succession; clips are 1–2 minutes.

## Decision
**Scan-to-scan recording**
- A pure state machine drives it:
  - a valid ID starts recording
  - the next ID saves the current clip and starts the next one on the same stream
  - manual Stop, plus an auto-stop at 2:00 with a warning at 1:45
- Commands run one after another.

**Saving and uploading**
- `MediaRecorder` hands over a chunk every second, and each chunk is written to IndexedDB.
- A persistent `UploadQueue`:
  - one uploader per browser (Web Locks), oldest clip first
  - backoff with jitter (1 s → 5 min)
  - re-signs expired URLs
  - only processes the signed-in user's jobs
  - recovers recordings interrupted by a crash
- **Connectivity** is decided from real request outcomes plus a `/api/v1/ping` probe, not `navigator.onLine` alone.
- **Local copies** are deleted only after the server reports READY.

## Consequences
- A crash or refresh loses at most about 1 s.
- Offline recording keeps working.
- A shared PC never uploads another user's clips.
- Browser storage quota applies: about 1.6 GB per offline hour, with a warning at 80%.
- Data on the device isn't encrypted (documented limitation).
