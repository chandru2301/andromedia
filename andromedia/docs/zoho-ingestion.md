# Zoho CRM ingestion (Lean V1)

Indexes deal-centric enriched CRM context (`Deals` + linked `Accounts` + `Contacts`, plus deal-linked `Notes` and Activities) into the Lucene service index as `sourceType=ZOHO`.

## CRM support

**Zoho only** in this version. Salesforce, HubSpot, and other CRMs are not configurable. Constructing a connection with any other provider fails immediately with:

`Only Zoho CRM is supported in this version. Salesforce and HubSpot support is coming.`

## M0: Get a real org to develop against

Before indexing production data, use one of:

1. **Design partner sandbox** (preferred) — export or API access with realistic Deals/Accounts/Contacts (and ideally Notes/Tasks/Calls).
2. **Zoho CRM trial org** — seed at least ~200 deals with mixed quality (empty descriptions, test records, stale rows).

Frozen fixture dataset for unit tests lives in:

`ingestion/src/test/resources/zoho/fixtures/`

## Auth (v1 — Self Client, manual one-time)

Zoho OAuth uses short-lived grant tokens (~10 minutes, single-use). V1 supports manual Self Client setup only.

1. Create a **Self Client** in [Zoho API Console](https://api-console.zoho.com/).
2. Generate a grant token with CRM scopes (`ZohoCRM.modules.ALL`, or read scopes that include Deals, Accounts, Contacts, **Notes**, **Tasks**, **Calls**, and **Events**/**Meetings**).
3. Exchange the grant token via `POST /api/v1/integrations/zoho/exchange-grant` with a `service` owner id.
4. The server vaults the refresh token (AES-GCM under `~/.andromedia/connections/`) and returns only a `connectionId` — **never return or store refresh tokens in the browser**.
5. Test and index with `{ service, connectionId }` only.

Set a durable encryption key (base64 32-byte AES key):

```bash
export ANDROMEDIA_CREDENTIALS_ENCRYPTION_KEY="$(openssl rand -base64 32)"
```

For local/dev without a key, `andromedia.credentials.allow-ephemeral-key=true` (default) generates an in-memory key (vault lost on restart).

Missing Notes/Activities scopes do not fail ingest — those modules are skipped with a warning and deals still index.

**Known v1→v2 gap:** self-serve browser OAuth redirect is not implemented yet.

### Region (v1)

Bind to a single region per connection:

| Region | Accounts host | API host |
|--------|---------------|----------|
| `com` (default) | `accounts.zoho.com` | `www.zohoapis.com` |
| `in` | `accounts.zoho.in` | `www.zohoapis.in` |

## Endpoints

- `POST /api/v1/integrations/zoho/test`
- `POST /api/v1/integrations/zoho/exchange-grant` (one-time Self Client setup; vaults refresh token)
- `POST /api/v1/index/zoho`
- `GET /api/v1/index/zoho/{jobId}`

If a stored refresh token is revoked, APIs return `401` with `"code":"ZOHO_REAUTH_REQUIRED"` — exchange a new grant token.

### Exchange grant token (one-time)

```bash
curl -X POST http://localhost:8083/api/v1/integrations/zoho/exchange-grant \
  -H 'Content-Type: application/json' \
  -d '{
    "service": "local",
    "clientId": "1000.xxx",
    "clientSecret": "xxx",
    "grantToken": "1000.xxx",
    "region": "com"
  }'
```

Response (no refresh/access tokens):

```json
{ "ok": true, "connectionId": "uuid", "connectionKey": "abcdef0123456789", "region": "com" }
```

### Test connection

```bash
curl -X POST http://localhost:8083/api/v1/integrations/zoho/test \
  -H 'Content-Type: application/json' \
  -d '{
    "service": "local",
    "connectionId": "uuid-from-exchange"
  }'
```

### Index

```bash
curl -X POST http://localhost:8083/api/v1/index/zoho \
  -H 'Content-Type: application/json' \
  -d '{
    "service": "local",
    "connectionId": "uuid-from-exchange",
    "lookbackDays": 90,
    "minQualityScore": 0.35,
    "embed": false,
    "fullRefresh": false
  }'
```

Set `"fullRefresh": true` to force a full Get Records sync and wipe+rewrite all `sourceType=ZOHO` docs (weekly ops reconciliation). Omit or `false` to use durable incremental sync when a watermark exists.

## Incremental sync (durable watermark)

After the first successful index, Andromedia stores a per-org watermark at:

`~/.andromedia/zoho-watermarks/{service_region}.json`

keyed by module (`Deals`, `Accounts`, `Contacts`) with `lastSyncedAt`, `lastSyncStatus`, and `recordsFetched`. Writes are atomic (temp file + rename).

| Mode | When | Fetch | Lucene |
|------|------|-------|--------|
| **Full** | No watermark, `fullRefresh: true`, or watermark older than **30 days** | Get Records (all pages) | Delete all `sourceType=ZOHO`, then upsert |
| **Incremental** | Watermark present and fresh | Search Records with `Modified_Time:greater_than:{ISO-8601 datetime}` (e.g. `2026-04-21T00:00:00+00:00`) | Upsert changed deals; remove chunks for Zoho Deleted Records since watermark |

Watermark is advanced to **sync start** (not end) only after a successful Lucene commit, so mid-sync modifications are not missed.

### Deleted records

Incremental runs call `GET /crm/v3/Deals/deleted?type=all` and delete matching Lucene deal-context chunks. Zoho only retains ~30 days of deletions — if the watermark is older than that, the job auto-escalates to full refresh.

Job stats include `syncType`, `dealsUpdated`, `dealsDeleted`, and `watermark`.

### Weekly reconciliation

Credentials are not persisted server-side, so there is no multi-org `@Scheduled` cron. Ops should call `POST /api/v1/index/zoho` with `"fullRefresh": true` on a weekly schedule (e.g. cron) as a safety net.

## V1 pipeline

1. **Schema discovery** for `Deals`, `Accounts`, `Contacts` (plus best-effort `Notes` / `Tasks` / `Calls` / `Events`). Discovered field metadata drives the Get Records / Search Records `fields=` projection (batched at Zoho’s 50-field limit).
2. **Three-layer field classification** (data type → first-page sample fill/length → optional cheap LLM A/B/C). Near-empty fields become `SKIP`; long prose in lame-named customs (e.g. `Field1__c`) becomes `NARRATIVE`. Classification samples only the first ingestion page (≤200 rows) — no extra sampling API calls.
3. **Column-based table inference** for custom/unknown modules (weighted signatures + value patterns). Standard Zoho modules (`Deals` / `Accounts` / `Contacts`) are never re-inferred.
4. Paginated fetch (200/page) using Zoho **`page_token` / `next_page_token` cursors** (not numeric `page=`). Full sync uses Get Records; incremental uses Search Records with `Modified_Time` criteria. Offset pagination is capped by Zoho at 2000 records (`DISCRETE_PAGINATION_LIMIT_EXCEEDED`); cursor pagination does not have that cap. If a mid-fetch `page_token` expires, the client restarts that module once from the first page. Very large initial loads may still want Zoho Bulk Read later; REST cursors are the supported path today.
5. CRM and OAuth HTTP calls retry on **429** (and 408/5xx) with exponential backoff, a higher 429 floor, and `Retry-After` when Zoho sends it. Tunable via `andromedia.zoho.max-retries` / `retry-*-ms` (defaults match chat: 5 retries, 2s initial, 5s 429 floor, 60s cap).
6. Quality score + signals (demo/stale/sparse); **demo records are indexed with `test_demo_pattern`**, not hard-dropped
7. Deal-centric enrichment (account + contacts + deal-linked notes + activities), then **universal narrative extras** for discovered custom NARRATIVE / non-canonical STRUCTURED fields (capped at 5000 chars). Canonical Amount / Probability / Closing_Date stay on typed Lucene columns only.
8. Three-bucket field routing into Lucene:
   - **Bucket 1 (narrative / BM25 + embed):** Deal_Name, Stage, Account_Name, Descriptions, **Next_Step**, Contact names, Notes/Activities text, plus discovered custom narrative extras
   - **Bucket 2 (structured stored metadata):** Amount, Expected_Revenue, Probability, Closing_Date, Modified_Time, Owner, Lead_Source, **Next_Step** (also stored for reliable quoting), Contact email/phone, Account description
   - **Bucket 3 (quality signals only):** `@noemail.invalid`, `555-555-5555`, `(Sample)`/test/demo names, account-wide demo propagation, optional sandbox account signatures (King/Chapman/… when `andromedia.zoho.sandbox-demo-signatures-enabled=true`), empty account description, close-date-passed, stale Modified_Time

Caps: up to **5 notes** and **5 activities** per deal (newest first), then narrative truncated at 5000 chars.

Amount and Closing_Date are **not** duplicated into narrative prose — they ride as labeled Structured metadata so answer generation can distinguish CRM fields from free-text notes.

**Demo detection is account-level:** if any contact on an account has a placeholder email/phone, or the account matches test/sample text (or an enabled sandbox signature), **all deals under that account** receive `test_demo_pattern`.

### Data-gap report (job metadata)

Completed Zoho ingest jobs include a `dataGapReport` array in job stats / service index metadata. Each entry is:

`{ module, apiName, type, completenessScore, recommendation }`

`completenessScore` is 0–100 from first-page fill rate. Low-completeness NARRATIVE / OUTCOME fields (and consistently empty SKIP fields) get a recommendation string (e.g. “Only 23% of deals fill Loss Reason Detail; make it required”). The andro UI panel for this report is deferred.

### Re-index / refresh semantics

- **Full refresh** (first sync, `fullRefresh: true`, or stale watermark): replaces all `sourceType=ZOHO` documents, then writes the fresh set. Slack/Jira/Linear/Notion/Git docs in the same index are preserved.
- **Incremental**: upserts only changed deal chunks by `chunkId` and deletes chunks for CRM-deleted deals. Unchanged ZOHO docs stay as-is.

After changing demo detection, adding fields such as `Next_Step`, or picking up newly discovered custom fields, run a **full refresh** (`fullRefresh: true`) so every deal chunk is rebuilt. Canonical FDE Lucene field names are unchanged.

## Retrieval validation

See [zoho-retrieval-validation.md](./zoho-retrieval-validation.md) for sample queries to run after first real-org index.
