# Andromedia

**Company brain for engineering teams.**  
Index your code, git history, Slack, Notion, Jira, Linear, and Zoho CRM — then ask grounded questions across all of it.

![Andromedia banner](docs/images/banner-terminal.png)

```text
Code + Git + Slack + Notion + Jira + Linear  →  one searchable index  →  answers with provenance
```

---

## Why Andromedia

| Pain | What Andromedia does |
|------|----------------------|
| “Why did we switch auth?” lives in Slack | Indexes threads and boosts decision-style queries |
| Architecture is buried in Notion | Chunks pages by section for ADR-style search |
| Bugs are scattered across Jira / Linear | Indexes issues + comments as ticket chunks |
| Code context is siloed from tickets | Same Lucene index powers investigate + search |

Built for developers who want **local-first control**, hybrid BM25 + vector search, and a thin API your UI / MCP / agents can call.

---

## Quick start (Docker — recommended)

**Requirements:** Docker + Docker Compose, ~Java-free on the host.

```bash
# 1. Configure (optional keys — integrations can also send tokens from the UI)
cp .env.example .env
# Edit .env: GROQ_API_KEY, OPENROUTER_API_KEY, …

# 2. Start the API
docker compose up --build -d

# 3. Verify
curl http://localhost:8083/api/v1/health
# → {"status":"UP"}
```

| | |
|--|--|
| **API** | `http://localhost:8083` |
| **Health** | `GET /api/v1/health` |
| **Actuator** | `GET /actuator/health` |
| **Data** | Docker volume `andromedia-data` → `/home/andromedia/.andromedia` |
| **Repos for indexing** | Bind-mount `./workspace` → `/workspace` (read-only) |

Stop without losing indexes:

```bash
docker compose down
```

Rebuild after backend changes:

```bash
docker compose up --build -d
```

---

## Connect the UI

1. Start the Andromedia API (`docker compose up` or Maven — below).
2. Run the Andro frontend (separate `andro` app): `npm run dev` → typically `http://localhost:5173`.
3. API base defaults to `http://localhost:8083`. Override with:

```bash
export VITE_ANDROMEDIA_API_URL=http://localhost:8083
```

4. Open **Sources** → Configure each integration → **Test** → **Save & index**.

Supported live integrations today: **Git**, **Slack**, **Notion**, **Jira**, **Linear**, **Zoho CRM**.

---

## First indexing recipes

### Code + git (from Docker)

```bash
# On the host: put or symlink a repo into ./workspace
ln -s /path/to/my-service ./workspace/my-service

curl -X POST http://localhost:8083/api/v1/index \
  -H 'Content-Type: application/json' \
  -d '{"service":"local","localPath":"/workspace/my-service","embed":false}'
```

Optional history:

```bash
curl -X POST http://localhost:8083/api/v1/index/history \
  -H 'Content-Type: application/json' \
  -d '{"service":"local","localPath":"/workspace/my-service","embed":false}'
```

### Slack / Notion / Jira / Linear

Credentials are usually sent from the UI. Curl examples:

```bash
# Notion test
curl -X POST http://localhost:8083/api/v1/integrations/notion/test \
  -H 'Content-Type: application/json' \
  -d '{"apiKey":"secret_xxx","pageIds":["page-id"]}'

# Jira test
curl -X POST http://localhost:8083/api/v1/integrations/jira/test \
  -H 'Content-Type: application/json' \
  -d '{"baseUrl":"https://your.atlassian.net","email":"you@company.com","apiToken":"xxx","projectKeys":["KAN"]}'

# Linear test
curl -X POST http://localhost:8083/api/v1/integrations/linear/test \
  -H 'Content-Type: application/json' \
  -d '{"apiKey":"lin_api_xxx","teamKeys":["Softtwig"]}'
```

Deep dives:

- [Git history](docs/git-history-ingestion.md)
- [Slack](docs/slack-ingestion.md)
- [Notion](docs/notion-ingestion.md)
- [Jira](docs/jira-ingestion.md) — uses `POST /rest/api/3/search/jql` + ADF→plain text
- [Linear](docs/linear-ingestion.md) — team **name** or **key** accepted

---

## Environment

Copy [`.env.example`](.env.example) → `.env`:

| Variable | Required for | Notes |
|----------|--------------|--------|
| `GROQ_API_KEY` | Investigate / ask / plan chat | Groq OpenAI-compatible API |
| `OPENROUTER_API_KEY` | Embeddings / hybrid search | Optional if `embed:false` |
| `SLACK_BOT_TOKEN` | Optional server default | UI can pass token per job |
| `NOTION_API_KEY` | Optional server default | UI can pass token per job |

---

## Product surface

### HTTP API (`core`)

| Area | Endpoints |
|------|-----------|
| Health | `GET /api/v1/health` |
| Code index | `POST /api/v1/index`, `GET /api/v1/index/{jobId}` |
| Git history | `POST /api/v1/index/history`, `GET …/{jobId}` |
| Slack | `POST /api/v1/integrations/slack/test`, `POST/GET /api/v1/index/slack` |
| Notion | `POST /api/v1/integrations/notion/test`, `POST/GET /api/v1/index/notion` |
| Jira | `POST /api/v1/integrations/jira/test`, `POST/GET /api/v1/index/jira` |
| Linear | `POST /api/v1/integrations/linear/test`, `POST/GET /api/v1/index/linear` |
| Zoho CRM | `POST /api/v1/integrations/zoho/test`, `POST /api/v1/integrations/zoho/exchange-grant`, `POST/GET /api/v1/index/zoho` |
| Overview | `GET /api/v1/index/services/{service}/overview` |
| Investigate | `POST /api/v1/investigate` |

### CLI (`andro`) — local power-user path

```bash
mvn clean verify
./cli/bin/install-andro   # installs to ~/.local/bin

export GROQ_API_KEY=…
export OPENROUTER_API_KEY=…

cd /path/to/project
andro index --embed --recreate .
andro search --hybrid "authentication filter"
andro ask "where is the main entry point?"
```

| Command | Purpose |
|---------|---------|
| `andro index` | Index source (Lucene + optional embeddings) |
| `andro index history` | Index commits / PR mentions into the same index |
| `andro search` | BM25 / semantic / hybrid search |
| `andro ask` | Grounded Q&A |
| `andro usages` / `andro impact` | Symbol graph |
| `andro plan` | Implementation plans with grounding checks |
| `andro` | Interactive REPL |

![CLI help](docs/images/install-help.png)

---

## Architecture (short)

```text
 Sources (GitHub / Slack / Notion / Jira / Linear / local files)
        │
        ▼
 Ingestion (ApiClient → Filter → Chunker → ChunkBuilder)
        │
        ▼
 Lucene index  (~/.andromedia / Docker volume)
        │
        ▼
 Search + HeuristicRanker + Explore / Investigate
        │
        ▼
 HTTP API  ·  CLI  ·  Frontend  ·  MCP (optional)
```

| Module | Role |
|--------|------|
| `common` | Chunk models, index constants, path/cursor helpers |
| `ingestion` | Chunking + source connectors |
| `indexing` | Lucene writers |
| `search` | Hybrid retrieval, ranking, explore/plan |
| `llm` | Chat + embeddings clients |
| `core` | Spring WebFlux API (what Docker runs) |
| `cli` | `andro` Picocli + shell |

Indexes for the API live under `~/.andromedia/index/by-service/{service}` (in Docker: volume-backed home).

---

## Local development (without Docker)

```bash
# Java 21 + Maven
mvn install -DskipTests
mvn -pl core -am spring-boot:run
# API: http://localhost:8083

mvn clean verify
mvn test -pl search,cli -am -q
```

Always rebuild dependent modules with `-am` so ingestion/indexing changes load into `core`.

---

## Ops cheat sheet

| Task | Command |
|------|---------|
| Start API | `docker compose up --build -d` |
| Logs | `docker compose logs -f andromedia-api` |
| Health | `curl http://localhost:8083/api/v1/health` |
| Overview | `curl http://localhost:8083/api/v1/index/services/local/overview` |
| Stop | `docker compose down` |
| Wipe indexes | `docker compose down -v` ⚠️ deletes volume |

---

## Status & contributing

Implementation status and known gaps: see [STATUS.md](STATUS.md) if present in your checkout.

PRs welcome for connectors, ranking, and retrieval quality. Prefer small, test-covered changes (`mvn test -pl <module> -am`).

---

## License

Proprietary / project license as configured by your team. Update this section before public release.
