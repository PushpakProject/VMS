#!/usr/bin/env bash
# Runs on the VM: writes /opt/vms/.env from Secret Manager (via the VM's service account) plus
# the non-secret deployment settings. The file is readable by root only.
set -euo pipefail

DEPLOY_ENV="/opt/vms/deploy.env"
OUT="/opt/vms/.env"
# shellcheck disable=SC1090
source "$DEPLOY_ENV"

secret() { gcloud secrets versions access latest --secret "$1" --project "$PROJECT_ID"; }

umask 077
{
  echo "REGISTRY=${REGISTRY}"
  echo "PUBLIC_HOST=${PUBLIC_HOST}"
  echo "VMS_GCS_BUCKET=${VMS_GCS_BUCKET}"
  echo "VMS_GOOGLE_CLIENT_ID=${VMS_GOOGLE_CLIENT_ID:-}"
  echo "VMS_DB_PASSWORD=$(secret vms-db-password)"
  echo "VMS_JWT_SECRET=$(secret vms-jwt-secret)"
  echo "VMS_DEMO_USER_EMAIL=operator@vms.demo"
  echo "VMS_DEMO_USER_PASSWORD=$(secret vms-demo-user-password)"
  echo "VMS_DEMO_ADMIN_EMAIL=admin@vms.demo"
  echo "VMS_DEMO_ADMIN_PASSWORD=$(secret vms-demo-admin-password)"
} >"${OUT}.tmp"
mv "${OUT}.tmp" "$OUT"
echo "Wrote $OUT"
