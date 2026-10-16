# Andromedia: Zoho Interview Project Guide

This guide is a practical way to explain Andromedia in a Zoho interview. It is based on the
current implementation, so it separates shipped capabilities from future improvements.

## 1. Thirty-second introduction

> Andromedia is a local-first knowledge and intelligence layer for engineering and revenue teams.
> It connects source code, Git history, Slack, Notion, Jira, Linear, and Zoho CRM, converts them
> into structured searchable chunks, and stores them in a Lucene index. Users can then ask
> grounded questions through a web UI, CLI, HTTP API, or MCP connector. The system combines
> deterministic business rules with an LLM: Java calculates reliable CRM facts such as pipeline
> totals, qualification signals, stale records, and conflicts, while the model explains those
> facts using retrieved evidence and provenance.

## 2. The problem

Business and engineering knowledge is distributed across many tools:

- The code shows what the system does.
- Git shows how and when it changed.
- Slack shows decisions and operational context.
- Notion contains architecture and design documents.
- Jira and Linear contain requirements, bugs, and comments.
- Zoho CRM contains accounts, deals, contacts, activities, and commercial status.

Without a unified layer, a developer or sales engineer must search each system manually. An AI
coding assistant can read the repository, but it usually cannot answer questions such as:

- Why did the team choose this implementation?
- Which incident caused this configuration change?
- Which CRM deal needs follow-up?
- What did the team say about a customer?
- Does the CRM record agree with the proposal in Slack or Notion?

Andromedia solves this by indexing the sources into one service-scoped knowledge layer and returning
answers that can be inspected against their original evidence.

## 3. Main users and use cases

### Engineering use cases

- Search code with BM25, semantic, or hybrid retrieval.
- Ask why a design or configuration exists.
- Find Git history related to a code path.
- Find symbol usages.
- Calculate the reverse blast radius of a symbol.
- Generate a grounded implementation plan.
- Ask Slack, Notion, Jira, and code questions from an AI coding agent.

### Revenue and CRM use cases

- Find active deals that need attention.
- Rank or qualify deals using probability, stage, freshness, and quality signals.
- Identify stale, overdue, or incomplete CRM records.
- Summarize an account using deal, contact, note, and activity context.
- Compare a structured Zoho amount with a proposal or discussion in Slack/Notion.
- Ask what was said about a customer without leaving the CRM context.

### AI-agent use cases

The MCP adapter exposes the same search and investigation capabilities to Cursor, Claude Code,
and other MCP-compatible clients:

- `andro_search`
- `andro_investigate`
- `andro_list_sources`
- `andro_get_file_history`

## 4. High-level architecture

```text
Git / local files / Slack / Notion / Jira / Linear / Zoho CRM
                              |
                              v
                 Source-specific ingestion clients
                              |
                              v
               Filtering, chunking, enrichment, metadata
                              |
                              v
                 Lucene text index + optional vectors
                              |
                              v
             BM25 + vector search + ranking + entity pinning
                              |
                              v
        Deterministic CRM intelligence + grounded LLM synthesis
                              |
             +----------------+----------------+
             |                |                |
             v                v                v
          Web UI             CLI              HTTP / MCP
```

The important design principle is:

> Retrieval finds evidence; deterministic services decide what can be trusted; the LLM explains
> the result without inventing facts.

## 5. Technology stack

### Backend

- Java 21
- Spring Boot 3.4.x
- Spring WebFlux for the HTTP API and streaming responses
- Maven multi-module build
- SLF4J and Logback for logging
- Spring configuration properties for typed configuration
- Flyway and PostgreSQL for the optional credential vault

### Search and indexing

- Apache Lucene 9.12.x
- BM25 lexical retrieval
- Optional vector embeddings through an OpenAI-compatible embedding API
- KNN/vector retrieval when embeddings are available
- Reciprocal-rank fusion and heuristic ranking for hybrid search
- JSON sidecar files for the symbol graph and durable sync watermarks

### AI providers

The chat client uses OpenAI-compatible `/chat/completions` APIs:

- Groq is the default primary provider.
- Groq's configured default model is `qwen/qwen3.8-27b`.
- DeepSeek is the configured fallback using `deepseek-v4-flash`.
- OpenRouter and local Ollama are also supported.
- DeepSeek fallback is attempted only after transient Groq failures such as rate limits,
  timeouts, network failures, or 5xx responses.
- A streaming response falls back only if Groq has emitted no tokens. This avoids returning a
  partial Groq answer followed by a duplicated DeepSeek answer.
- Embeddings are configured separately and currently use OpenRouter by default.

### Frontends and access paths

- React/Vite `andro` frontend for the local product surface.
- Next.js `landing` application for the deployed product surface.
- Picocli-based `andro` CLI and interactive shell.
- REST API under `/api/v1`.
- Node.js MCP adapter for AI-agent access.

## 6. Maven module responsibilities

| Module | Responsibility |
|---|---|
| `common` | Shared chunk models, source constants, index path handling, cursors, and graph data |
| `ingestion` | Source clients, filters, chunkers, CRM adapters, and source-specific enrichment |
| `indexing` | Lucene writers, document mapping, embedding storage, and index replacement/upsert logic |
| `search` | BM25, vector, hybrid search, ranking, explore, canonical entity matching, and Zoho qualification |
| `llm` | Chat providers, embeddings, retries, prompts, streaming, and provider fallback |
| `core` | Spring Boot application, WebFlux routes, jobs, credentials, trial access, and investigation |
| `cli` | Picocli commands, interactive shell, usages, impact analysis, and grounded plans |

## 7. Source ingestion model

Every connector follows the same broad pipeline:

```text
Authenticate
  -> Fetch records/messages/pages
  -> Filter unsupported or irrelevant data
  -> Preserve source metadata
  -> Chunk into searchable units
  -> Enrich with structured fields
  -> Optionally generate embeddings
  -> Write or update Lucene documents
```

The system does not flatten every source into anonymous text:

- A code chunk retains file, symbol, import, annotation, and reference metadata.
- A Git chunk retains commit, author, timestamp, and changed-file context.
- A Slack thread is indexed as one chunk containing the parent and replies.
- A Notion page is split by meaningful sections.
- Jira and Linear chunks retain issue identifiers and comments.
- A Zoho deal becomes a deal-centric context containing CRM facts and related narrative.

The `service` value acts as a logical workspace boundary. Code, Slack, Notion, Jira, Linear, and
Zoho records using the same service value can be retrieved together.

## 8. Zoho CRM integration: the interview explanation

### 8.1 Why Zoho is treated as a first-class source

Zoho is not only imported as raw JSON. It has:

- A dedicated source type: `ZOHO`.
- CRM-specific ingestion models.
- Stored structured Lucene fields.
- Deal quality signals.
- Account/contact/note/activity enrichment.
- A full deal ledger for deterministic pipeline questions.
- Investigation rules that prevent demo data and unsupported claims from becoming recommendations.

The current product posture is Zoho-first. The CRM abstraction seams exist, but Salesforce, HubSpot,
and other CRM adapters are not implemented in this version.

### 8.2 Zoho authentication flow

The current V1 flow uses a Zoho Self Client and a one-time grant token:

```text
Self Client grant token
  -> POST /api/v1/integrations/zoho/exchange-grant
  -> exchange grant for access/refresh tokens
  -> encrypt and vault refresh token
  -> return connectionId, never refresh token
  -> use connectionId for test and indexing
```

Important security decisions:

- Zoho grant tokens are short-lived and single-use.
- The browser does not receive the refresh token.
- Refresh tokens are encrypted with AES-GCM.
- The API returns a connection identifier instead of OAuth secrets.
- A durable encryption key can be supplied through environment configuration.
- In local development, an ephemeral key can be enabled, with the documented trade-off that the
  vault cannot be decrypted after restart.

### 8.3 Schema discovery and field classification

The ingestion pipeline discovers module and field metadata before fetching records. Fields are
classified using a layered approach:

1. Data type and field metadata.
2. Fill-rate and sample values from the first page.
3. Optional low-cost LLM classification for ambiguous fields.

Fields are routed into three conceptual buckets:

1. **Narrative/searchable fields**
   - Deal name
   - Stage
   - Account name
   - Description
   - Next step
   - Contact text
   - Notes and activities
   - Useful custom narrative fields
2. **Structured fields**
   - Amount
   - Expected revenue
   - Probability
   - Closing date
   - Modified time
   - Owner
   - Lead source
   - Account and contact details
3. **Quality/trust signals**
   - Missing contacts, notes, or activities
   - Missing account context
   - Stale or overdue records
   - Demo/test patterns
   - Placeholder email or phone values

The distinction is important because an LLM should not treat an unverified note as equivalent to a
typed CRM Amount field.

### 8.4 Relationship enrichment

For each deal, the pipeline resolves:

- Deal to Account.
- Deal to explicit Contacts.
- Account to linked Contacts.
- Deal-linked Notes.
- Activities such as Tasks, Calls, Events, or Meetings when scopes and data are available.

The result is one unified deal-context chunk per deal. A typical context contains:

```text
Deal: Enterprise License
Account: Example Realty
Stage: Negotiation/Review
Amount: 85000
Probability: 90%
Close date: 2026-08-31
Owner: ...
Contacts: ...
Notes: ...
Activities: ...
Quality signals: recently_modified, has_contacts, has_activities
```

### 8.5 Cursor pagination and retries

Zoho REST retrieval uses cursor pagination:

- Records are fetched in pages of up to 200.
- `page_token` and `next_page_token` are used instead of numeric offset pagination.
- If a cursor expires during a module fetch, the client can restart that module once.
- CRM and OAuth requests retry 429, 408, and 5xx responses with exponential backoff.
- `Retry-After` is honored when Zoho supplies it.

This avoids Zoho's offset pagination limitation for larger datasets.

### 8.6 Incremental synchronization

After the first sync, Andromedia stores a durable per-service/per-region watermark:

```text
Watermark exists and is fresh
  -> query records modified after the watermark
  -> upsert changed Zoho deal chunks
  -> remove recently deleted records
  -> commit the new watermark after a successful Lucene commit
```

Full refresh is used when:

- No watermark exists.
- The caller explicitly requests `fullRefresh`.
- The watermark is older than the configured safety period.

The deleted-record path uses Zoho's deleted-record endpoint where available. A weekly full refresh
is still a useful safety net because Zoho's deletion history is time-limited.

### 8.7 Data quality and demo detection

The quality scorer produces signals rather than silently destroying records. Examples include:

- Missing deal name, stage, amount, description, account context, contacts, notes, or activities.
- Stale Modified Time.
- Close date already passed.
- Placeholder email or phone values.
- `(Sample)`, test, or demo naming patterns.
- Account-level demo propagation when linked contacts contain placeholder values.

Demo records remain indexed so the evidence is auditable, but they are excluded from primary
recommendations and pipeline rankings. This is safer than silently deleting data and then making a
false completeness claim.

## 9. Search and retrieval

### 9.1 Lexical search

BM25 is strong for:

- Exact deal and account names.
- Class names and method names.
- Ticket identifiers.
- File paths.
- CRM field values and keywords.

### 9.2 Semantic search

When the index contains embeddings, the query is embedded and compared with stored vectors. This
helps with paraphrases, such as finding a Slack decision about “authentication expiry” when the
query says “token timeout policy”.

### 9.3 Hybrid search

Hybrid search runs lexical and vector retrieval, then combines the candidates before returning the
final results. The system intentionally keeps a larger candidate pool than the requested result
count so that a strong lexical match is not lost just because its semantic score is weak.

#### Reciprocal Rank Fusion (RRF)

BM25 and vector search produce scores on different scales, so Andromedia does not add their raw
scores. It uses Reciprocal Rank Fusion, which combines the rank of the same chunk in each channel:

```text
RRF(chunk) =
  bm25Weight    / (60 + bm25Rank)
  + semanticWeight / (60 + semanticRank)
```

