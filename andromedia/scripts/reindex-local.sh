#!/usr/bin/env bash
# Reindex Slack + Zoho (demo sources) against a running local Andro API.
# Uses vaulted credentials already saved for SERVICE — does not invent tokens.
#
# Usage (from andromedia/):
#   ./scripts/reindex-local.sh
#   INCLUDE_NOTION=1 INCLUDE_JIRA=1 INCLUDE_LINEAR=1 ./scripts/reindex-local.sh
set -euo pipefail

ANDRO_BASE="${ANDRO_BASE:-http://localhost:8083}"
ANDRO_BASE="${ANDRO_BASE%/}"
SERVICE="${SERVICE:-local}"
POLL_SECONDS="${POLL_SECONDS:-5}"
MAX_POLLS="${MAX_POLLS:-60}"

health="$(curl --silent --show-error --fail --max-time 8 "${ANDRO_BASE}/api/v1/health" || true)"
if [[ "${health}" != *'"status":"UP"'* && "${health}" != *'"status": "UP"'* ]]; then
  echo "Andro API is not up at ${ANDRO_BASE}/api/v1/health" >&2
  echo "Start core first, then retry." >&2
  exit 1
fi

overview="$(curl --silent --show-error --fail --max-time 15 \
  "${ANDRO_BASE}/api/v1/index/services/${SERVICE}/overview")"
connection_id="$(python3 -c 'import json,sys; print(json.load(sys.stdin).get("zohoConnectionKey") or "")' <<<"${overview}")"

post_job() {
  local path="$1"
  local body="$2"
  local tmp
  tmp="$(mktemp)"
  local http
  http="$(curl --silent --show-error --max-time 30 \
    -o "${tmp}" -w '%{http_code}' \
    -H 'Content-Type: application/json' \
    -X POST "${ANDRO_BASE}${path}" \
    -d "${body}")"
  if [[ "${http}" != "202" && "${http}" != "200" ]]; then
    echo "POST ${path} failed HTTP ${http}: $(cat "${tmp}")" >&2
    rm -f "${tmp}"
    return 1
  fi
  python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print(d["jobId"])' "${tmp}"
  rm -f "${tmp}"
}

poll_job() {
  local source="$1"
  local job_id="$2"
  local i status
  for ((i = 0; i < MAX_POLLS; i++)); do
    local payload
    payload="$(curl --silent --show-error --fail --max-time 15 \
      "${ANDRO_BASE}/api/v1/index/${source}/${job_id}")"
    status="$(python3 -c 'import json,sys; print((json.load(sys.stdin).get("status") or "").lower())' <<<"${payload}")"
    case "${status}" in
      completed|success|done)
        echo "==> ${source} ${job_id} ${status}"
        return 0
        ;;
      failed|error)
        echo "${source} job ${job_id} failed: ${payload}" >&2
        return 1
        ;;
    esac
    sleep "${POLL_SECONDS}"
  done
  echo "${source} job ${job_id} timed out (last status=${status:-unknown})" >&2
  return 1
}

echo "==> Slack reindex (vaulted bot token for service=${SERVICE})"
slack_job="$(post_job "/api/v1/index/slack" "{\"service\":\"${SERVICE}\",\"embed\":false}" || true)"
if [[ -z "${slack_job}" ]]; then
  echo "Slack enqueue failed. Save a Slack bot token via credentials/Plugins, then retry." >&2
  exit 1
fi
poll_job slack "${slack_job}"

if [[ -z "${connection_id}" ]]; then
  echo "No zohoConnectionKey on overview for service=${SERVICE}." >&2
  echo "Connect Zoho in Plugins (exchange grant) so the vault has a connection, then retry." >&2
  exit 1
fi

echo "==> Zoho reindex (connectionId from overview, not printed)"
zoho_job="$(post_job "/api/v1/index/zoho" "{\"service\":\"${SERVICE}\",\"connectionId\":\"${connection_id}\",\"embed\":false,\"fullRefresh\":true}" || true)"
if [[ -z "${zoho_job}" ]]; then
  echo "Zoho enqueue failed. Vaulted connection may need reauth." >&2
  exit 1
fi
poll_job zoho "${zoho_job}"

if [[ "${INCLUDE_NOTION:-0}" == "1" ]]; then
  echo "==> Notion reindex"
  notion_job="$(post_job "/api/v1/index/notion" "{\"service\":\"${SERVICE}\",\"embed\":false}")"
  poll_job notion "${notion_job}"
fi
if [[ "${INCLUDE_JIRA:-0}" == "1" ]]; then
  echo "==> Jira reindex"
  jira_job="$(post_job "/api/v1/index/jira" "{\"service\":\"${SERVICE}\",\"embed\":false}")"
  poll_job jira "${jira_job}"
fi
if [[ "${INCLUDE_LINEAR:-0}" == "1" ]]; then
  echo "==> Linear reindex"
  linear_job="$(post_job "/api/v1/index/linear" "{\"service\":\"${SERVICE}\",\"embed\":false}")"
  poll_job linear "${linear_job}"
fi

echo "==> Overview"
curl --silent --show-error --fail --max-time 15 \
  "${ANDRO_BASE}/api/v1/index/services/${SERVICE}/overview" | python3 -c '
import json,sys
d=json.load(sys.stdin)
print("totalChunks:", d.get("totalChunks"))
print("lastSyncAt:", d.get("lastSyncAt"))
print("sourceTypes:", d.get("sourceTypes"))
print("indexExists:", d.get("exists"))
'
