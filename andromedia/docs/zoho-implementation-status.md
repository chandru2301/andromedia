# Zoho Integration: Implementation Status

Last audited: 2026-07-19

## Executive summary

The original checklist is outdated. Andromedia now has a working Zoho V1 pipeline from OAuth and CRM retrieval through enrichment, quality analysis, Lucene indexing, search, and investigation.

The following exact class names do not exist:

- `ZohoSchemaDiscoveryService`
- `ZohoDataQualityAnalyzer`
- `ZohoRelationshipResolver`
- `ZohoChunkBuilder`

However, those capabilities have been implemented under different names or directly inside the ingestion pipeline.

Current overall state:

- Complete for a Lean V1: OAuth, connection test, schema discovery, cursor-based CRM reads, account/contact/note/activity enrichment, quality/demo detection, unified chunks, Lucene indexing, REST APIs, frontend configuration, and grounded CRM investigation.
- Partial: schema-driven ingestion, production-grade persistence/security, CRM-specific retrieval boosting, deletion handling, region coverage, and test coverage at the API/job boundary.
- Missing: Bulk Read, incremental synchronization, webhooks, proactive alerts, durable credentials/jobs, and scheduled pipeline monitoring.

## Original checklist corrected

### Decided to build Zoho integration

Status: Complete

Zoho is implemented as a first-class source type with dedicated ingestion models, indexing fields, search metadata, and frontend integration.

Key files:

- `common/src/main/java/com/andromedia/common/IndexConstants.java`
- `common/src/main/java/com/andromedia/common/ZohoDealContextChunk.java`
- `ingestion/src/main/java/com/andromedia/ingestion/zoho/`
- `core/src/main/java/com/andromedia/core/index/`
- `search/src/main/java/com/andromedia/search/ZohoQualificationService.java`

### Understood the API

Status: Implemented, with known limitations

Implemented:

- Self Client OAuth grant exchange
- Access-token refresh
- Region-specific token and CRM URLs
- Connection test through `/settings/modules`
- Field discovery through `/settings/fields`
- Cursor pagination through `page_token` / `next_page_token`
- One restart of a module fetch when a page token expires
- Deals, Accounts, Contacts, Notes, Tasks, Calls, Events, and Meetings fallback

Key files:

- `ingestion/src/main/java/com/andromedia/ingestion/zoho/ZohoAuthClient.java`
- `ingestion/src/main/java/com/andromedia/ingestion/zoho/ZohoApiClient.java`
- `ingestion/src/main/java/com/andromedia/ingestion/zoho/ZohoRegion.java`

Limitations:

- No Bulk Read API
- Only `.com` and `.in` regions are supported
- Notes and activities are fetched module-wide and filtered locally
- No browser redirect OAuth flow

CRM and OAuth HTTP transports retry on 429 / 408 / 5xx with exponential backoff, a higher 429 floor, and `Retry-After` when present (`ZohoHttpRetry`, configured under `andromedia.zoho.*`).

### Designed the architecture

Status: Complete for V1

The implemented flow is:

```text
Zoho OAuth
  -> schema discovery
  -> fetch Deals / Accounts / Contacts / Notes / Activities
  -> resolve relationships
  -> score quality and detect demo data
  -> build one unified deal-context chunk per deal
  -> index structured fields + narrative + optional embedding
  -> hybrid retrieval / full deal ledger
  -> grounded investigation and pipeline-health reasoning
```

The implementation follows the three-bucket field design:

1. Narrative/searchable content: descriptions, Next Step, contacts, notes, activities.
2. Structured CRM facts: stage, amount, probability, close date, owner, account, and other stored fields.
3. Quality/trust signals: demo, stale, overdue, missing contacts, missing notes, and missing account context.

### Created sample data in Zoho

Status: Complete

The connected sandbox contains sample and real-looking CRM records. Test fixtures also exist under:

- `ingestion/src/test/resources/zoho/fixtures/`

The code now detects sample data at deal, account, and contact levels and keeps demo records indexed but excludes them from primary recommendations.

## Capability mapping

### Schema discovery

Original expected class: `ZohoSchemaDiscoveryService`

Actual implementation:

- `ZohoApiClient.discoverV1Schema()`
- `ZohoApiClient.discoverModuleSchema()`
- `ZohoIngestionService.discoverSchema()`

Status: Partial

What works:

- Discovers field metadata for core modules.
- Best-effort discovery for enrichment modules.
- Reports discovered modules in ingestion statistics.

What remains:

- Discovered schema does not dynamically control which fields are fetched.
- Fetch field lists remain hardcoded.
- Custom Zoho fields are not automatically ingested.
- There is no public schema-inspection API endpoint.

### Data quality analysis

Original expected class: `ZohoDataQualityAnalyzer`

Actual implementation:

- `ZohoRecordQualityScorer`
- `ZohoRecordFilter`
- `ZohoDemoAccountDetector`

Status: Complete for V1

Implemented signals include:

- Missing or present deal name, stage, amount, and description
- Missing account description
- Missing contacts, notes, and activities
- Recently modified or stale record
- Passed close date
- Demo/test/sample text
- Placeholder email and phone patterns
- Account-wide demo propagation
- Configurable classic Zoho sandbox account signatures

