# Zoho FDE scorecard (honest status)

Last updated: 2026-07-21  
Purpose: separate **what is built and working** from **what is still a real production gap**. This corrects an earlier draft scorecard that marked some shipped work as missing.

**Current honest score: ~7.0 / 10**  
(Incremental durable watermark + Deleted Records cleanup landed; remaining gaps are entity resolution, semantic normalization, multi-CRM, and unattended multi-org cron.)

---

## Executive summary

Andro has a working Zoho FDE foundation: dynamic schema discovery, deal-centric enrichment, quality/demo signals, intelligence-layer guards, and a CRM-agnostic schema package with Zoho as the first adapter.

What still separates this from something an enterprise trusts at scale is mostly **entity semantics and multi-CRM depth**: duplicate/account resolution, loss-reason normalization, non-Zoho CRM adapters, and persisted credentials for unattended multi-org reconciliation. Durable incremental watermarks and Deleted Records cleanup are now in place.

The architecture is in the right place for those next layers. They are not blocked by a rewrite.

---

## Scoreboard (as of this doc)

| Capability | Score | Status |
|---|---:|---|
| Schema discovery (dynamic) | 7/10 | Shipped |
| Data quality scoring + demo detection | 6/10 | Shipped |
| Relationship enrichment | 5/10 | Shipped (deal-centric only) |
| Narrative quality | 6/10 | Shipped (Structured block + remount fix) |
| Intent classification | 6/10 | Shipped (LLM + follow-up→qualify heuristics) |
| Stale / deleted record handling | 7/10 | Shipped (Deleted Records API on incremental; full refresh safety net) |
| Incremental sync | 7/10 | Shipped (durable `Modified_Time` watermark + Search Records) |
| Duplicate / entity resolution | 0/10 | Not built |
| Cross-field semantic normalization | 0/10 | Not built |
| Multi-CRM abstraction | 2/10 | Interface + Zoho only (product: Zoho-only fail-fast) |
| Volume / rate-limit handling | 6/10 | Shipped (429/5xx retry + Retry-After) |

**Weighted takeaway:** happy-path investigation on a re-indexed Zoho org is solid. Gaps are production hardening and multi-CRM depth, not “does the demo work.”

---

## What we have done

### Universal CRM schema layer — shipped

- Package `com.andromedia.ingestion.crm`: `CrmField`, types/categories, `CrmRecord`, `CrmSchemaAdapter`, `CrmSchemaCache`, `CrmFetchQuery`.
- Three-layer `FieldClassifier` (data type → first-page samples → optional LLM A/B/C; undecided defaults to STRUCTURED).
- `CrmTableInferenceEngine` for custom/unknown modules (standard modules protected).
- `ZohoSchemaAdapter` drives discover → classify → dynamic `fields=` fetch (50-field batches).
- `CrmDataGapReport` emitted in Zoho job metadata (`apiName`, type, completeness, recommendation).

### Zoho pipeline intelligence — shipped

- Per-deal quality scoring and signals.
- Demo / sample detection (`test_demo_pattern`), account-level propagation; demos indexed but excluded from primary recommendations.
- Deal-centric enrichment (account, contacts, notes, activities).
- Investigate intelligence layer: full active ledger, pipeline health facts, anti-hallucination / invariant guards.

### Evidence and narrative fixes — shipped

Earlier scorecard listed these as pending; they are in the tree:

| Fix | What landed |
|---|---|
| Narrative remount collision | `evidenceFromLedger` joins by `chunkId` / `path` / name+account; ledger carries stored narrative (fixes 50× identical “Chandru V” narratives). |
| Sparse BM25 narrative | `ZohoUnifiedChunkBuilder` always emits a `Structured:` block (Account, Stage, Amount, Probability, Close, Next step). |
| Demo filtering from evidence | Demo / `test_demo_pattern` rows omitted from Investigate evidence array (still used for guard / demo counts). |
| Follow-up intent | Follow-up / best-deal / prioritize cues classify as `qualify` → `qualificationIntent: true`. |

**Requires:** core restart + Zoho re-index for Lucene content to pick up Structured narratives.

### Rate-limit handling — shipped (was mis-scored as 2/10)

- `ZohoHttpRetry` on CRM and OAuth transports: retry 429 / 408 / 5xx, exponential backoff, higher 429 floor, honor `Retry-After`.
- Tunable via `andromedia.zoho.max-retries` / `retry-*-ms`.
- Still missing: operator metrics / dashboards for retry volume (not the retry itself).

### Deleted-record handling on re-index — partial, shipped

- Successful Zoho index **deletes all `sourceType=ZOHO` Lucene docs** in the same writer session, then writes the fresh set and commits once (`LuceneIndexingService.indexZohoHistory`).
- Deleted Zoho accounts/deals no longer linger **after a successful re-index**.
- Other sources (Slack/Jira/…) in the same service index are preserved.
- Empty / failed ingest does **not** wipe Zoho docs.

