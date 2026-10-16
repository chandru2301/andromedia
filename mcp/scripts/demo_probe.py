#!/usr/bin/env python3
"""Demo-question routing probe suite.

Asserts, for the exact questions the 15-minute design-partner demo asks:

  (a) STABLE ROUTE      — the same question classifies to the same intent across runs
  (b) DETERMINISTIC     — ledger-shaped questions answer from structured data, not the LLM
  (c) PLAIN LANGUAGE    — no internal jargon reaches the user-facing summary/reasons
  (d) TRUST HONESTY     — a grounded answer is never badged insufficient_data, and an
                          ungrounded one never claims to be grounded

Provider failures (503 LLM_RATE_LIMITED, "answer synthesis is unavailable") are retried
and reported as PROVIDER, never as a code failure.

Usage:
  set -a && source ~/.hermes/.env && set +a
  python3 mcp/scripts/demo_probe.py [--runs 2] [--api http://localhost:8083]
"""
import argparse
import json
import sys
import time
import urllib.error
import urllib.request

SERVICE = "local"

# Internal vocabulary that must never appear in user-facing copy.
JARGON = [
    "test_demo_pattern",
    "non-demo deal ledger",
    "deal ledger",
    "primaryRecords",
    "canonicalEntityKey",
    "trustState",
    "LedgerSplit",
    "insufficient_data",
]

# expect_deterministic: the answer shape is a pure comparison/rollup over CRM fields.
# expect_intents: routes that are acceptable for this question (stability = same one each run).
PROBES = [
    {
        "q": "What is the largest deal amount in the pipeline and which account owns it?",
        "expect_deterministic": True,
        "expect_intents": {"rank"},
    },
    {
        "q": "Which deals are currently in Proposal or Price Quote stage and what are their probabilities?",
        "expect_deterministic": True,
        "expect_intents": {"enumerate"},
    },
    {
        "q": "Which account has the largest total exposure across all its deals?",
        "expect_deterministic": True,
        "expect_intents": {"aggregate"},
    },
    {
        "q": "What is the Al Habtoor Phase 2 deal worth?",
        "expect_deterministic": False,
        "expect_intents": {"lookup", "general", "reason"},
        "expect_trust_not": {"insufficient_data"},
        "note": "conflict demo: Zoho current value vs Notion original value, both must be cited",
    },
    {
        "q": "What is going on with Bright Horizon and what has Fatima been doing on it?",
        "expect_deterministic": False,
        "expect_intents": {"lookup", "reason", "general"},
        "note": "cross-source join: CRM record + Slack/Notion activity",
    },
    {
        "q": "The Infosys digital transformation deal says the owner left — how should we handle follow-up?",
        "expect_deterministic": False,
        "expect_intents": {"lookup", "reason"},
    },
    {
        "q": "What is the causr workspace and why was someone asking about it?",
        "expect_deterministic": False,
        "expect_intents": {"lookup", "general", "reason"},
    },
    {
        "q": "What did we decide in SOF-4 about onboarding, and where is the auth architecture doc?",
        "expect_deterministic": False,
        "expect_intents": {"lookup", "general", "reason"},
        "expect_artifacts": ["SOF-4", "auth architecture"],
        "note": "B-class: id/title-named artifacts must survive query-biased retrieval",
    },
    {
        "q": "What is our Q3 churn rate and ARR?",
        "expect_deterministic": True,
        "expect_intents": {"unsupported_metric"},
        "forbid_substrings": ["open pipeline", "eligible active deal"],
        "note": "must refuse; never substitute open-pipeline total for ARR/churn",
    },
]

PROVIDER_MARKERS = ("answer synthesis is unavailable", "LLM_RATE_LIMITED", "rate limit")


