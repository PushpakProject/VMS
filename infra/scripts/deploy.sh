#!/usr/bin/env bash
# Builds the three images with Cloud Build (linux/amd64, tagged with the git SHA), copies the
# compose/nginx config to the VM over IAP and runs remote-deploy.sh there.
#   ./infra/scripts/deploy.sh            deploy HEAD
#   ./infra/scripts/deploy.sh --rollback roll back to the previous tag
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
if [ ! -f "$ROOT/infra/.deploy.env" ]; then
  echo "infra/.deploy.env not found: run 'terraform -chdir=infra/terraform apply' first." >&2
  exit 1
fi
# shellcheck disable=SC1091
source "$ROOT/infra/.deploy.env"
SSH=(gcloud compute ssh "$VM_NAME" --zone "$ZONE" --project "$PROJECT_ID" --tunnel-through-iap)

if [ "${1:-}" = "--rollback" ]; then
  "${SSH[@]}" --command "sudo /opt/vms/remote-deploy.sh --rollback"
  exit 0
fi

if [ -n "$(git -C "$ROOT" status --porcelain)" ]; then
  echo "Commit your changes first: images are tagged with the git SHA." >&2
  exit 1
fi
TAG="$(git -C "$ROOT" rev-parse --short=12 HEAD)"

echo "==> Building images ${REGISTRY}/*:${TAG}"
gcloud builds submit "$ROOT" --project "$PROJECT_ID" --region "$REGION" \
  --config "$ROOT/infra/cloudbuild.yaml" --substitutions "_REGISTRY=${REGISTRY},_TAG=${TAG}" \
  --service-account "projects/${PROJECT_ID}/serviceAccounts/${BUILD_SERVICE_ACCOUNT}"

echo "==> Copying configuration to ${VM_NAME}"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
cp "$ROOT/infra/docker-compose.prod.yml" "$ROOT/infra/nginx/prod.conf.template" \
  "$ROOT/infra/scripts/fetch-secrets.sh" "$ROOT/infra/scripts/remote-deploy.sh" \
  "$ROOT/infra/.deploy.env" "$STAGE/"
mv "$STAGE/.deploy.env" "$STAGE/deploy.env"
"${SSH[@]}" --command "rm -rf /tmp/vms && mkdir -p /tmp/vms"
gcloud compute scp --zone "$ZONE" --project "$PROJECT_ID" --tunnel-through-iap \
  "$STAGE"/* "${VM_NAME}:/tmp/vms/"

echo "==> Deploying on ${VM_NAME}"
"${SSH[@]}" --command "sudo bash /tmp/vms/remote-deploy.sh ${TAG}"
