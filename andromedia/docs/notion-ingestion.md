# Notion ingestion

Slack-shaped pull of Notion pages into Lucene (`sourceType=NOTION`) so investigate can synthesize architecture docs with Slack decisions and Git history.

## Pipeline

```text
POST /api/v1/index/notion
  → NotionIngestionJobService
      · users.me validate
      · expand databaseIds → page IDs
      · retrieve page + fetchAllBlocks (recursive children)
      · NotionPageFilter → NotionPageChunker (heading sections)
      · NotionHistoryChunkBuilder → [NOTION] chunks
      · LuceneIndexingService.indexNotionHistory
      · .andromedia/notion-history.json cursors
```

### Recursive blocks (critical)

Notion returns a **tree**. `NotionApiClient.fetchAllBlocks` paginates `blocks/{id}/children` and recurses when `has_children` is true. Without this, toggles/nested lists are silently dropped. Do **not** recurse into `child_page` / `child_database` bodies (index those via `pageIds` / DB query instead).

Always send header `Notion-Version: 2022-06-28`.

## API

```bash
# Test
curl -s -X POST http://localhost:8083/api/v1/integrations/notion/test \
  -H 'Content-Type: application/json' \
  -d '{"apiKey":"secret_xxx","pageIds":["abc123"]}'

# Index
curl -s -X POST http://localhost:8083/api/v1/index/notion \
  -H 'Content-Type: application/json' \
  -d '{"service":"local","apiKey":"secret_xxx","pageIds":["abc123"],"databaseIds":[],"embed":false}'

# Poll
curl -s http://localhost:8083/api/v1/index/notion/<jobId>
```

## Chunk shape

```text
[NOTION] Auth Architecture · updated 2026-05-18 · chandru
section: Why JWT over Sessions

Session store was causing OOM...
```

Body capped at 2000 chars per section.

## Ranking

`ArchitectureIntentDetector` + `andromedia.ranking.notion-architecture-boost` (default `2.0`) boosts NOTION hits for queries like “how does … work”, ADR, architecture, design.

## Config

```yaml
andromedia:
  notion:
    api-key: ${NOTION_API_KEY:}
    page-ids: []
    database-ids: []
```

Share pages/databases with the Notion integration or retrieve will 404.