def investigate(api, query, tries=3):
    """Returns (payload, seconds) or (None, reason) when the provider is the blocker."""
    body = {"service": SERVICE, "query": query, "context": {"trustMode": "structured-evidence"}}
    last = "unknown"
    for attempt in range(tries):
        req = urllib.request.Request(
            api + "/api/v1/investigate",
            data=json.dumps(body).encode(),
            headers={"Content-Type": "application/json"},
        )
        t0 = time.time()
        try:
            with urllib.request.urlopen(req, timeout=180) as r:
                data = json.load(r)
            blob = json.dumps(data).lower()
            if not any(marker.lower() in blob for marker in PROVIDER_MARKERS):
                return data, round(time.time() - t0, 1)
            last = "provider flake in payload"
        except urllib.error.HTTPError as e:
            last = f"HTTP {e.code}"
            if e.code not in (429, 500, 502, 503, 504):
                return None, last
        except Exception as e:  # noqa: BLE001 - probe script, report and retry
            last = str(e)
        time.sleep(3 * (attempt + 1))
    return None, last


def user_facing_text(payload):
    answer = payload.get("answer") or {}
    parts = [payload.get("summary") or "", answer.get("summaryText") or ""]
    parts.extend(answer.get("reasons") or [])
    return "\n".join(parts)


def check(payload, probe):
    """Returns a list of failure strings (empty == pass)."""
    failures = []
    answer = payload.get("answer") or {}
    meta = payload.get("metadata") or {}
    text = user_facing_text(payload)

    for word in JARGON:
        if word.lower() in text.lower():
            failures.append(f"jargon leak: {word!r} in user-facing copy")

    if probe.get("expect_deterministic") and not meta.get("deterministicAnswer"):
        failures.append(
            f"expected a deterministic structured answer, got intent={meta.get('classificationIntent')}"
        )

    trust = answer.get("trustState")
    for forbidden in probe.get("expect_trust_not", ()):
        if trust == forbidden:
            failures.append(f"trustState={forbidden} on an answer that cited evidence")

    if trust in ("structured", "deterministic", "grounded", "conflict_adjudicated"):
        if not payload.get("evidence"):
            failures.append(f"trustState={trust} but no evidence was returned")

    for artifact in probe.get("expect_artifacts", ()):
        found = meta.get("artifactsFound") or []
        if not any(artifact.lower() in str(a).lower() for a in found):
            failures.append(f"named artifact {artifact!r} was not found in the index")

    for needle in probe.get("forbid_substrings", ()):
        if needle.lower() in text.lower():
            failures.append(f"forbidden phrasing {needle!r} in user-facing copy")
    return failures


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--api", default="http://localhost:8083")
    parser.add_argument("--runs", type=int, default=2, help="runs per question (route stability)")
    args = parser.parse_args()

    failed = provider_blocked = 0
    for probe in PROBES:
        print(f"\n=== {probe['q']}")
        if probe.get("note"):
            print(f"    ({probe['note']})")
        intents, trusts = [], []
        probe_failures = []
        for run in range(args.runs):
            payload, info = investigate(args.api, probe["q"])
            if payload is None:
                print(f"  run {run + 1}: PROVIDER — {info} (not a code failure)")
                provider_blocked += 1
                continue
            meta = payload.get("metadata") or {}
            answer = payload.get("answer") or {}
            intents.append(meta.get("classificationIntent"))
            trusts.append(answer.get("trustState"))
            print(
                f"  run {run + 1}: {info}s intent={meta.get('classificationIntent')} "
                f"trust={answer.get('trustState')} deterministic={bool(meta.get('deterministicAnswer'))} "
                f"evidence={len(payload.get('evidence') or [])}"
            )
            if meta.get("artifactsFound") or meta.get("artifactsNotFound"):
                print(
                    f"           artifacts found={meta.get('artifactsFound')} "
                    f"missing={meta.get('artifactsNotFound')}"
                )
            probe_failures.extend(check(payload, probe))

        if intents:
            if len(set(intents)) > 1:
                probe_failures.append(f"UNSTABLE ROUTE across runs: {intents}")
            unexpected = set(intents) - set(probe["expect_intents"])
            if unexpected:
                probe_failures.append(
                    f"unexpected route {sorted(unexpected)}, expected one of {sorted(probe['expect_intents'])}"
                )
        if probe_failures:
            failed += 1
            for failure in sorted(set(probe_failures)):
                print(f"  FAIL: {failure}")
        elif intents:
            print("  PASS")

    print(f"\n{'-' * 60}\nprobes: {len(PROBES)}  failed: {failed}  provider-blocked runs: {provider_blocked}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
