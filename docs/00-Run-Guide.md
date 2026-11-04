# 00 · Run guide: local and Google Cloud, step by step

This guide brings the whole platform up:
- **Part A:** on your laptop, with no cloud account, in about 10 minutes.
- **Part B:** on Google Cloud with HTTPS and a public URL, in about 30 minutes, most of it waiting for builds.

Every step ends with a **Check:** line describing what you should see.

---

## Part A: Local (Docker on your laptop)

### A1. Prerequisites
| Tool | Version | Check |
| --- | --- | --- |
| Docker Desktop (running) | 4.x / Engine 24+ | `docker info` prints server details |
| Docker Compose v2 | bundled | `docker compose version` |
| Chrome or Edge | latest | needed for camera + MediaRecorder |
| *(tests only)* Java 17, Maven 3.9, Node 24, ffmpeg | | `java -version`, `mvn -v`, `node -v`, `ffmpeg -version` |

Free ports: **8088** (web). Nothing else is exposed to the host.

### A2. Get the code
```bash
git clone <repo-url> vms-platform && cd vms-platform
```

### A3. Create the local configuration (`infra/.env`, gitignored)
Generate real random values; never reuse the placeholders:
```bash
umask 077
cat > infra/.env <<EOF
VMS_DB_PASSWORD=$(openssl rand -hex 16)
VMS_JWT_SECRET=$(openssl rand -base64 48)
VMS_LOCAL_SIGNING_KEY=$(openssl rand -hex 32)
VMS_GOOGLE_CLIENT_ID=
VMS_DEMO_USER_EMAIL=operator@vms.local
VMS_DEMO_USER_PASSWORD=$(openssl rand -hex 8)-Op
VMS_DEMO_ADMIN_EMAIL=admin@vms.local
VMS_DEMO_ADMIN_PASSWORD=$(openssl rand -hex 8)-Ad
EOF
```
**Check:** `grep DEMO infra/.env` shows the two test accounts. These are your local login credentials.

### A4. Build and start the stack
```bash
docker compose -f infra/docker-compose.yml --env-file infra/.env up -d --build
```
- The first build takes 5–10 minutes (Maven and npm downloads); later runs take seconds.
- It starts five containers: `postgres`, `kafka` (KRaft), `api`, `watermark` and `web` (nginx).

### A5. Wait until everything is healthy
```bash
docker compose -f infra/docker-compose.yml --env-file infra/.env ps
```
**Check:** `api`, `watermark`, `postgres` and `kafka` show `(healthy)` (about 1 minute after start).

The API log should contain `Started VmsApiApplication` and `Created demo account`:
```bash
docker compose -f infra/docker-compose.yml --env-file infra/.env logs api | grep -E "Started|demo"
```

### A6. Automated end-to-end check (optional, needs ffmpeg + python3 on the host)
```bash
set -a; . infra/.env; set +a
LOGIN="$VMS_DEMO_USER_EMAIL" PASSWORD="$VMS_DEMO_USER_PASSWORD" ./infra/scripts/smoke-test.sh
```
**Check:** the script ends with `SMOKE TEST PASSED`. Along the way it signs in, uploads a clip with a signed URL, waits for Kafka and ffmpeg to reach `READY`, downloads the watermarked MP4, and runs the negative checks (401, 404, 403).

### A7. Use the app
1. Open **http://localhost:8088** (localhost counts as a secure origin, so the camera works without HTTPS).
2. Sign in with a test account. The passwords are in `infra/.env` (`grep DEMO infra/.env`) and never in the repo:

   | Role | Login | Password |
   | --- | --- | --- |
   | Operator | `operator@vms.local` | `VMS_DEMO_USER_PASSWORD` |
   | Admin (all videos, reprocess) | `admin@vms.local` | `VMS_DEMO_ADMIN_PASSWORD` |

   Full credential rules (lockout, roles, rotation) are in `REVIEWER-GUIDE.md` → *Login credentials*.
3. Allow camera access and pick a camera.
4. Type `TRK-1001` and press Enter: recording starts. Type `TRK-1002` and press Enter: clip 1 is saved and clip 2 starts.
5. Press **Stop**, or wait for the 2:00 auto-stop.
6. Open **Videos**: the status goes from *Uploaded* to *Adding watermark* to *Ready*. Press **Play**: the logo is in the bottom-right corner.