Demo records are indexed with `test_demo_pattern` rather than discarded. This preserves evidence while preventing sample deals from becoming primary recommendations.

Known limitations:

- The default stale threshold is 180 days.
- `lookbackDays` affects quality scoring but is not an API fetch window.
- Generic substring matching for `test`, `demo`, or `sample` can produce false positives.
- Sandbox signatures should be disabled for real production organizations.

### Relationship resolution

Original expected class: `ZohoRelationshipResolver`

Actual implementation:

- Inline resolution in `ZohoIngestionService`
- `ZohoApiClient.indexNotesByDeal()`
- `ZohoApiClient.indexActivitiesByDeal()`

Status: Complete for the V1 deal-centric model

Implemented:

- Deal to Account by account ID, then normalized account name
- Deal to explicit Contact lookup
- Account to all linked Contacts
- Synthetic contact fallback when a deal references a contact missing from the contact fetch
- Notes to Deal by parent ID
- Tasks, Calls, Events, and Meetings to Deal by `What_Id`
- Account-level demo propagation across sibling deals

Known limitations:

- No general-purpose CRM relationship graph
- No Products, Attachments, Quotes, Emails, or Related Lists
- Matching by normalized account name may over-associate duplicate account names
- No account-level aggregate chunk

### Unified chunk builder

Original expected class: `ZohoChunkBuilder`

Actual implementation:

- `ZohoUnifiedChunkBuilder`
- `ZohoDealContextChunk`

Status: Complete

Each indexed deal chunk includes:

- Deal facts and timestamps
- Account context
- Contacts
- Note snippets
- Activity narrative
- CRM-authored `Next_Step`
- Quality score and signals
- Provenance references
- Stable Zoho chunk ID and source type

Known limitations:

- Provenance references are created but not stored in Lucene.
- Activities are searchable in narrative but do not have a dedicated multi-value stored field.
- Narrative text is capped at 5,000 characters.
- Notes and activities are capped at five per deal.

### API endpoints

Original claim: Not built

Status: Built

Implemented routes:

- `POST /api/v1/integrations/zoho/test`
  - Tests credentials, region, and module access.
- `POST /api/v1/integrations/zoho/exchange-grant`
  - Exchanges a Self Client grant token for OAuth tokens.
- `POST /api/v1/index/zoho`
  - Starts asynchronous Zoho ingestion and indexing.
- `GET /api/v1/index/zoho/{jobId}`
  - Returns job status, statistics, and errors.
- `POST /api/v1/investigate`
  - Runs Zoho-aware grounded investigation.

Main implementation:

- `core/src/main/java/com/andromedia/core/ApiRouterConfig.java`
- `core/src/main/java/com/andromedia/core/index/ZohoConnectionTestService.java`
- `core/src/main/java/com/andromedia/core/index/ZohoGrantExchangeService.java`
- `core/src/main/java/com/andromedia/core/index/ZohoIngestionJobService.java`
- `core/src/main/java/com/andromedia/core/index/ZohoIngestionJobStore.java`

## Indexing and retrieval

Status: Built

Implemented:

- Lucene upsert by stable chunk ID
- Structured Zoho fields stored separately from narrative
- BM25 text retrieval
- Optional vector embeddings
- Hybrid search when embeddings exist
- Zoho metadata mapping into search hits
- CRM-specific evidence formatting
- Full active-deal ledger for corpus-wide reasoning

Key files:

- `indexing/src/main/java/com/andromedia/indexing/LuceneIndexingService.java`
- `search/src/main/java/com/andromedia/search/IndexDocumentMapper.java`
- `search/src/main/java/com/andromedia/search/ExploreService.java`
- `search/src/main/java/com/andromedia/search/ZohoHitMetadata.java`

Important operational note:

- If ingestion is started with `embed=false`, the index still exists and BM25 works, but semantic retrieval is unavailable.
- Re-ingestion is required after adding fields or changing demo-detection behavior.
- Removed Zoho records are not pruned from the index; indexing is currently upsert-only.

## CRM intelligence

Status: Built

Implemented capabilities:

- Demo and real deal separation
- Demo records visible as evidence but blocked from primary recommendations
- Full-ledger reasoning for ranking, qualification, risk, follow-up, and data-gap questions
- Deterministic numeric-superlative cross-checks
- Deal-membership guard against hallucinated records
- `Next_Step` grounding and anti-hallucination checks
- Pipeline-health facts calculated in Java
- Demo-heavy and thin-pipeline observations
- High-probability threshold at 30%
- Evidence table with structured CRM facts and trust flags

Key files:

- `search/src/main/java/com/andromedia/search/ZohoQualificationService.java`
- `search/src/main/java/com/andromedia/search/ZohoQualificationProperties.java`
- `core/src/main/java/com/andromedia/core/investigate/InvestigationService.java`

Current limitation:

- Andromedia can identify overdue/at-risk deals when queried, but it does not proactively monitor or send alerts.
- A 45-day inactive deal is not currently marked `stale_record`; the configured threshold is 180 days.
- “Last touched” currently relies primarily on deal `Modified_Time`, not the latest meaningful timestamp across every activity source.

