# Reviewer guide

## Access
| Item | Value |
| --- | --- |
| Application URL | `https://<vm-ip-with-dashes>.sslip.io` (provided with the submission) |
| Browser | Latest Chrome or Edge (Safari works, records MP4) |

The app runs over HTTPS, so the camera works without the `chrome://flags` workaround.

## Login credentials
Two test accounts are created automatically on start-up. Their passwords are generated per environment and are **never stored in the repository**. The passwords for the live URL are provided privately with the submission.

| Environment | Role | Login | Where the password comes from |
| --- | --- | --- | --- |
| Google Cloud (`https://<host>`) | Operator | `operator@vms.demo` | Secret Manager `vms-demo-user-password` |
| Google Cloud | Admin | `admin@vms.demo` | Secret Manager `vms-demo-admin-password` |
| Local (`http://localhost:8088`) | Operator | `operator@vms.local` | `VMS_DEMO_USER_PASSWORD` in `infra/.env` |
| Local | Admin | `admin@vms.local` | `VMS_DEMO_ADMIN_PASSWORD` in `infra/.env` |

The login field accepts the email or the username (`operator` / `admin`).

Read the cloud passwords (Cloud Shell, project selected):
```bash
gcloud secrets versions access latest --secret vms-demo-user-password
gcloud secrets versions access latest --secret vms-demo-admin-password
```
Read the local passwords:
```bash
grep DEMO infra/.env
```

| Rule | Behaviour |
| --- | --- |
| Roles | Operators see only their own videos. The admin sees all videos and can reprocess failed ones. |
| Lockout | 5 wrong passwords lock the account for 15 minutes (same generic error every time). |
| Self-registration | Disabled in the cloud deployment (`VMS_REGISTRATION_ENABLED=false`); enabled locally. |
| Change cloud passwords | `terraform -chdir=infra/terraform apply -replace=random_password.demo_user` (or `demo_admin`), then `./infra/scripts/deploy.sh` |
| Change local passwords | Edit `infra/.env`, then `docker compose -f infra/docker-compose.yml --env-file infra/.env down -v` and `up -d`. Accounts are created only on first start, so **this resets local data**. |

The full feature walkthrough for operators and admins is in [docs/USER-GUIDE.md](docs/USER-GUIDE.md).

## 2-minute test script
1. **Sign in** as the operator. Then open DevTools → Application → Cookies: the refresh cookie is `HttpOnly; Secure; SameSite=Strict`, and no token is in localStorage.
2. **Camera:** allow camera access and pick a camera. Unplugging the camera (or denying access) shows a specific message with a Retry button.
3. **Scan-to-scan:**
   1. Type `TRK-1001` and press Enter. Recording starts by itself (REC badge, timer).
   2. After ~10 s type `TRK-1002` + Enter. Clip 1 is saved and clip 2 starts. No Start or Stop click is needed.
   3. Press **Stop** (or wait for the 2:00 auto-stop; a warning shows at 1:45).
4. **Picture-in-picture:** the button at the bottom-right of the preview.
5. **Offline:**
   1. DevTools → Network → *Offline*, then record `TRK-1003`. The header shows *Offline — recording continues* and the clip waits as *Waiting for network*.
   2. Switch back to *No throttling*. The clip uploads automatically.
   3. Refreshing the page in between loses nothing.
6. **Videos page:** status moves through *Uploaded → Adding watermark → Ready*. Press **Play**: the logo is in the bottom-right corner.
7. **Isolation:** sign in as admin to see all videos; the operator only sees their own.