### A8. Stop, restart, reset
| Goal | Command |
| --- | --- |
| Stop (keep data) | `docker compose -f infra/docker-compose.yml --env-file infra/.env stop` |
| Start again | `docker compose -f infra/docker-compose.yml --env-file infra/.env up -d` |
| Rebuild after code changes | `... up -d --build` |
| Follow logs | `... logs -f api watermark` |
| **Reset all local data** (deletes videos, users, Kafka topics) | `... down -v` |

### A9. Run the test suites (no stack needed)
```bash
(cd api-service && mvn verify)          # unit tests, plus Testcontainers integration tests (needs Docker), plus format check
(cd watermark-service && mvn verify)    # includes real ffmpeg on 60 s / 120 s clips
(cd frontend && npm ci && npm run lint && npm test && npm run build)
```

### A10. Local troubleshooting
| Symptom | Cause / fix |
| --- | --- |
| `Cannot connect to the Docker daemon` | Start Docker Desktop and wait until `docker info` works |
| `port is already allocated` (8088) | Another process uses it: change `"8088:80"` in `infra/docker-compose.yml` **and** `VMS_CORS_ALLOWED_ORIGINS` |
| `api` keeps restarting | `docker compose … logs api`. Usually a missing value in `infra/.env` (the compose file names it) or a database created by an older schema: run `down -v` |
| "Camera access was blocked" | Click the camera icon in the address bar, allow access, press **Retry** |
| "Camera in use by another application" | Close Teams/Zoom/other tabs using the camera |
| Video stuck at *Uploaded* | `docker compose … logs watermark`. The worker must show `partitions assigned` |
| Sign-in says "offline" | The API isn't reachable yet; wait for `(healthy)` |

---

## Part B: Google Cloud (public HTTPS URL)

**How it works:** Terraform (`infra/terraform/`) creates the cloud infrastructure once, and `infra/scripts/deploy.sh` builds and ships the application on every release.

**What gets created:**
- One e2-standard-2 VM (Ubuntu 24.04, Shielded VM) running the same containers as locally.
- A private GCS bucket.
- An Artifact Registry repo for the images.
- Secret Manager secrets.
- A static IP, and a TLS certificate from Let's Encrypt on `<ip-with-dashes>.sslip.io`.

**Cost:** about $50/month, covered by the $300 free-trial credit. Teardown is in B10.

### B1. Create the project (one time, in the browser)
1. Go to https://console.cloud.google.com → project picker → **New project** and note the **Project ID** (not the name).
2. **Billing:** Billing → link a billing account to the project. A free-trial account works.
3. Open **Cloud Shell** (`>_` icon, top right). It already has `gcloud`, `terraform`, `docker`, `git`, `openssl` and `python3`, and you're already authenticated.
4. In Cloud Shell, select the project:
   ```bash
   gcloud config set project <project-id>
   ```

**Check:**
- `gcloud config list` shows your account and project.
- `terraform version` prints 1.5 or newer.

### B2. Get the code into Cloud Shell
`deploy.sh` tags images with the git commit, so Cloud Shell needs a git checkout with everything committed. Use **option A** or **option B**:

**A. From a git remote** (recommended): push the repo from your laptop to a private GitHub or GitLab repo, then:
```bash
git clone <repo-url> vms-platform && cd vms-platform
```

**B. Without a remote:**
1. On your laptop, zip `vms-platform/` without `node_modules`, `target` or `infra/.env`.
2. In Cloud Shell: ⋮ menu → **Upload**, then:
   ```bash
   unzip vms-platform.zip && cd vms-platform
   git init -q && git add -A
   git -c user.name="<you>" -c user.email="<you@example.com>" commit -qm "Deploy snapshot"
   ```

**Check:** `git status` shows `nothing to commit, working tree clean`.

### B3. Configure Terraform
```bash
cp infra/terraform/terraform.tfvars.example infra/terraform/terraform.tfvars
nano infra/terraform/terraform.tfvars        # or: cloudshell edit infra/terraform/terraform.tfvars
```
| Variable | Required | Default | Meaning |
| --- | --- | --- | --- |
| `project_id` | **yes** | – | Project ID from B1 |
| `letsencrypt_email` | **yes** | – | Receives TLS certificate notices |
| `region` / `zone` | no | `us-central1` / `us-central1-a` | Where everything runs |
| `machine_type` | no | `e2-standard-2` | VM size (8 GB) |
| `google_client_id` | no | empty | Google Sign-In client ID (B9) |
| `raw_retention_days` | no | `14` | Backstop deletion of unprocessed raw uploads |