The constant `60` reduces the effect of small rank differences. A chunk found near the top by both
channels receives contributions from both; a chunk found by only one channel still remains
eligible. Duplicate chunks are merged by `chunkId`.

The channel weights depend on the query shape:

- Identifier queries favor exact lexical retrieval: BM25 `1.0`, semantic `0.3`.
- Natural-language queries favor meaning: BM25 `0.6`, semantic `1.0`.
- Mixed queries balance both channels: BM25 `0.85`, semantic `0.85`.

If embeddings are unavailable, Andromedia continues with BM25-only retrieval. If embedding
coverage is only partial across sources, natural-language queries use the balanced mixed weights
so sources without vectors are not drowned out by one vector-enabled source.

#### Heuristic ranking

RRF creates a balanced candidate list; heuristic ranking then applies deterministic, explainable
business and source signals. The factors multiply the current score and the results are sorted
again:

- Boost main production code and service/listener/consumer classes.
- Boost exact file, class, or method matches for identifier queries.
- Boost Git for history questions.
- Boost decision-oriented Slack content by `2.5` and Notion architecture content by `2.0`.
- Boost Jira, Linear, and Zoho results for ticket or CRM-oriented questions.
- Penalize tests (`0.75`), generated/build output (`0.5`), and configuration files (`0.85`).
- Apply reaction-count boosts to decision-related Slack messages, capped to prevent domination.

Several safeguards run around the ranking step:

1. Semantic results below the configured minimum score (`0.05`) are removed.
2. A relative-score filter removes hits below `8%` of the top fused score, avoiding irrelevant
   tail padding.
3. Source coverage repair can reinsert a competitive BM25 result for a source/entity pair missing
   from the semantic channel.
4. When BM25 has a strong lexical lead, lexical anchors can displace semantic-only CRM padding.

This ordering matters: RRF provides channel diversity, while heuristics encode retrieval intent and
trust signals. The heuristics do not replace semantic search, and semantic search does not override
an exact identifier or a strong source-specific match.

If embeddings are unavailable, the system continues with text retrieval rather than making the
whole search feature unusable.

### 9.4 Entity pinning and cross-source matching

Named-entity questions need stronger handling than a generic top-k search. Andromedia uses a
canonical entity key to relate variants such as:

```text
Bright Horizon Realty
Bright Horizon - Enterprise License
Bright Horizon deal
```

Account-level matching is primary because people commonly mention the customer account in Slack.
Deal-title-specific matching is used for disambiguation when the account has multiple concurrent
active deals. An account-only near-match in an ambiguous account is retained with an
`possible_account_mention_unconfirmed_deal` signal rather than being silently discarded.

This lets the answer say “Slack reports an account-level risk signal” instead of incorrectly
claiming that Slack definitively referred to one particular deal.

### 9.5 Conflict detection

For named-entity evidence, the system can compare amounts from structured CRM fields with amounts
found in other sources. Date-like values are excluded from money extraction, so a year such as
`2026` is not fabricated into `$2026`. Real currency-shaped values such as `$450,000` remain
eligible for conflict reporting.

The goal is to expose disagreements:

```text
Zoho CRM amount: 310000
Notion proposal: $450,000
Conclusion: cite both and identify the source of each value
```

## 10. Investigation and grounded answers

`POST /api/v1/investigate` is more than search followed by an unconstrained prompt.

### 10.1 Query classification

The classifier identifies shapes such as:

- `lookup`
- `reason`
- `rank`
- `qualify`
- `enumerate`
- `aggregate`
- `corpus_staleness`
- `named_entity_staleness`
- `general`

The classification determines whether the system should use the full CRM ledger, pin a named
entity, calculate a deterministic result, or synthesize across evidence.

### 10.2 Structured pipeline path

Questions such as “Which deal should I follow up on this week?” use the full active ledger rather
than trusting a small semantic top-k list. Java calculates facts such as:

- Probability and amount rankings.
- Stage and freshness signals.
- Stale and overdue records.
- Eligible versus demo-only counts.
- Pipeline totals.
- Missing-data indicators.

