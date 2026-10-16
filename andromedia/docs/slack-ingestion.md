# Slack history ingestion — how it works

This document describes the **Slack ingestion pipeline** in Andromedia v1: bot-token pull of selected channels, thread-as-chunk indexing into Lucene, and how explore/search surfaces decision context alongside code and git history.

---

## What it is for

The pipeline answers questions like:

- **What did the team decide** about X?
- **Why** did we choose approach Y over Z?
- What was discussed during an **incident** or design thread?

It does **not** index DMs or channels the bot is not invited to. Each Slack **thread** (parent + replies) becomes one structured text chunk indexed into the same Lucene index as code and git history.

---

## Architecture

```text
Slack workspace (bot token)
        │
        ▼
SlackApiClient
  · conversations.list / history / replies / users.info
        │
        ▼
SlackMessageFilter
  · drop bot_message, join/leave, empty text, Andro pipeline/investigate self-posts
        │
        ▼
SlackThreadChunker
  · group by thread_ts (standalone messages = one chunk)
        │
        ▼
SlackHistoryChunkBuilder
  · [SLACK] channel · date · participants
  · thread text + reaction summary
        │
        ▼
LuceneIndexingService.indexSlackHistory()
  · sourceType=SLACK, channel, timestamp, participants, reactionCount
        │
        ▼
HybridSearchService / ExploreService
  · decision-intent boost for SLACK chunks
```

There is **no** separate Slack orchestrator class. `SlackIngestionJobService` calls ingest → chunk builder → `indexSlackHistory()` directly (same pattern as `indexGitHistory()` on `LuceneIndexingService`).

### Key modules

| Component | Location |
|-----------|----------|
| Types (`SlackChunk`) | `common/` |
| Cursor store | `common/.../SlackHistoryCursorStore.java` |
| API client + filter + chunker + builder | `ingestion/.../slack/` |
| Lucene write | `indexing/.../LuceneIndexingService.java` |
| Async job + API | `core/.../SlackIngestionJobService.java` |

---

## How to run it

### Prerequisites

- Slack app with a **Bot User OAuth Token** (`xoxb-...`)
- Bot invited to the channels you want indexed **and** any channel used for Send to Slack / pipeline alerts
- Scopes typically needed:
  - Read/index: `channels:history`, `channels:read`, `groups:history`, `groups:read`, **`users:read`**
  - Write/notify: **`chat:write`** (required for `POST /api/v1/integrations/slack/message` and post-reindex pipeline alerts)

### Notify APIs (local/dev)

| Route | Purpose |
|-------|---------|
| `POST /api/v1/integrations/slack/channels` | List channels for the bot token (channel picker) |
| `POST /api/v1/integrations/slack/message` | Post a grounded investigate answer (summary, reasons, fields, flags) |
| `GET/PUT /api/v1/alerts/slack` | Per-service pipeline alert config (enabled, channel, bot token) |

After each successful Zoho reindex, `DealAlertMonitor` diffs deal flags (`stale_record`, `close_date_passed`, hot stages) against `~/.andromedia/alerts/{service}.json` and posts a digest when newly crossed.

Optional env fallbacks: `ANDRO_ALERTS_SLACK_BOT_TOKEN`, `ANDRO_ALERTS_SLACK_CHANNEL`.

### 1. Index via API

Core server:

```bash
cd /path/to/andromedia
mvn -pl core -am install -DskipTests
mvn -pl core spring-boot:run
```

```bash
curl -s -X POST http://localhost:8083/api/v1/index/slack \
  -H 'Content-Type: application/json' \
  -d '{
    "service": "andromedia",
    "botToken": "xoxb-...",
    "channels": "#backend-eng, #incidents",
    "embed": false,
    "lookbackDays": 90
  }'
```

Poll:

```bash
curl -s http://localhost:8083/api/v1/index/slack/<jobId> | python3 -m json.tool
```

### 2. Config properties

```yaml
andromedia:
  slack:
    bot-token: ${SLACK_BOT_TOKEN:}
    channels: []
    lookback-days: 90
    skip-bot-messages: true
    max-messages-per-channel: 5000
  ranking:
    slack-decision-boost: 2.5
```

### 3. UI (andro)

In the Slack configure modal, enter **bot token** + **channels**, then **save**. The UI POSTs to `/api/v1/index/slack` (default API base `http://localhost:8083`, override with `VITE_ANDROMEDIA_API_URL`).

---

## Incremental cursor

- File: `{workspace}/.andromedia/slack-history.json` → `{ channelId: lastIndexedTimestamp }`
- API metadata also stores `slackCursors` on the service stats map

Next run only pulls messages newer than the per-channel cursor (still bounded by `lookbackDays` on first run).

---

## Chunk shape

```text
[SLACK] #backend-eng · 2026-06-14 · chandru, ravi, priya
thread: 8 messages · reactions: 7

chandru: why are we seeing timeout cascades...
ravi: traced it to the Feign client...
priya: we decided to switch to async Kafka publish...

[reactions: +1×5 white_check_mark×2]
```

Lucene fields:

| Field | Purpose |
|-------|---------|
| `sourceType` | `SLACK` |
| `channelId` / `channelName` | Channel identity |
| `threadTs` | Thread key |
| `timestamp` | Thread start time |
| `participants` | Display names (multi-valued) |
| `reactionCount` | Aggregate reaction signal |
| `content` | Assembled thread text |
| `path` | `slack://#channel/threadTs` |

---

## Explore / ranking behavior

`ExploreService` formats SLACK hits with full thread `content` (capped at 1500 chars), channel name, and timestamp.

Decision-intent queries (`decided`, `agreed`, `why`, `because`, `chose`, …) apply **`andromedia.ranking.slack-decision-boost`** (default `2.5`) to SLACK chunks, with a mild extra boost from reaction count parsed from the chunk header. Decision-intent explore also requests **10** hybrid hits (vs 5) so Slack is less likely to lose the slate to code.

`/api/v1/investigate` with `service=<name>` searches the same `~/.andromedia/index/by-service/<name>` path Slack ingestion writes — there is no `sourceType` allowlist that drops `SLACK`. Empty `referenceCount` is usually a **BM25 query** issue: identifier searches stay `AND`, while natural-language / decision / history queries sanitize filler terms and use `OR` so Slack prose can match on substantive tokens (`Kafka`, `payment`, `Feign`, …).

Dashboard stats use **`GET /api/v1/index/services/{service}/overview`**, which reads the Lucene directory on disk (chunk count, source types, size, last file mtime). Unlike in-memory `GET /api/v1/index/services/{service}` job metadata, overview survives process restart.

---

## v1 limitations

- No realtime Events API — on-demand / job pull only
- No DMs; private channels only if the bot is a member
- Bot messages and join/leave events are filtered out
- Long threads keep head + tail messages (middle omitted) to stay under the embedding char limit