## Frontend

Status: Built for V1

Implemented:

- Zoho integration configuration modal
- Self Client grant exchange
- Connection test
- Ingestion start and job polling
- Region, lookback, quality, and embedding controls
- Investigation page
- Structured evidence table and trust state
- LLM provider/model/API-key configuration

Key files:

- `andro/src/components/ConfigureModal.tsx`
- `andro/src/lib/andromediaApi.ts`
- `andro/src/pages/investigation-page.tsx`
- `andro/src/components/SettingsPanel.tsx`

## Tests

Status: Good unit and fixture coverage; incomplete integration coverage

Present:

- OAuth token parsing
- Cursor pagination and expired-token restart
- Unified chunk construction
- Fixture-based relationship enrichment
- Demo-account detection
- `Next_Step` propagation
- Search evidence formatting
- Deal qualification and ranking
- Demo exclusion and hallucination guards
- Thin/demo-heavy pipeline intelligence

Test files:

- `ingestion/src/test/java/com/andromedia/ingestion/zoho/ZohoAuthClientTest.java`
- `ingestion/src/test/java/com/andromedia/ingestion/zoho/ZohoApiClientPaginationTest.java`
- `ingestion/src/test/java/com/andromedia/ingestion/zoho/ZohoUnifiedChunkBuilderTest.java`
- `ingestion/src/test/java/com/andromedia/ingestion/zoho/ZohoV1PipelineFixtureTest.java`
- `search/src/test/java/com/andromedia/search/ZohoQualificationServiceTest.java`
- `core/src/test/java/com/andromedia/core/investigate/InvestigationServiceTest.java`

Missing:

- API router tests for Zoho routes
- `ZohoIngestionJobService` tests
- Live Zoho organization integration tests
- Dedicated Lucene Zoho mapping tests
- Frontend Zoho end-to-end tests

## What is still missing

### High priority

1. Incremental synchronization
   - No `Modified_Time` watermark or Zoho cursor store.
   - Every ingestion performs a full module fetch.

2. Bulk Read for large initial imports
   - REST cursor pagination works beyond 2,000 records but is inefficient for very large organizations.
   - Zoho Bulk Read should handle the first full import.

3. Retry and rate-limit handling
   - CRM/OAuth HTTP retries on 429 with exponential backoff + `Retry-After` are implemented.
   - Still missing: dedicated rate-limit / retry metrics dashboards.

4. Durable jobs and credential storage
   - Job state and credentials are kept in process memory.
   - Restarting the backend loses jobs.
   - Credentials need encrypted durable storage or an external secret manager.

5. Stale record deletion
   - Records removed from Zoho can remain in Lucene.
   - A completed sync should reconcile and delete missing Zoho document IDs.

### Product capabilities

6. Proactive deal-risk monitoring and alerts
   - No scheduled stale/overdue evaluation.
   - No Slack/email/webhook alert delivery.

7. Zoho webhooks
   - No near-real-time update receiver.

8. Stage-aware inactivity policy
   - No deterministic rule such as:
     `active AND close date passed AND no meaningful activity for 30 days`.

9. Additional CRM objects
   - No Products, Quotes, Attachments, Emails, Sales Orders, or custom related lists.

### Platform maturity

10. Multi-region support
    - Add EU, AU, JP, CA, and other Zoho data-center suffixes.

11. Browser OAuth
    - Current flow is Self Client/manual grant token.

12. Security
    - API routes do not have application-level authentication/authorization.
    - Grant exchange returns tokens directly.

13. Observability
    - No Zoho-specific metrics for module latency, record counts, retries, rate limits, cursor restarts, or failures.

14. Dynamic schema usage
    - Use discovered schema to ingest configured custom fields and route them into structured or narrative storage.

15. CLI support
    - Zoho is available through the core API/frontend, not through a dedicated CLI command.

## Recommended next sequence

1. Stabilize current V1
   - ~~Add 429 retry/backoff.~~ Done (`ZohoHttpRetry` on CRM + OAuth).
   - Add job/API/Lucene integration tests.
   - Disable sandbox account signatures for production deployments.

2. Add synchronization correctness
   - Durable `Modified_Time` watermark.
   - Deletion reconciliation.
   - Idempotent incremental updates.

3. Scale initial imports
   - Bulk Read API for initial full-org ingestion.
   - Keep REST cursor pagination for incremental reads.

4. Add proactive intelligence
   - Compute last meaningful activity across CRM events.
   - Add stage-aware stale/overdue rules.
   - Schedule evaluations and deliver alerts.

5. Harden production operations
   - Credential vault, durable job store, API authentication, broader regions, and metrics.

## Final assessment

The Zoho integration is not “design only.” It is a functional Lean V1 with the core FDE path implemented end to end.

The most accurate current description is:

> Zoho ingestion, enrichment, quality analysis, chunking, indexing, APIs, frontend configuration, and grounded investigation are built. The remaining work is primarily production hardening, scalable synchronization, proactive alerts, and broader CRM coverage.