This is **not** the same as:

- Zoho Deleted Records API / COQL tombstones
- Webhook-driven real-time removal
- Incremental delete without full ZOHO replace

---

## What we have not done (honest gaps)

### 1. Delete reconciliation — largely shipped; webhooks still open

| Have | Do not have |
|---|---|
| Durable watermark + Zoho Deleted Records API on incremental sync | Webhooks / push notification cleanup |
| Full ZOHO replace on first sync / `fullRefresh` / watermark older than 30 days | Soft-delete visibility if nobody syncs for >30 days without full refresh |

**Production risk:** Zoho only retains ~30 days of deletions — ops should cron weekly `fullRefresh: true`.

### 2. Incremental sync — shipped

| Have | Do not have |
|---|---|
| Durable per-org watermark under `~/.andromedia/zoho-watermarks/` | Persisted OAuth for unattended multi-org `@Scheduled` |
| Search Records with `Modified_Time` + Lucene upsert (no full wipe on incremental) | Notes/Activities related-list fetch (still module list filtered by changed deal ids) |
| Auto full refresh when watermark is stale (>30 days) | Notion / Jira / Linear still largely full re-fetch |

### 3. Duplicate / entity resolution — not built (0/10)

- “Infosys” vs “Infosys Ltd” remain separate accounts.
- No fuzzy match, no canonical company ID, no merge of contacts across spellings.
- Corrupts enrichment and “top account” style questions when CRM hygiene is poor.

### 4. Cross-field semantic normalization — not built (0/10)

- Loss reasons like “Lost - Price” / “pricing issue” / “competitor cheaper” stay raw strings.
- No canonical concept map → “why do we lose on price?” under-recalls.

### 5. Multi-CRM — interface only (2/10)

- **Product posture:** Zoho only. Non-Zoho `CrmConnection.provider` values fail fast with a clear error (no silent no-op).
- CRM-agnostic types + Zoho adapter live; seams remain for later adapters.
- No Salesforce / HubSpot / Dynamics adapters yet.
- No cross-CRM unified field mapping into Lucene beyond Zoho’s dual-write FDE fields.

### 6. Relationship enrichment depth — capped (5/10)

- Deal-centric graph is real (account + contacts + notes + activities).
- Not a full CRM graph (multi-hop accounts, open activities across products, email threads as first-class, etc.).

### 7. Operational polish still thin

- Job store is in-memory (lost on restart).
- No scheduled pipeline monitoring / alerting product surface.
- No Bulk Read path for very large orgs (REST cursors are the supported path).
- Watermark files survive restart; Zoho credentials do not (enqueue-only).

---

## Path to ~7/10 — landed

Already counted into ~7.0:

1. **Evidence fixes** — done.
2. **Full Zoho refresh on re-index** — done (orphan wipe).
3. **429 retry** — done.
4. **Durable `Modified_Time` watermark + incremental upsert** — done.
5. **Zoho Deleted Records cleanup on incremental** — done.
6. **`fullRefresh` API + auto-escalate when watermark >30 days** — done.

Remaining polish (small lifts): Notes related-list fetch for incremental; ops cron for weekly full refresh.

---

## Path to ~9/10 (next quarter)

| Item | Est. lift |
|---|---|
| Duplicate / entity resolution | +0.5 |
| Semantic normalization (loss reasons, stages) | +0.5 |
| Salesforce adapter | +0.5 |
| HubSpot adapter | +0.3 |
| Webhook real-time sync | +0.2 |

These are the “demo vs product customers trust” items. Architecture already has seams (`CrmSchemaAdapter`, shared Lucene FDE fields, intelligence ledger).

---

## Correction log vs earlier 5.5/10 draft

| Earlier claim | Reality in codebase |
|---|---|
| Evidence fixes “pending” (+0.2 when done) | Implemented (remount, Structured narrative, demo evidence filter, follow-up→qualify). |
| Stale deletion 0/10 | Full ZOHO replace + Deleted Records API on incremental → ~7/10. |
| 429 retry 2/10 “documented as missing” | `ZohoHttpRetry` is implemented and documented in `zoho-ingestion.md` → ~6/10. |
| Incremental sync “Modified_Time watermark ❌” | Durable `ZohoSyncWatermarkStore` + Search Records incremental → ~7/10. |

---

## Bottom line

You have a **solid FDE foundation for the happy path** on Zoho: discover custom fields, score quality, enrich deal context, guard answers, and sync incrementally with durable watermarks and delete cleanup.

Remaining honest gaps: entity resolution, semantic normalization, multi-CRM adapters, and persisted credentials for unattended multi-org cron. Those plug into what is already there rather than requiring a new architecture.
