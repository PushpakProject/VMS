#!/usr/bin/env bash
# Runs on the VM (as root): installs new config, obtains the TLS certificate on first run,
# starts the new image tag and rolls back automatically if health checks fail.
#   remote-deploy.sh <image-tag>      deploy a tag
#   remote-deploy.sh --rollback       go back to the previous tag
set -euo pipefail

APP=/opt/vms
STAGING=/tmp/vms
HEALTH_TIMEOUT=180

cd "$APP"
if [ -d "$STAGING" ]; then
  install -m 640 "$STAGING/docker-compose.prod.yml" "$APP/docker-compose.yml"
  install -m 640 "$STAGING/prod.conf.template" "$APP/nginx/prod.conf.template"
  install -m 600 "$STAGING/deploy.env" "$APP/deploy.env"
  install -m 700 "$STAGING/fetch-secrets.sh" "$APP/fetch-secrets.sh"
  install -m 700 "$STAGING/remote-deploy.sh" "$APP/remote-deploy.sh"
  rm -rf "$STAGING"
fi
# shellcheck disable=SC1091
source "$APP/deploy.env"
"$APP/fetch-secrets.sh"

compose() { docker compose --env-file "$APP/.env" -f "$APP/docker-compose.yml" "$@"; }

if [ "${1:-}" = "--rollback" ]; then
  TAG="$(cat "$APP/previous_tag")"
else
  TAG="${1:?usage: remote-deploy.sh <image-tag> | --rollback}"
fi
CURRENT="$(cat "$APP/current_tag" 2>/dev/null || true)"
export IMAGE_TAG="$TAG"

# First deploy: get the Let's Encrypt certificate (port 80 must be free).
if ! compose run --rm --entrypoint sh certbot -c "test -f /etc/letsencrypt/live/${PUBLIC_HOST}/fullchain.pem"; then
  compose stop web 2>/dev/null || true
  docker run --rm -p 80:80 -v vms_letsencrypt:/etc/letsencrypt certbot/certbot:v5.8.0 \
    certonly --standalone --non-interactive --agree-tos -m "$LETSENCRYPT_EMAIL" -d "$PUBLIC_HOST"
fi

echo "Deploying ${TAG} (previous: ${CURRENT:-none})"
compose pull api watermark web
compose up -d --remove-orphans

healthy() {
  for service in api watermark; do
    id="$(compose ps -q "$service")"
    [ -n "$id" ] || return 1
    [ "$(docker inspect -f '{{.State.Health.Status}}' "$id")" = "healthy" ] || return 1
  done
}

deadline=$((SECONDS + HEALTH_TIMEOUT))
until healthy; do
  if [ "$SECONDS" -ge "$deadline" ]; then
    echo "Health checks failed for ${TAG}" >&2
    if [ -n "$CURRENT" ] && [ "$CURRENT" != "$TAG" ]; then
      echo "Rolling back to ${CURRENT}" >&2
      export IMAGE_TAG="$CURRENT"
      compose up -d --remove-orphans
    fi
    exit 1
  fi
  sleep 5
done

[ -n "$CURRENT" ] && [ "$CURRENT" != "$TAG" ] && echo "$CURRENT" >"$APP/previous_tag"
echo "$TAG" >"$APP/current_tag"

# Weekly certificate renewal (webroot served by nginx), then reload nginx.
cat >/etc/cron.d/vms-certbot <<EOF
0 3 * * 1 root cd $APP && docker compose --env-file $APP/.env -f $APP/docker-compose.yml run --rm certbot renew --webroot -w /var/www/certbot --quiet && docker compose --env-file $APP/.env -f $APP/docker-compose.yml exec -T web nginx -s reload
EOF

echo "Deployed ${TAG}: https://${PUBLIC_HOST}"