The LLM receives these structured facts and explains them. It is not responsible for sorting the
dataset or inventing a record.

### 10.3 Named-entity path

For a named account or deal, the service:

1. Resolves the CRM entity.
2. Injects the authoritative Zoho ledger row.
3. Attaches related Slack, Notion, Jira, or other evidence.
4. Preserves source and score metadata.
5. Computes conflict markers where applicable.
6. Sends the evidence to a constrained synthesis prompt.

If the question is a pure CRM hygiene question and no non-Zoho evidence exists, a deterministic
staleness answer can be used. If Slack or another source provides relevant evidence, the answer
must reconcile both rather than hiding the non-Zoho signal.

### 10.4 Prompt guardrails

The prompts instruct the model to:

- Use only the evidence block and supplied context.
- Never invent a deal, contact, note, or activity.
- Treat structured CRM fields as authoritative for their fields.
- Label free-text notes as unverified.
- Cite the source when values conflict.
- Avoid selecting demo/sample records.
- Avoid borrowing contacts from unrelated deals.
- Refuse or state insufficiency when evidence is not enough.
- Treat ambiguous account-only evidence cautiously.

### 10.5 Streaming and progress

The investigation endpoint can stream progress events such as:

```text
classify
search_hybrid
search_zoho
search_slack
conflicts
entity_pin
demos
compose
result
done
```

This improves user trust because the UI can show whether the result came from classification,
retrieval, a CRM ledger, conflict analysis, or synthesis.

## 11. HTTP API surface

Representative endpoints include:

| Area | Endpoint |
|---|---|
| Health | `GET /api/v1/health` |
| Code index | `POST /api/v1/index` |
| Git history index | `POST /api/v1/index/history` |
| Slack connection/index | `POST /api/v1/integrations/slack/test`, `POST /api/v1/index/slack` |
| Notion connection/index | `POST /api/v1/integrations/notion/test`, `POST /api/v1/index/notion` |
| Jira connection/index | `POST /api/v1/integrations/jira/test`, `POST /api/v1/index/jira` |
| Linear connection/index | `POST /api/v1/integrations/linear/test`, `POST /api/v1/index/linear` |
| Zoho test | `POST /api/v1/integrations/zoho/test` |
| Zoho OAuth exchange | `POST /api/v1/integrations/zoho/exchange-grant` |
| Zoho index | `POST /api/v1/index/zoho` |
| Job status | `GET /api/v1/index/{jobId}` |
| Service overview | `GET /api/v1/index/services/{service}/overview` |
| Search | `POST /api/v1/search` |
| Investigation | `POST /api/v1/investigate` |
| Credentials | `GET/PUT/DELETE /api/v1/credentials` |
| Chat provider settings | `GET/PUT /api/v1/settings/chat-provider` |

Example investigation request:

```bash
curl -X POST http://localhost:8083/api/v1/investigate \
  -H 'Content-Type: application/json' \
  -d '{
    "service": "local",
    "query": "Which deal should I follow up on this week?"
  }'
```

Example Zoho indexing request after a connection has been created:

```bash
curl -X POST http://localhost:8083/api/v1/index/zoho \
  -H 'Content-Type: application/json' \
  -d '{
    "service": "local",
    "connectionId": "connection-id",
    "lookbackDays": 90,
    "minQualityScore": 0.35,
    "embed": false,
    "fullRefresh": false
  }'
```

## 12. CLI capabilities

The CLI is useful for local-first workflows:

```bash
andro index --recreate .
andro search --hybrid "authentication timeout"
andro ask "where is the main entry point?"
andro usages 'PaymentService#charge'
andro impact PaymentService
andro plan "add audit logging to payments"
```

The interactive shell supports natural-language routing to commands such as search, ask, index,
usages, impact, and plan. The symbol graph uses lightweight JSON adjacency lists instead of a
separate graph database:

- Forward edges represent outgoing dependencies.
- Reverse edges represent incoming usages.
- BFS traversal is cycle-safe.
- Hop distance contributes to result ordering.

