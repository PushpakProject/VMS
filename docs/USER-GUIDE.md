# User guide

How operators and admins use the Parcel Video Recorder. Setup is covered in [00-Run-Guide.md](00-Run-Guide.md).

| | Local | Google Cloud |
| --- | --- | --- |
| URL | http://localhost:8088 | `https://<ip-with-dashes>.sslip.io` |
| Browser | Latest Chrome or Edge (Safari works and records MP4) | same |
| Accounts | `operator@vms.local`, `admin@vms.local` | `operator@vms.demo`, `admin@vms.demo` |

Passwords are never in the repository: see [REVIEWER-GUIDE.md → Login credentials](../REVIEWER-GUIDE.md#login-credentials).

---

## 1. Sign in
1. Open the URL and enter your **email or username** (`operator` / `admin`) and password.
2. After **5 wrong passwords** the account is locked for 15 minutes. The message is the same for every failure, on purpose.
3. If the page says you are offline, sign-in needs a network connection. Recordings already on the device are safe and continue after you sign in.
4. Your session renews itself in the background, and you stay signed in for up to 7 days unless you sign out.

## 2. Choose a camera (Recorder page)
1. Allow camera access when the browser asks.
2. Pick a camera in the **Camera** dropdown. The choice is remembered, and newly plugged-in cameras appear automatically.
3. If there's a problem, a red message explains it and a **Retry** button appears:

| Message | What to do |
| --- | --- |
| No camera connected | Plug in a webcam; it's detected automatically |
| Camera access was blocked | Click the camera icon in the address bar → Allow → **Retry** |
| Your operating system is blocking the camera | **macOS:** System Settings → Privacy & Security → **Camera** → switch on your browser (Chrome/Edge/Safari), then **quit and reopen** the browser |
| Camera is in use by another application | Close Teams, Zoom or other tabs using the camera → **Retry** |
| The camera needs a secure (HTTPS) connection | Open the app over `https://` or on `localhost` |
| The camera was disconnected | Reconnect it or pick another one; the clip recorded so far is saved |

## 3. Record: scan, and the app does the rest
The **Tracking ID** box always has the cursor, so a barcode scanner works directly. Scanners "type" the code and press Enter.

| You do | What happens |
| --- | --- |
| Scan or type `TRK-1001`, then Enter | Recording **starts automatically**: red **REC** badge, Tracking ID and timer on the preview |
| Scan the **next** ID, e.g. `TRK-1002` | Clip 1 is **saved** and clip 2 starts at once. *No Start/Stop clicks needed.* |
| Press **Stop** | The current clip is saved; the app waits for the next scan |
| Recording reaches **2:00** | Stops automatically; a warning appears at **1:45** |
| Scan the same ID twice | The second scan is ignored ("was just scanned") |
| Enter an invalid ID | Inline error: 3–64 letters, digits, `-` or `_` |

**Picture-in-picture:** the button at the bottom-right of the preview opens a floating camera window that stays on top while you use other apps.

## 4. Uploads: the "On this device" panel
Each clip appears here and uploads in the background. You don't have to wait for it.

| Status | Meaning |
| --- | --- |
| Recording | Clip in progress; saved to the device every second |
| Queued | Waiting to upload |
| Uploading (progress bar) | Sent to cloud storage in 4 MB pieces |
| Waiting for network | Offline; uploads continue by themselves when the connection is back |
| Finishing upload / Uploaded | Server is verifying the file / file received |
| Failed | Permanent problem (e.g. file too large); shows **Retry** and **Discard** |

- **Offline:** recording keeps working. The header shows *Offline — recording continues*.
- **Refresh, crash or closed tab:** nothing is lost. Reopen the app and clips resume from the last piece the cloud confirmed.
- **Preview** plays the local copy, before it's watermarked.
- The local copy is removed automatically once the server has the watermarked video.
- If device storage passes 80% full, a warning asks you to reconnect so queued clips can upload.
- Recordings belong to whoever recorded them. On a shared PC, another user's clips are never uploaded under your account; you only see a count of them.

## 5. Videos page
| Feature | How |
| --- | --- |
| Status | *Uploaded → Adding watermark → Ready* (usually seconds). The list refreshes itself while anything is pending. |
| **Play** | Only for *Ready* videos. The company logo is in the bottom-right corner. Playback links expire after 5 minutes; press Play again if needed. |
| **Details** | Recorded time, duration, size, failure reason, and every watermark attempt with its status and timing |
| **Retry** | Shown for failures caused by a temporary problem |
| **Delete** | Removes the video (asks for confirmation) |
| Search / filter | Tracking ID search, status filter, paging |

## 6. Admin
Signed in as **admin** you can:
- see **all** users' videos (operators only ever see their own);
- **Reprocess** any failed video whose original upload still exists.

## 7. Sign out
Use **Sign out** in the header. If clips are still uploading, you'll be asked to confirm. They stay on the device and continue the next time **you** sign in.

## 8. Quick 2-minute check
1. Sign in as the operator.
2. Scan `TRK-1001`, wait about 10 s, scan `TRK-1002`, then press **Stop**.
3. Open **Videos**, wait for both to be *Ready*, then **Play** one and check the logo.
4. Offline test: DevTools → Network → **Offline**, record `TRK-1003`, then switch back to **No throttling**. The clip uploads by itself.

## 9. Common problems
| Problem | Fix |
| --- | --- |
| Page doesn't load (local) | `docker compose -f infra/docker-compose.yml --env-file infra/.env ps`; all services should be `healthy` |
| "This browser cannot record video" | Use the latest Chrome or Edge |
| Video stays *Uploaded* | The watermark service is busy or restarting; it continues automatically (admins: check **Details**) |
| *Failed: too large / too long / unreadable* | Record again; these files can't be processed |
| Signed out unexpectedly | The session expired or was revoked; sign in again. Queued clips are kept. |
