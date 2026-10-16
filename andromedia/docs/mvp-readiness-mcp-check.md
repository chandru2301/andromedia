# Andro design-partner readiness (MCP check)

**Verdict: conversation-ready for a scripted 15-minute design-partner demo. Not product-ready for unsupervised strangers.** Mid-September is 5–8 partner conversations to learn about cross-source pain — not a Jan-2027 public MVP.

Checkpoint: commit `a9b0634` on `design-partner-readiness` cleared the large uncommitted tree — that is **not** an MVP blocker.

---

## Surfaces

| Surface | Role |
|---------|------|
| `andro/` Electron + Vite | **Partner demo surface** (Chat / investigate) |
| `landing/` | Public web MVP |
| MCP / App Runner | Optional; retarget before hosted demos |

**Boot (step zero):** load env from `~/.hermes/.env`, run Spring Boot from `andromedia/` (`mvn -pl core -am spring-boot:run`), API `http://localhost:8083`, then desktop `andro/`.

---

## Frozen demo corpus (local) — authoritative snapshot

Same freeze as [`cross-source-conflict-fix-plan.md`](cross-source-conflict-fix-plan.md). Do not cite the older Sep-4 “102 chunks” figure.

```text
freezeDate:        2026-09-07
service:           local
totalChunks:       1123
sourcesConnected:  6
sourceTypes:       GOOGLE_CHAT, JIRA, LINEAR, NOTION, SLACK, ZOHO
zohoConnectionKey: 5d5632adbd02204c
lastSyncAt:        2026-09-04T13:38:45.118Z
indexPaths:
  ~/.andromedia/index/by-service/local
  ~/.andromedia/index/by-service/local__z_5d5632adbd02204c
indexSizeBytes:    844517
```

Historical note: an earlier MCP probe (2026-09-04) saw ~102 chunks before Google Chat / fuller sync. That snapshot is obsolete.

---

## What the check actually said

Hybrid search joined Slack *“Bright Horizon deal is actually stalled, Fatima went quiet”* to Zoho **Bright Horizon - Enterprise License** (Negotiation/Review, AED 85,000, 90%). That is the product.

Plumbing around that claim (investigate pin/conflict, ledger dead-end, Google Chat noise in Chat, hosted MCP URL) is the remaining gap — see the conflict fix plan.

---

## Surfaces probed (historical + live API)

| Surface | Result |
|---|---|
| Local API `/api/v1/health` | `200` `{"status":"UP"}` when booted |
| Local index overview | **1123 chunks** (frozen above) |
| MCP `andro_search` | Works; Zoho + Slack evidence useful |
| MCP / Chat `andro_investigate` | Improving via conflict green path; was unreliable (ledger reject, rate limits) |
| App Runner `https://tuuf7zapih.ap-south-1.awsapprunner.com` | Health `200` |
| Landing `https://andro-app.vercel.app` | `200` |
| Remote MCP | Retarget with `mcp/scripts/update-mcp-env.sh` to `tuuf7zapih` |

---

## Demo script (15 minutes)

Lead with search or Chat after conflict green path. Prefer desktop `andro`.

1. Boot API + desktop (see step zero).
2. Ask: “What’s going on with Bright Horizon / Fatima?”
3. Show connected sources (six on frozen corpus).
4. Pace LLM calls if using hosted rate-limited providers.
5. Skip file history (no CODE/GIT on this index).

Before the call: `./scripts/reindex-local.sh` only if story deals drift — then **re-freeze** both docs.

---

## Do before any human / partner sees hosted surfaces

1. **Rotate leaked credentials (HARD GATE — status: unverified).** Keys were visible in App Runner describe output. Rotate Groq, DeepSeek, and RDS, then push with `andromedia/scripts/update-apprunner-env.sh`. This outranks demo work. Do not commit secrets. Until confirmed, treat stranger/partner hosted demos as blocked.
2. **Retarget hosted MCP** to live App Runner (`./mcp/scripts/update-mcp-env.sh`); confirm health shows `tuuf7zapih`.
3. Reindex only if needed; keep freeze metadata in sync.

---

## Conflict green path (Chat 3→6)

See [`cross-source-conflict-fix-plan.md`](cross-source-conflict-fix-plan.md): Phase 0 fixtures → 1.1 pin → 1.2B scoped second search → 2.1 stage conflict + 2.2 **required** post-check → C4 graceful ledger → cheap evidence UI on `andro/`.

---

## Defer to post-Jan (real users on real data)

- CODE/GIT ingestion
- App Runner index durability
- Embedding hardening
- Full Groq fallback engineering

---

## Jan-2027 stranger-MVP bar (not this month)

Durable prod index, embeddings, CODE/GIT if the pitch includes engineering, investigate without a dead-end, **leaked keys rotated**, LLM path that survives two questions.
