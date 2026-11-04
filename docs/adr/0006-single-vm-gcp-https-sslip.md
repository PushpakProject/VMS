# ADR 0006: Single GCE VM with Docker Compose, HTTPS on sslip.io

**Status:** accepted

## Context
The brief requires GCP, a free-tier project and Kafka. The e2-micro free tier (1 GB) can't run Kafka, Postgres, two JVMs and ffmpeg. Managed Kafka and Cloud SQL would use up the trial credit quickly. Chrome needs HTTPS for the camera, and Google OAuth rejects raw IPs as origins.

## Decision
**Hosting**
- One e2-standard-2 VM (about $50/month, from the $300 trial credit).
- Docker Compose with memory limits, health checks and `restart: unless-stopped`.
- Images built by Cloud Build and tagged with the git SHA; automatic rollback on failed health checks.
- Logs go to Cloud Logging through the `gcplogs` driver.

**HTTPS**
- Let's Encrypt on `<ip-with-dashes>.sslip.io`, which resolves to the VM's static IP.

**Provisioning**
- All infrastructure is declared in Terraform (`infra/terraform`), so it can be reviewed with `plan`, reproduced and destroyed. The application release stays a script (`deploy.sh`), because it changes on every commit.

**Security**
- Secrets come from Secret Manager.
- SSH only through IAP.
- A least-privilege service account with no key files.

## Consequences
- Everything in the brief works on a cheap setup, with identical containers locally and in the cloud.
- **Single point of failure.** The path to production:
  - Cloud Run for the API
  - a managed instance group or GKE for workers, autoscaled on consumer lag
  - Cloud SQL with high availability
  - Managed Kafka with RF=3
- Moving there is a configuration change, not a rewrite, because the services are 12-factor.