## Where to look in the code
| Concern | Location |
| --- | --- |
| Scan-to-scan state machine | `frontend/src/features/recorder/recorderMachine.ts` |
| Recording → IndexedDB → upload queue | `frontend/src/features/recorder/recordingController.ts`, `frontend/src/features/uploads/uploadQueue.ts` |
| Connectivity detection | `frontend/src/features/connectivity/connectivityMonitor.ts` |
| Size-capped V4 signed URLs | `api-service/.../storage/gcs/GcsStorageAdapter.java` |
| Upload verification + outbox write | `api-service/.../video/application/VideoService.java` (`completeUpload`) |
| Outbox relay (SKIP LOCKED) | `api-service/.../outbox/OutboxRelay.java`, `OutboxRepository.java` |
| Idempotent result consumer | `api-service/.../video/application/WatermarkResultHandler.java` |
| Video state machine | `api-service/.../video/domain/VideoStatus.java`, `Video.java` |
| Auth strategies, refresh rotation | `api-service/.../auth/application/` |
| Retry topics + DLT | `watermark-service/.../messaging/VideoUploadedListener.java` |
| Watermark pipeline (Template Method) | `watermark-service/.../application/AbstractVideoJob.java`, `WatermarkJob.java` |
| ffmpeg command (argument list, no shell) | `watermark-service/.../processing/FfmpegCommandBuilder.java` |
| Deployment | `infra/scripts/`, `infra/docker-compose.prod.yml`, `infra/nginx/prod.conf.template` |

## Scenario test matrix
| # | Scenario | Expected result | Covered by |
| --- | --- | --- | --- |
| 1 | Wrong password ×5 | Generic error, then a 15-minute lockout (same message) | `PasswordAuthenticationStrategyTest` |
| 2 | No camera / permission denied / camera busy | Specific message + how to fix | `cameraErrors.test.ts`, manual step 2 |
| 3 | Invalid Tracking ID / same ID scanned twice | Inline error / ignored | `recorderMachine.test.ts`, `TrackingIdInput.test.tsx` |
| 4 | Scan A then B | A saved and uploaded, B recording, no click | `recorderMachine.test.ts`, `recordingController.test.ts` |
| 5 | Recording reaches 2:00 | Auto-stop, "scan the next Tracking ID" | `recordingController.test.ts` |
| 6 | Network drops during upload | Waits, retries with backoff, READY | `uploadQueue.test.ts` |
| 7 | Wi-Fi up but no internet | Probe keeps the app OFFLINE until `/ping` succeeds | `connectivityMonitor.test.ts` |
| 8 | Tab crash mid-recording | Recovered from IndexedDB and uploaded | `uploadQueue.test.ts` |
| 9 | Signed URL expired | New URL requested automatically | `uploadQueue.test.ts` |
| 10 | Upload over 100 MB | Rejected by the signature, or the API marks it `TOO_LARGE` | `GcsStorageAdapterTest`, `VideoServiceTest` |
| 11 | `/complete` without an object in storage | 409 `UPLOAD_NOT_FOUND`, status unchanged | `VideoServiceTest` |
| 12 | Corrupt video | No retries; DLT; FAILED `CORRUPT_INPUT` | `WatermarkJobTest`, `VideoUploadedListenerTest` |
| 13 | Transient GCS / ffmpeg error | RETRYING, then READY or FAILED after 4 attempts | `VideoUploadedListenerTest` |
| 14 | Duplicate `video.uploaded` event | Output already exists, so it's skipped; single READY | `WatermarkJobTest`, `WatermarkResultHandlerTest` |
| 15 | Two workers race on one video | Create-only write; loser treats it as done | `WatermarkJobTest` |
| 16 | User A requests B's video | 404 (no existence leak) | `VideoServiceTest`, `VideoControllerSecurityTest` |
| 17 | Playback before READY | 409 `NOT_READY` | `VideoServiceTest`, `VideoControllerSecurityTest` |
| 18 | Owner retries a retryable failure | UPLOADED → READY; non-retryable failures need an admin | `VideoServiceTest` |
| 19 | Second user on the same PC | First user's recordings are not uploaded | `uploadQueue.test.ts` |
| 20 | Final video | Logo bottom-right, raw deleted, MP4 with duration ±1.5 s | `WatermarkJobFfmpegIntegrationTest` |
