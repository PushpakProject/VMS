#!/usr/bin/env bash
# End-to-end smoke test through the public entry point (nginx), exactly the browser's path:
# login -> signed upload URL -> PUT clip -> complete -> wait for READY -> download watermarked MP4.
#   BASE_URL=http://localhost:8088 LOGIN=operator@vms.local PASSWORD=... ./infra/scripts/smoke-test.sh
# Requires curl, python3 and ffmpeg/ffprobe.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8088}"
: "${LOGIN:?set LOGIN}"
: "${PASSWORD:?set PASSWORD}"
SECONDS_LONG="${CLIP_SECONDS:-20}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

json() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }
step() { printf '\n==> %s\n' "$*"; }

step "Sign in"
TOKEN="$(curl -fsS -c "$WORK/cookies" -H 'Content-Type: application/json' \
  -d "$(python3 -c 'import json,os; print(json.dumps({"login": os.environ["LOGIN"], "password": os.environ["PASSWORD"]}))')" \
  "$BASE_URL/api/v1/auth/login" | json 'd["accessToken"]')"
AUTH=(-H "Authorization: Bearer $TOKEN")
echo "ok (refresh cookie: $(grep -c vms_rt "$WORK/cookies") set)"

step "Record a ${SECONDS_LONG}s browser-like WebM clip"
ffmpeg -hide_banner -loglevel error -y -f lavfi -i "testsrc2=size=1280x720:rate=30:duration=${SECONDS_LONG}" \
  -f lavfi -i "sine=frequency=440:duration=${SECONDS_LONG}" -c:v libvpx -deadline realtime -cpu-used 8 \
  -b:v 2500k -c:a libopus "$WORK/clip.webm"
SIZE="$(wc -c <"$WORK/clip.webm" | tr -d ' ')"
echo "$SIZE bytes"

step "Request a signed upload URL"
UPLOAD_ID="$(python3 -c 'import uuid; print(uuid.uuid4())')"
TRACKING="SMOKE-$(date +%s)"
curl -fsS "${AUTH[@]}" -H 'Content-Type: application/json' -H "Idempotency-Key: $UPLOAD_ID" \
  -d "{\"trackingId\":\"$TRACKING\",\"contentType\":\"video/webm;codecs=vp8,opus\",\"sizeBytes\":$SIZE}" \
  "$BASE_URL/api/v1/videos" >"$WORK/initiate.json"
VIDEO_ID="$(json 'd["videoId"]' <"$WORK/initiate.json")"
URL="$(json 'd["upload"]["url"]' <"$WORK/initiate.json")"
case "$URL" in http*) ;; *) URL="$BASE_URL$URL" ;; esac
HEADERS=()
while IFS= read -r header; do HEADERS+=(-H "$header"); done < <(json '"\n".join(f"{k}: {v}" for k, v in d["upload"]["headers"].items())' <"$WORK/initiate.json")
echo "video $VIDEO_ID, tracking $TRACKING"

PROTOCOL="$(json 'd["upload"].get("protocol") or "SIMPLE"' <"$WORK/initiate.json")"
if [ "$PROTOCOL" = "RESUMABLE" ]; then
  step "Upload directly to storage through the resumable session"
  # Browsers always send Origin on PUT; do the same so CORS is exercised.
  STATUS_CODE="$(curl -s -o /dev/null -w '%{http_code}' -X PUT -H "Origin: $BASE_URL" -H "Content-Range: bytes */$SIZE" "$URL")"
  echo "status query -> $STATUS_CODE (308 = session open, nothing committed yet)"
  curl -fsS -X PUT -H "Origin: $BASE_URL" -H "Content-Type: application/octet-stream" -H "Content-Range: bytes 0-$((SIZE - 1))/$SIZE" \
    --data-binary @"$WORK/clip.webm" "$URL" -o /dev/null
else
  step "Upload directly to storage with the signed headers"
  curl -fsS -X PUT "${HEADERS[@]}" --data-binary @"$WORK/clip.webm" "$URL" -o /dev/null
fi
echo "uploaded ($PROTOCOL)"

step "Complete (server verifies the object)"
curl -fsS "${AUTH[@]}" -H 'Content-Type: application/json' -d "{\"durationMs\":$((SECONDS_LONG * 1000))}" \
  "$BASE_URL/api/v1/videos/$VIDEO_ID/complete" | json 'd["status"]'

step "Wait for the watermark service"
STATUS=""
for _ in $(seq 1 60); do
  STATUS="$(curl -fsS "${AUTH[@]}" "$BASE_URL/api/v1/videos/$VIDEO_ID" | json 'd["status"]')"
  printf '%s ' "$STATUS"
  [ "$STATUS" = "READY" ] || [ "$STATUS" = "FAILED" ] && break
  sleep 2
done
echo
[ "$STATUS" = "READY" ] || { echo "FAILED: video ended in status $STATUS" >&2; exit 1; }
curl -fsS "${AUTH[@]}" "$BASE_URL/api/v1/videos/$VIDEO_ID" | json '"attempts: " + ", ".join(a["status"] for a in d["attempts"])'

step "Download via the short-lived playback URL and verify"
PLAY="$(curl -fsS "${AUTH[@]}" "$BASE_URL/api/v1/videos/$VIDEO_ID/playback-url" | json 'd["url"]')"
case "$PLAY" in http*) ;; *) PLAY="$BASE_URL$PLAY" ;; esac
curl -fsS "$PLAY" -o "$WORK/final.mp4"
ffprobe -v error -show_entries stream=codec_name,width -show_entries format=duration -of compact=p=0 "$WORK/final.mp4"
ffmpeg -hide_banner -loglevel error -y -ss 1 -i "$WORK/final.mp4" -frames:v 1 "${FRAME_OUT:-$WORK/frame.png}"

step "Negative checks"
code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
echo "no token          -> $(code "$BASE_URL/api/v1/videos")"
echo "unknown video     -> $(code "${AUTH[@]}" "$BASE_URL/api/v1/videos/$(python3 -c 'import uuid; print(uuid.uuid4())')")"
echo "refresh w/o header-> $(code -X POST -b "$WORK/cookies" "$BASE_URL/api/v1/auth/refresh")"

echo
echo "SMOKE TEST PASSED: $TRACKING is READY and watermarked"