## 13. MCP integration

The MCP server is a thin adapter. It does not own the index or duplicate business logic:

```text
Cursor / Claude Code
        |
        | MCP stdio or Streamable HTTP
        v
Node MCP adapter
        |
        | REST
        v
Andromedia core API
        |
        v
Lucene index and investigation services
```

This separation means the same retrieval and safety rules apply whether the request comes from the
web UI, CLI, REST client, or an AI agent.

## 14. Security design

### Secrets

- API keys are supplied through environment variables, runtime settings, or the credential vault.
- Settings responses do not return API keys.
- Zoho refresh tokens are encrypted and never sent to the browser.
- Runtime-entered keys are held in backend memory and are not written to source control.
- Production secrets should be provided through a secret manager or platform secret reference.

### Access control and isolation

- `service` is used as a logical data boundary.
- Hosted trial mode can require Clerk JWT verification.
- Trial access and query counts are guarded by backend services.
- IP rate limiting protects API endpoints.
- CORS is configured for the deployed frontend origins.
- Per-request credential handling avoids putting tenant credentials into shared browser state.

### Evidence safety

Security is not only authentication. The investigation layer also protects against untrusted
answers by:

- Separating structured fields from free-text notes.
- Excluding demo records from primary recommendations.
- Preventing invented contacts and activities.
- Requiring exact evidence names for selected records.
- Refusing to answer about a different entity when the requested entity is absent.

## 15. Deployment architecture

The documented production layout is:

```text
Vercel
  -> Next.js landing/product frontend
  -> App Runner backend API
  -> RDS PostgreSQL credential vault
  -> external Zoho / Slack / Notion / Jira / Linear APIs
  -> Groq / DeepSeek / OpenRouter as configured
```

Typical deployment components:

- Backend container built from the `andromedia/` module.
- Amazon ECR image.
- AWS App Runner service exposing port 8083.
- RDS PostgreSQL for durable user credentials.
- Flyway migrations for credential tables.
- Vercel project using `landing/` as the root.

Important operational consideration:

- Lucene indexes are stored on the local filesystem or Docker volume.
- A normal App Runner filesystem is ephemeral, so durable index storage such as EFS should be
  considered for a long-lived production deployment.
- The documented current RDS approach prioritizes simplicity; a later hardening step can move RDS
  private behind a VPC connector and NAT while preserving outbound LLM connectivity.

## 16. Testing strategy

Testing is organized around pure logic, mocked transport behavior, and module-level tests.

### Unit tests

- Canonical entity normalization.
- Query classification and routing.
- Qualification and ranking calculations.
- Money extraction and conflict detection.
- Demo filtering and quality signals.
- Symbol extraction and reference resolution.
- Graph traversal and impact ranking.
- Retry/backoff calculations.

### HTTP behavior tests

Mocked HTTP tests verify:

- Zoho pagination and token refresh behavior.
- Slack posting and API handling.
- OpenAI-compatible chat completion requests.
- Streaming Server-Sent Event parsing.
- Groq retry behavior.
- DeepSeek fallback after transient Groq failure.
- No fallback after a partial stream or non-transient error.

### Integration-style tests

Lucene tests exercise:

- Index writes and reads.
- Multi-reader index access.
- Structured Zoho fields.
- Embedding coverage.
- Hybrid retrieval repair.
- Cross-source evidence merging.

### Test commands

```bash
# Build and test the Maven reactor
mvn clean verify

# Run a module and its dependencies
mvn test -pl search,cli -am -q

# Run focused LLM tests
mvn -pl llm -Dtest=ConfigurableChatServiceTest,OpenRouterChatServiceTest test
```

## 17. Important engineering decisions and trade-offs

### Lucene instead of a hosted search database

**Decision:** use Lucene on the local filesystem.

**Why:**

- Local-first data control.
- No required search infrastructure.
- Good lexical and vector primitives.
- Simple Docker volume and CLI story.

**Trade-off:**

