# Plan sheet — fix cross-source conflict (Chat)

**Goal:** Make cross-source conflict demoable in Chat for design-partner conversations (Slack/CRM stage-risk + Notion/Zoho amount), without waiting for full stranger-MVP polish.

**Checked against / corpus freeze:** 2026-09-07 local service `local` (see freeze block below). Phase 0 fixtures and green claims apply **only** to this snapshot. Re-freeze after any reindex that changes story deals.

**Non-goals for this sheet:** CODE/GIT, App Runner durability, Groq TPM engineering, full reindex productization.

---

## Surfaces

| Surface | Role |
|---------|------|
| [`andro/`](../../andro/) Electron + Vite | **Partner demo surface** — Chat/investigate in `investigation-page.tsx` |
| [`landing/`](../../landing/) | Public web MVP — not the conflict demo surface |
| MCP / App Runner | Optional after env retarget; not required for local partner script |

---

## Frozen demo corpus (local)

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
storyDeals:        Bright Horizon / Fatima (Slack↔Zoho), Al Habtoor Phase 2 (Notion↔Zoho amount)
```

Boot step zero: env from `~/.hermes/.env`, `mvn -pl core -am spring-boot:run` from `andromedia/`, API `:8083`, then desktop `andro/`.

**Credential rotation gate (unverified):** App Runner–exposed keys must be rotated (Groq/DeepSeek/RDS + `scripts/update-apprunner-env.sh`) before any partner sees hosted/MCP. Local engineering may proceed; outreach stays blocked until confirmed.

---

## Problem in one line

`/api/v1/search` can join Slack + Zoho for Bright Horizon; Chat/investigate often never puts both sides into evidence, and the conflict engine only detected **amount** mismatches — not Slack “stalled” vs CRM Negotiation.

---

## Broken sections → fix order

```text
P0  Investigate retrieval (named deals drowned by Google Chat)
P0  Named-entity pin so Slack+Zoho (or Notion+Zoho) merge into evidence
P1  Conflict model: stage / status / risk (not only amount)
P1  Surface conflict_adjudicated in answer + trust metadata
P1  Evidence UI: stop rendering chat spaces as CRM DEAL rows
P2  Ledger dead-end on qualify / “at risk this week”
P2  Empty LLM synthesis fail-loud (required post-check)
P3  Chip / suggestion data alignment (King, TechNova) — defer
```

---

## Phase 0 — Prove the contract (red fixtures)

**Acceptance fixtures** (must pass against the frozen corpus):

| ID | Query | Must retrieve | Must answer |
|----|-------|---------------|-------------|
| C1 | `Bright Horizon Fatima stalled` | SLACK stall + ZOHO deal | Slack says stalled/at risk; CRM still Negotiation/Review 90% / AED 85k |
| C2 | Natural: `What's going on with Bright Horizon and Fatima?` | Same as C1 (not Google Chat hospital/job spam) | Same conflict framed clearly |
| C3 | `Does Notion disagree with Zoho on Al Habtoor Phase 2 Expansion amount?` | ZOHO + NOTION amount chunks | Both amounts + sources + timestamps (Rule 7) |
| C4 | `Which deals look at risk this week?` | Zoho ledger | No sole “could not ground against eligible deals” dead-end |

**Files:** JUnit under `core/src/test/.../investigate/` + [`scripts/probe-conflict.sh`](../scripts/probe-conflict.sh).

---

## Phase 1 — Retrieval: make investigate see what search sees (P0)

### 1.1 Named-entity pin for Bright Horizon / Fatima / Al Habtoor

Strengthen resolve so conversational queries (extra person/risk tokens) still pin CRM deals; if classifier entity misses, retry with full query. Merge related Slack/Notion via existing filters.

**Done when:** C1/C2 investigate evidence includes at least one `ZOHO` + one `SLACK` for Bright Horizon.

### 1.2 Scoped second search (**B only — locked**)

When a CRM named-entity pin fires, run a **scoped second search** with `sourceTypes=[ZOHO,SLACK,NOTION]` and merge into evidence. Do **not** global-rank demote Google Chat (A) or soft-filter Chat corpus-wide (C).

**Done when:** C2 top evidence is not Google Chat spam.

---

## Phase 2 — Conflict model beyond amount (P1)

### 2.1 Stage / status / risk conflict marker

Detect Slack risk language (stalled, at risk, went quiet, blocked, …) vs Zoho open Stage/probability for the pinned entity. Emit `CONFLICT DETECTED: field=status, …` and set trust to `conflict_adjudicated`. Keep amount path for C3.

### 2.2 Prompt / integrity alignment (**required post-check**)

Multi-source synthesis always receives the conflict marker. **Required:** if marker present but the model omits a cited side / conflict framing, fail loud and fall back to a deterministic conflict template — never silently degrade on stage.

---

## Phase 3 — Chat evidence UI (P1, cheap truthfulness)

Partner surface: [`andro/src/pages/investigation-page.tsx`](../../andro/src/pages/investigation-page.tsx).

- CRM table only for Zoho / structured CRM rows.
- Non-CRM: list with source badge, name, timestamp/snippet.
- Grounded count matches visible evidence.

---

## Phase 4 — Ledger / qualify dead-end (P2 / C4)

On invariant failure: graceful top eligible deals + “name a deal” — not a hard dead-end only.

---

## Phase 5 — Chips (P3 defer)

King / TechNova chips: hide or retarget only if blocking screenshots. Settings filters: low priority.

---

## Implementation sequence

| Day | Deliverable |
|-----|-------------|
| 0 | Corpus freeze in both docs + Phase 0 red fixtures |
| 1 | 1.1 pin + 1.2B scoped search (C1/C2 API green) |
| 2 | 2.1 stage marker + 2.2 required post-check + C3 |
| 2–3 | C4 graceful ledger + Phase 3 evidence split |

---

## Demo script after green

1. Desktop `andro` Chat: “What’s going on with Bright Horizon and Fatima?” → Slack stalled vs CRM Negotiation, both in evidence, `conflict_adjudicated`.
2. “Does Notion disagree with Zoho on Al Habtoor amount?” → both amounts.
3. Optional: stale deals warm-up — not a substitute for conflict.

Do **not** open with King/TechNova or open-ended risk until C4 is green. Hosted/MCP blocked until credential rotation verified.

---

## Success bar

Cross-source conflict is **conversation-ready** when C1–C3 pass in desktop Chat against the frozen 1123-chunk corpus, evidence panel shows correct source types, and C4 is not a sole dead-end.