`terraform.tfvars`, the Terraform state and `infra/.deploy.env` are all gitignored.

### B4. Create the infrastructure
```bash
terraform -chdir=infra/terraform init
terraform -chdir=infra/terraform plan      # review: about 40 resources to add, 0 to change or destroy
terraform -chdir=infra/terraform apply     # type "yes"
```
Terraform creates:
- **APIs:** enables Compute, Storage, IAM, IAM Credentials, Secret Manager, Artifact Registry, Logging, Cloud Build and IAP.
- **Service accounts** (least privilege, no key files):
  - `vms-runtime` (on the VM): object admin on **this bucket only**, can sign URLs as itself, can read **only the four VMS secrets**, writes logs, pulls images.
  - `vms-builder` (Cloud Build): pushes images, writes build logs.
- **Network:** a static IP; firewall open on **80/443** to everyone and **22 only from Google IAP**.
- **Bucket** `<project-id>-vms-videos`: uniform access, public access blocked, CORS limited to `https://<host>`, lifecycle rule for `raw/`.
- **Secrets:** DB password, JWT secret and two test-account passwords, generated randomly.
- **VM:** a startup script installs Docker, registry credentials and 2 GB of swap.
- **Settings file:** `infra/.deploy.env`, the non-secret settings `deploy.sh` reads.

**Check:** the outputs show `app_url = "https://<ip-with-dashes>.sslip.io"`. You can print them again any time:
```bash
terraform -chdir=infra/terraform output
```

> On a brand-new project the first `apply` can fail with *"API has not been used in project … or it is disabled"*, because enabling an API takes a minute to propagate. Wait a minute and run `apply` again; Terraform continues where it stopped.

> **State contains secrets.** The generated passwords are also in `infra/terraform/terraform.tfstate`. Keep Cloud Shell's home directory private. For a shared setup, create a private bucket, uncomment the `gcs` backend in `versions.tf`, and run `terraform init -migrate-state -backend-config="bucket=<state-bucket>"`.

### B5. Wait for the VM to be ready (about 2 minutes)
```bash
gcloud compute ssh vms-vm --zone us-central1-a --tunnel-through-iap --command "docker --version"
```
The first SSH generates a key; press Enter at the prompts.

**Check:** it prints `Docker version …`. If it says `command not found`, the startup script is still running; wait and retry.

### B6. Build and deploy
```bash
./infra/scripts/deploy.sh
```
1. Refuses to run with uncommitted changes, because images are tagged with the git commit.
2. **Cloud Build**, running as `vms-builder`, builds `api-service`, `watermark-service` and `web` (linux/amd64) and pushes them to Artifact Registry. This takes about 8–12 minutes the first time.
3. Copies the compose file, nginx config and scripts to the VM over the IAP tunnel.
4. On the VM, `remote-deploy.sh`:
   1. Writes `/opt/vms/.env` from Secret Manager (root-only file).
   2. On the first run, gets the Let's Encrypt certificate.
   3. Starts the stack.
   4. Waits up to 3 minutes for the health checks, and **rolls back to the previous version automatically** if they fail.
   5. Installs weekly certificate renewal.

**Check:** the output ends with `Deployed <sha>: https://<host>`.

### B7. Get the test credentials
```bash
echo "operator@vms.demo / $(gcloud secrets versions access latest --secret vms-demo-user-password)"
echo "admin@vms.demo    / $(gcloud secrets versions access latest --secret vms-demo-admin-password)"
```
Share these with the reviewers privately, never in the repo.

### B8. Verify
1. Open the `app_url` in Chrome. You should see a valid padlock, and the camera works without `chrome://flags`.
2. Run the automated check from Cloud Shell:
   ```bash
   source infra/.deploy.env
   BASE_URL="https://$PUBLIC_HOST" LOGIN=operator@vms.demo \
   PASSWORD="$(gcloud secrets versions access latest --secret vms-demo-user-password)" \
   ./infra/scripts/smoke-test.sh
   ```
   **Check:** `SMOKE TEST PASSED` with `uploaded (RESUMABLE)`. This proves the cloud path end to end:
   - resumable session creation on GCS
   - IAM-based URL signing
   - Kafka and ffmpeg on the VM
   - the 5-minute playback URL
3. In the browser, upload one clip: this checks bucket CORS. Then do the walkthrough in `REVIEWER-GUIDE.md`.

