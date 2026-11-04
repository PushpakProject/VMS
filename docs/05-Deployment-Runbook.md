# 05 · Deployment runbook (Google Cloud)

**Target:**
- One Compute Engine VM (e2-standard-2, Ubuntu 24.04) running Docker Compose: nginx + TLS, the API, the watermark service, Kafka (KRaft) and PostgreSQL.
- Videos in a private GCS bucket.
- Images in Artifact Registry, built by Cloud Build.
- **Cost:** about $50/month, covered by the $300 free-trial credit.

## Resource budget (8 GB)
nginx 128 MB · Postgres 512 MB · Kafka 1 GB · API 768 MB · watermark 1.5 GB (JVM 50%, rest for ffmpeg) · 2 GB swap.

## 1. One-time prerequisites (needs your Google account)
1. Create a GCP project and enable billing (the free trial is fine).
2. Open **Cloud Shell** (it has gcloud, terraform, docker and git) and get a committed checkout of the repository. See `00-Run-Guide.md` B2.

## 2. Provision with Terraform (`infra/terraform/`)
```bash
cp infra/terraform/terraform.tfvars.example infra/terraform/terraform.tfvars   # project_id, letsencrypt_email
terraform -chdir=infra/terraform init
terraform -chdir=infra/terraform plan
terraform -chdir=infra/terraform apply
```
| File | Resources |
| --- | --- |
| `main.tf` | Required APIs; Artifact Registry `vms`; service accounts `vms-runtime` (sign-as-self, log writer, registry reader) and `vms-builder` (registry writer, log writer, object viewer) |
| `storage.tf` | Private bucket (uniform access, public access prevention, CORS for the app origin, `raw/` lifecycle); object admin for `vms-runtime` on this bucket only |
| `secrets.tf` | Random DB, JWT and test-account passwords in Secret Manager; accessor binding per secret |
| `compute.tf` | Static IP; firewall 80/443 public, 22 from IAP only; Shielded Ubuntu 24.04 VM with the bootstrap startup script |
| `outputs.tf` | `app_url`, `bucket`, `registry`, `ssh_command`; writes `infra/.deploy.env` for `deploy.sh` |

State contains the generated secrets. Use the (commented) `gcs` backend for any shared setup.

## 3. Deploy
```bash
./infra/scripts/deploy.sh
```
1. Refuses to run with uncommitted changes, because images are tagged with the git SHA.
2. Cloud Build builds `api-service`, `watermark-service` and `web` for linux/amd64 and pushes them.
3. Copies the compose file, nginx config and scripts to the VM over the IAP tunnel.
4. `remote-deploy.sh` on the VM:
   1. Writes `/opt/vms/.env` from Secret Manager (`chmod 600`).
   2. On the first run, gets a Let's Encrypt certificate (certbot standalone).
   3. Pulls the images and runs `docker compose up -d`.
   4. Waits up to 3 minutes for the API and watermark health checks.
   5. **If they fail, it restarts the previous tag automatically.**
   6. Installs a weekly certificate-renewal cron job.

Open `https://<PUBLIC_HOST>` and run the smoke test:
```bash
LOGIN=operator@vms.demo PASSWORD="$(gcloud secrets versions access latest --secret vms-demo-user-password)" \
BASE_URL=https://<PUBLIC_HOST> ./infra/scripts/smoke-test.sh
```

## 4. Rollback
```bash
./infra/scripts/deploy.sh --rollback      # previous image tag
```

## 5. Operations
| Task | Command |
| --- | --- |
| SSH | `gcloud compute ssh vms-vm --zone us-central1-a --tunnel-through-iap` |
| Status | `sudo docker compose --env-file /opt/vms/.env -f /opt/vms/docker-compose.yml ps` |
| Logs | Cloud Logging → filter `resource.type="gce_instance" jsonPayload.container.name=~"vms-api"` (all logs are structured JSON with `correlationId`) |
| Test credentials | `gcloud secrets versions access latest --secret vms-demo-user-password` (admin: `vms-demo-admin-password`) |
| Rotate a secret | `printf '%s' "$NEW" \| gcloud secrets versions add vms-jwt-secret --data-file -` then `./infra/scripts/deploy.sh` |
| DLT inspection | `docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 --topic video.uploaded.v1-dlt --from-beginning` |
| Postgres backup | `docker compose exec -T postgres pg_dump -U vms vms \| gzip > vms-$(date +%F).sql.gz` |

## 6. Local (no cloud)
```bash
cp .env.example infra/.env   # fill in values
docker compose -f infra/docker-compose.yml --env-file infra/.env up -d --build
open http://localhost:8088
```