- Filesystem lifecycle and durability need operational care in App Runner.
- Distributed multi-node indexing is not the primary V1 goal.

### Deterministic calculations before LLM synthesis

**Decision:** calculate amounts, counts, rankings, eligibility, and quality signals in Java.

**Why:**

- Arithmetic and ordering are more reliable in code.
- Results are testable and repeatable.
- The LLM is used for explanation, not as a database query engine.

**Trade-off:**

- More domain logic must be maintained.
- The classifier and routing paths need careful testing.

### Preserve demo records but exclude them from recommendations

**Decision:** index demo records with trust signals instead of deleting them.

**Why:**

- Evidence remains auditable.
- Data-quality problems are visible.
- Recommendations can still exclude unsafe records.

**Trade-off:**

- Every answer path must respect demo flags.

### Source-aware chunks instead of generic documents

**Decision:** preserve source-specific metadata and boundaries.

**Why:**

- A Slack thread, CRM deal, code method, and Jira issue have different meaning.
- Ranking and answer guardrails need that metadata.

**Trade-off:**

- More connector-specific code and models.
- Cross-source canonicalization becomes a real design problem.

### Automatic LLM fallback

**Decision:** Groq primary, DeepSeek fallback only for transient errors.

**Why:**

- One provider's rate limit or temporary outage should not stop an investigation.
- Authentication and payload errors should not be hidden by switching providers.
- Partial streaming must not be duplicated.

**Trade-off:**

- Two provider credentials may be required.
- Provider-specific model behavior can vary.
- Observability should record which provider produced the final response.

## 18. Known limitations to state honestly

These are good answers when asked what you would improve:

- Zoho is the only implemented CRM adapter; the abstraction exists for future Salesforce or
  HubSpot adapters.
- Duplicate-account and advanced entity resolution is still an area for improvement.
- Loss reasons and other CRM concepts are not fully semantically normalized.
- There is no webhook-driven real-time synchronization for every connector.
- The in-memory job store can lose job status after a restart.
- Very large Zoho orgs may eventually need Bulk Read support.
- Not all source types have equally deep incremental synchronization.
- App Runner's local index needs durable storage for a resilient production deployment.
- API observability can be expanded with provider, retry, latency, and ingestion metrics.

## 19. Common Zoho interview questions and answers

### Q1. Why did you choose Zoho as the first CRM?

> Zoho provided a concrete CRM workflow with Deals, Accounts, Contacts, Notes, and Activities,
> while the product still needed a clean seam for future CRMs. I built a CRM-oriented ingestion
> model and a Zoho adapter first, rather than pretending that different CRM schemas are
> interchangeable. The code keeps shared CRM concepts separate from Zoho API details, so another
> adapter can be added later.

### Q2. How do you prevent LLM hallucinations?

> I do not let the LLM calculate the full CRM answer from raw text. The backend builds a
> structured ledger, computes rankings and quality signals in Java, and sends only retrieved
> evidence plus deterministic facts to the model. Prompts require exact evidence names, prohibit
> invented records and activities, distinguish structured fields from unverified notes, and
> require insufficiency when the evidence is missing.

### Q3. How do you handle inconsistent Zoho data?

> I preserve the record and expose quality signals instead of silently deleting it. Missing
> fields, stale dates, placeholder contact details, demo patterns, and passed close dates become
> explicit signals. Demo records remain searchable for auditability but are excluded from primary
> pipeline recommendations.

### Q4. How does Zoho pagination work?

> I use Zoho's cursor-based `page_token` and `next_page_token` flow with bounded page sizes. I do
> not rely on numeric offsets because Zoho limits offset pagination. If a token expires during a
> module fetch, the client can restart that module once. Requests also retry transient statuses
> with exponential backoff and honor `Retry-After`.

### Q5. How do you keep the index synchronized?

> The first run can perform a full sync. Successful syncs persist a per-service/per-region
> Modified-Time watermark. Later runs fetch records modified after that watermark, upsert changed
> chunks, remove deleted records where the API supports it, and advance the watermark only after a
> successful Lucene commit. A stale watermark or explicit `fullRefresh` triggers a full refresh.