### B9. *(Optional)* Google Sign-In
Password login already meets the brief, so you can skip this.
1. APIs & Services → **OAuth consent screen** → External → app name and your email → Save. Add yourself as a test user.
2. APIs & Services → **Credentials** → **Create credentials → OAuth client ID** → *Web application*:
   - **Authorised JavaScript origins:** the `app_url` from B4, and `http://localhost:8088`.
3. Set `google_client_id = "<client-id>"` in `infra/terraform/terraform.tfvars`.
4. Run `terraform -chdir=infra/terraform apply` (this only rewrites `infra/.deploy.env`), then `./infra/scripts/deploy.sh`.

**Check:** the login page shows the Google button.

### B10. Day-2 operations
| Task | Command |
| --- | --- |
| Deploy a new commit | `git pull && ./infra/scripts/deploy.sh` |
| Roll back to the previous version | `./infra/scripts/deploy.sh --rollback` |
| Change infrastructure (e.g. machine type) | Edit `terraform.tfvars`, then `terraform -chdir=infra/terraform plan` and `apply` |
| Show URL, bucket, SSH command | `terraform -chdir=infra/terraform output` |
| SSH to the VM | `gcloud compute ssh vms-vm --zone us-central1-a --tunnel-through-iap` |
| Container status (on the VM) | `sudo docker compose --env-file /opt/vms/.env -f /opt/vms/docker-compose.yml ps` |
| Logs | Console → Logging → Logs Explorer, query `jsonPayload.container.name:"vms-api"` (structured JSON, filter by `correlationId`) |
| Inspect parked (DLT) messages | On the VM: `sudo docker compose --env-file /opt/vms/.env -f /opt/vms/docker-compose.yml exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 --topic video.uploaded.v1-dlt --from-beginning --timeout-ms 5000` |
| Rotate a generated secret | `terraform -chdir=infra/terraform apply -replace=random_password.jwt` (or `.db`, `.demo_user`, `.demo_admin`), then `./infra/scripts/deploy.sh`. Rotating the JWT signs everyone out. **Rotating the DB password needs a database reset**, because Postgres keeps the password it was first created with. |
| Database backup | On the VM: `sudo docker compose … exec -T postgres pg_dump -U vms vms \| gzip > vms-$(date +%F).sql.gz` |

**Pause or tear down (cost control):**
| Goal | Command |
| --- | --- |
| Pause (keeps disk, IP and data; you still pay for disk and IP) | `gcloud compute instances stop vms-vm --zone us-central1-a` / `start` to resume |
| **Remove all VMS resources (irreversible: deletes all videos)** | 1. `source infra/.deploy.env && gcloud storage rm --recursive "gs://$VMS_GCS_BUCKET/**"` (the bucket refuses deletion while it holds videos). 2. `terraform -chdir=infra/terraform destroy` |
| **Delete the whole project (irreversible)** | `gcloud projects delete <project-id>` |

### B11. GCP troubleshooting
| Symptom | Cause / fix |
| --- | --- |
| `terraform apply`: "API has not been used … or it is disabled" | Propagation delay on a new project; wait a minute and re-run `apply` |
| `terraform apply`: `PERMISSION_DENIED` / `Error 403` | Your account needs *Owner* (or Editor + Project IAM Admin + Secret Manager Admin) on the project |
| `terraform apply`: bucket name already exists | Bucket names are global. Someone else owns `<project-id>-vms-videos`; change the `name` in `storage.tf` |
| `deploy.sh`: "infra/.deploy.env not found" | Run B4 first (Terraform writes the file) |
| `deploy.sh`: "Commit your changes first" | `git status`, commit, then retry |
| Cloud Build fails "permission denied … artifactregistry" or "logs bucket" | Re-run `terraform apply` (it grants `vms-builder`), then `deploy.sh` |
| `gcloud compute ssh` hangs or fails | Wait until the VM is RUNNING; check that the `vms-allow-iap-ssh` firewall rule exists; the first run needs SSH key generation |
| `docker: command not found` on the VM | The startup script is still running; wait, or check `sudo journalctl -u google-startup-scripts` |
| certbot fails | `<ip>.sslip.io` must resolve to the VM IP, and port 80 must be open; re-run `deploy.sh` |
| Health checks fail, then rolled back | `remote-deploy.sh` printed the cause. Check `docker compose logs api watermark` on the VM |
| Upload fails in the browser (CORS) | The bucket CORS origin must equal `https://$PUBLIC_HOST`. `terraform apply` keeps it in sync with the static IP |
| Google button missing | `google_client_id` not set; see B9 |
