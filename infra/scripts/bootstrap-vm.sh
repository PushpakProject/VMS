#!/usr/bin/env bash
# GCE startup script: installs Docker + Compose and registry credentials. Safe to re-run.
set -euo pipefail

if ! command -v docker >/dev/null 2>&1; then
  apt-get update
  apt-get install -y --no-install-recommends docker.io docker-compose-v2 openssl
  systemctl enable --now docker
fi

# 2 GB swap as a safety net for ffmpeg peaks on the 8 GB VM.
if [ ! -f /swapfile ]; then
  fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

REGION="$(curl -fs -H 'Metadata-Flavor: Google' \
  http://metadata.google.internal/computeMetadata/v1/instance/attributes/vms-region || echo us-central1)"
gcloud auth configure-docker "${REGION}-docker.pkg.dev" --quiet

mkdir -p /opt/vms/nginx
chmod 750 /opt/vms