### Q6. How are Zoho relationships represented?

> The primary unit is a deal-centric context. Each deal is enriched with its account, explicit and
> account-linked contacts, deal-related notes, and activities. Structured CRM fields remain
> separately stored from narrative text so retrieval and synthesis can treat them differently.

### Q7. Why use Lucene instead of PostgreSQL full-text search?

> Lucene gives the project strong BM25 retrieval, analyzers, vector KNN support, and a local
> filesystem index without requiring a separate search cluster. PostgreSQL is used for the
> credential vault, not as the primary knowledge index. If multi-node search or very large-scale
> indexing becomes a requirement, the indexing boundary gives us a place to evaluate a distributed
> search service later.

### Q8. How do you handle CRM amounts versus Slack or Notion amounts?

> The structured Zoho Amount is treated as the CRM fact, while amounts found in Slack or Notion
> are preserved as source-specific narrative evidence. A deterministic conflict marker identifies
> divergent values, and the LLM is instructed to cite both values and their sources instead of
> silently preferring one.

### Q9. What happens when Slack mentions an account but not the exact deal?

> The system first matches the canonical account name because that reflects how people normally
> talk in chat. If the account has one active deal, the account match is sufficient. If it has
> multiple active deals, the evidence is retained as an account-level signal with an
> `possible_account_mention_unconfirmed_deal` flag unless deal-specific wording is present. That
> avoids both false certainty and silent evidence loss.

### Q10. How do you protect OAuth credentials?

> The browser sends a one-time grant token to the backend. The backend exchanges it, encrypts the
> refresh token with AES-GCM, stores it in the credential vault, and returns only a connection ID.
> The refresh token is never returned to the browser. Production uses a durable encryption key from
> a secret manager, while the local ephemeral-key option is explicitly treated as development-only.

### Q11. What would you improve next?

> I would prioritize duplicate/entity resolution, semantic normalization of CRM fields such as
> loss reasons, webhook-based synchronization, durable job state, Bulk Read for very large Zoho
> orgs, and stronger observability. I would also move production index storage to durable
> infrastructure and harden the RDS network path.

## 20. Two-minute project explanation

Use this version when the interviewer says, “Tell me about your project”:

> I worked on Andromedia, a local-first knowledge layer that unifies engineering and revenue
> context. It connects code, Git, Slack, Notion, Jira, Linear, and Zoho CRM. Each connector fetches
> data, preserves source metadata, chunks it into meaningful units, enriches it, and writes it to a
> service-scoped Lucene index. The index supports BM25, optional vector retrieval, and hybrid
> ranking.
>
> The Zoho part is deal-centric. We exchange a Self Client grant token on the backend, vault the
> encrypted refresh token, discover fields, fetch Deals, Accounts, Contacts, Notes, and
> Activities with cursor pagination, resolve relationships, score data quality, and create one
> unified context per deal. We also support incremental synchronization with a Modified-Time
> watermark and deleted-record cleanup.
>
> For questions, the backend classifies the query. Corpus-wide CRM questions use a full ledger and
> deterministic Java calculations for amounts, rankings, stale records, and demo exclusion.
> Named-entity questions pin the correct CRM entity and attach related Slack or Notion evidence.
> Finally, a constrained LLM prompt summarizes only the supplied evidence. This design makes the
> LLM useful for explanation while keeping arithmetic, filtering, provenance, and trust decisions
> testable in code.
>
> The application is available through a Spring WebFlux REST API, a React/Next.js UI, a Picocli
> CLI, and an MCP adapter for AI coding agents. The main production improvements I would make next
> are better duplicate-account resolution, webhooks, durable job/index storage, and more
> observability.

## 21. Strong closing statement

> The main lesson from the project was that an AI system over business data needs more than a
> vector database and a prompt. It needs source-aware ingestion, structured facts, relationship
> enrichment, deterministic business rules, explicit trust signals, and answer-level provenance.
> That combination is what makes the result useful in a Zoho workflow instead of just producing a
> plausible paragraph.

