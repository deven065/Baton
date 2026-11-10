#!/usr/bin/env bash
# Loads the synthetic Project Annapurna sources into a running Baton backend and starts reconstruction.
# Usage: demo/load-demo.sh [api-base-url]     (default http://localhost:8080)
# Then score it: python demo/evaluate.py [api-base-url]
# PROJECT=<id> loads into that project instead of the first one.
# Needs: curl. Uses the seeded lead persona; the demo password defaults to the value in application.properties.
set -euo pipefail

API="${1:-http://localhost:8080}"
PASSWORD="${DEMO_PASSWORD:-annapurna-demo}"
DIR="$(cd "$(dirname "$0")/annapurna" && pwd)"

json_field() { sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p" | head -1; }

TOKEN=$(curl -sf -X POST "$API/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"workspace\":\"demo\",\"email\":\"neha@annapurna.test\",\"password\":\"$PASSWORD\"}" | json_field token)
AUTH="Authorization: Bearer $TOKEN"
PROJECT="${PROJECT:-$(curl -sf "$API/api/projects" -H "$AUTH" | json_field id)}"
echo "Project: $PROJECT"

upload() { # type title sensitivity groups file
  local groups=()
  [ -n "$4" ] && groups=(-F "aclGroups=$4")
  curl -sf -X POST "$API/api/projects/$PROJECT/sources" -H "$AUTH" \
    -F "type=$1" -F "title=$2" -F "sensitivity=$3" "${groups[@]}" \
    -F authorisationAttested=true -F "file=@$DIR/$5" | json_field jobId
}

upload MEETING_NOTES "Weekly sync 2026-10-08" INTERNAL "" 2026-10-08-weekly-sync.md >/dev/null
upload CHAT_EXPORT "Slack #dev" INTERNAL "" dev.json >/dev/null
upload TASK_CSV "Jira export" INTERNAL "" tasks.csv >/dev/null
upload DOCUMENT "Architecture notes" CONFIDENTIAL engineering architecture.md >/dev/null
upload DOCUMENT "Vendor contract" RESTRICTED operations vendor-contract.md >/dev/null
upload CHAT_EXPORT "WhatsApp Annapurna Core" INTERNAL "" "WhatsApp Chat with Annapurna Core.txt" >/dev/null
upload CHAT_EXPORT "Discord #design" INTERNAL "" "discord-design.json" >/dev/null
echo "Uploaded 7 sources; waiting for ingestion..."

for _ in $(seq 1 60); do
  busy=$(curl -sf "$API/api/projects/$PROJECT/sources" -H "$AUTH" | grep -cE '"status":"(PENDING|SYNCING)"' || true)
  [ "$busy" = "0" ] && break
  sleep 1
done

PRIYA=$(curl -sf "$API/api/projects/$PROJECT" -H "$AUTH" | sed 's/},{/}\n{/g' | grep 'Priya Sharma' | json_field userId)
HANDOVER=$(curl -sf -X POST "$API/api/projects/$PROJECT/handovers" -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"departingUserId\":\"$PRIYA\",\"title\":\"Priya's handover (graduating)\",\"scenario\":\"PLANNED\"}" | json_field id)
JOB=$(curl -sf -X POST "$API/api/handovers/$HANDOVER/reconstruct" -H "$AUTH" | json_field jobId)
echo "Handover $HANDOVER reconstructing (job $JOB)..."

for _ in $(seq 1 120); do
  status=$(curl -sf "$API/api/jobs/$JOB" -H "$AUTH" | json_field status)
  [ "$status" = "SUCCEEDED" ] || [ "$status" = "FAILED" ] && break
  sleep 1
done
echo "Reconstruction: $status"
curl -sf "$API/api/handovers/$HANDOVER/summary" -H "$AUTH"; echo
