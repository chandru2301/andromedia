#!/usr/bin/env bash
# Phase 0 live probe against the frozen local corpus (1123 chunks / service=local).
# Requires API on :8083. Exit non-zero on any fixture failure.
set -euo pipefail

ANDRO_BASE="${ANDRO_BASE:-http://localhost:8083}"
SERVICE="${SERVICE:-local}"
TMPDIR="${TMPDIR:-/tmp}"
WORKDIR=$(mktemp -d "${TMPDIR}/andro-conflict-XXXXXX")
cleanup() { rm -rf "$WORKDIR"; }
trap cleanup EXIT

need() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "missing dependency: $1" >&2
    exit 2
  }
}

need curl
need python3

echo "== health =="
curl -fsS "${ANDRO_BASE}/api/v1/health" | python3 -c 'import sys,json; d=json.load(sys.stdin); assert d.get("status")=="UP", d'

echo "== corpus freeze check =="
curl -fsS "${ANDRO_BASE}/api/v1/index/services/${SERVICE}/overview" | python3 -c '
import json,sys
d=json.load(sys.stdin)
chunks=d.get("totalChunks")
types=set(d.get("sourceTypes") or [])
print(f"totalChunks={chunks} sourceTypes={sorted(types)}")
if chunks != 1123:
    print(f"WARN: expected frozen totalChunks=1123, got {chunks} — re-freeze docs if intentional", file=sys.stderr)
required={"ZOHO","SLACK","NOTION"}
missing=required-types
if missing:
    raise SystemExit(f"missing source types: {missing}")
'

post_investigate() {
  local query="$1"
  local out="$2"
  python3 - "$SERVICE" "$query" <<'PY' | curl -fsS -X POST "${ANDRO_BASE}/api/v1/investigate" \
    -H 'Content-Type: application/json' \
    -d @- -o "$out"
import json,sys
print(json.dumps({"service": sys.argv[1], "query": sys.argv[2], "context": {}}))
PY
}

assert_sources() {
  local label="$1"
  local file="$2"
  shift 2
  python3 - "$label" "$file" "$@" <<'PY'
import json,sys
label=sys.argv[1]
with open(sys.argv[2], encoding="utf-8") as f:
    payload=json.load(f)
need=set(sys.argv[3:])
evidence=payload.get("evidence") or []
sources={str(e.get("source") or "").upper() for e in evidence}
print(f"{label}: sources={sorted(sources)} trust={((payload.get('answer') or {}).get('trustState'))}")
missing=need-sources
if missing:
    raise SystemExit(f"{label} missing evidence sources {missing}; got {sorted(sources)}")
summary=((payload.get("answer") or {}).get("summaryText") or payload.get("summary") or "")
if "could not ground an answer against the eligible deals" in summary.lower() and "here are eligible" not in summary.lower() and "ask about" not in summary.lower():
    raise SystemExit(f"{label}: hard ledger dead-end")
PY
}

echo "== C1 search join =="
curl -fsS -X POST "${ANDRO_BASE}/api/v1/search" \
  -H 'Content-Type: application/json' \
  -d "{\"service\":\"${SERVICE}\",\"query\":\"Bright Horizon Fatima stalled\",\"limit\":8}" \
  | python3 -c '
import json,sys
d=json.load(sys.stdin)
hits=d.get("hits") or []
types={str(h.get("sourceType") or "").upper() for h in hits}
print("C1 search sources", sorted(types))
if "ZOHO" not in types or "SLACK" not in types:
    raise SystemExit(f"C1 search must join ZOHO+SLACK, got {sorted(types)}")
'

echo "== C1 investigate =="
post_investigate "Bright Horizon Fatima stalled" "$WORKDIR/c1.json"
assert_sources "C1" "$WORKDIR/c1.json" ZOHO SLACK

echo "== C2 investigate =="
post_investigate "What's going on with Bright Horizon and Fatima?" "$WORKDIR/c2.json"
assert_sources "C2" "$WORKDIR/c2.json" ZOHO SLACK

echo "== C3 investigate =="
post_investigate "Does Notion disagree with Zoho on Al Habtoor Phase 2 Expansion amount?" "$WORKDIR/c3.json"
assert_sources "C3" "$WORKDIR/c3.json" ZOHO NOTION

echo "== C4 investigate =="
post_investigate "Which deals look at risk this week?" "$WORKDIR/c4.json"
python3 - "$WORKDIR/c4.json" <<'PY'
import json,sys
with open(sys.argv[1], encoding="utf-8") as f:
    payload=json.load(f)
summary=((payload.get("answer") or {}).get("summaryText") or payload.get("summary") or "")
print("C4 summary:", summary[:280].replace("\n"," "))
low=summary.lower()
if "could not ground an answer against the eligible deals" in low and "here are eligible" not in low and "ask about" not in low:
    raise SystemExit("C4 hard dead-end")
print("C4 ok")
PY

echo "All conflict probes passed against ${ANDRO_BASE} service=${SERVICE}"
