# Git history ingestion — how it works today

This document describes the **git history ingestion pipeline** as implemented in Andromedia v1: what it does, how to run it, and known gaps (especially why `andro search` does not surface commit history).

---

## What it is for

The pipeline answers questions like:

- **Why** was this file or symbol changed?
- **When** did a change land?
- What was the **commit message** or **PR context**?

It does **not** store raw diffs. Each commit becomes one **structured text chunk** (commit message + changed files + changed symbols) indexed into Lucene for BM25 and optional vector search.

---

## Architecture

```text
Local repo (full .git history)
        │
        ▼
GitIngestionService (JGit)
  · walk commits (newest first)
  · diff vs first parent (EmptyTreeIterator for root commit)
  · optional PR metadata (merge-commit parser)
        │
        ▼
GitHistoryChunkBuilder
  · one chunk per commit
  · front-loaded text: [GIT_COMMIT], subject, files, symbols
  · body/description truncated (500 / 300 chars)
        │
        ▼
LuceneIndexingService.indexGitHistory()
  · sourceType=GIT, commitSha, timestamp, filePaths, author
        │
        ▼
Same Lucene index as code chunks (when both are present)
        │
        ▼
HybridSearchService / investigate API
```

### Key modules

| Component | Location |
|-----------|----------|
| Types (`GitChangeEvent`, `GitHistoryChunk`) | `common/` |
| JGit walk + diff | `ingestion/.../git/GitIngestionService.java` |
| Chunk text assembly | `ingestion/.../git/GitHistoryChunkBuilder.java` |
| PR parsing (GitHub merge/squash) | `ingestion/.../git/MergeCommitPullRequestParser.java` |
| Lucene write | `indexing/.../LuceneIndexingService.java` |
| Async job + API | `core/.../GitHistoryJobService.java` |

---

## Index locations

| Index | Path | Created by |
|-------|------|------------|
| **Project (CLI)** | `~/.andromedia/index/<projectHash>/` | `andro index .`, **`andro index history .`** |
| **Service (API)** | `~/.andromedia/index/by-service/<service>/` | `POST /api/v1/index/history` |

`<projectHash>` = first 8 hex chars of `sha256(workspaceRoot)`.

**`andro search`, `andro ask`, and `andro investigate` use the project index.** Use **`andro index history`** to ingest git commits into that same index so history is visible to the CLI.

The API still writes the **service index** (for remote/service-scoped workflows). Both paths can coexist; they are not merged automatically.

Incremental cursor for CLI project history: `{workspaceRoot}/.andromedia/git-history.json` (`lastIndexedCommitSha`).

---

## How to run it

### Prerequisites

- **Local repo** with full `.git` history (not a shallow API clone).

### 1. Index git history into the project index (CLI — recommended)

```bash
cd /path/to/your-repo
andro index .                    # code chunks (optional but typical)
andro index history .            # git commits into the same project index
andro index history . --max-commits 50
andro index history . --embed      # optional embeddings
andro index history . --since 2024-01-01T00:00:00Z --recreate
```

Search and ask then see GIT chunks alongside code:

```bash
andro search "implemented impact and plan options"
andro ask "why was impact and plan options implemented?"
```

### 2. Ingest git history (API — service index)

**Core server** running for API jobs:

```bash
cd /path/to/andromedia
mvn -pl core -am install -DskipTests
mvn -pl core spring-boot:run
```

Server default: `http://localhost:8083`

```bash
curl -s -X POST http://localhost:8083/api/v1/index/history \
  -H 'Content-Type: application/json' \
  -d '{
    "service": "andromedia",
    "localPath": "/home/you/your-repo",
    "embed": false,
    "maxCommits": 50
  }'
```

Response includes `jobId`. Poll until completed:

```bash
curl -s http://localhost:8083/api/v1/index/history/<jobId> | python3 -m json.tool
```

### 3. Check service metadata

```bash
curl -s http://localhost:8083/api/v1/index/services/andromedia | python3 -m json.tool
```

Example stats:

```json
{
  "commitsIndexed": 11,
  "chunksIndexed": 11,
  "sourceType": "git-history",
  "indexPath": "/home/you/.andromedia/index/by-service/andromedia",
  "lastIndexedCommitSha": "ea8d8e1..."
}
```

### 4. Incremental re-runs

The CLI stores `lastIndexedCommitSha` in `.andromedia/git-history.json`. The API stores it in service metadata. The next run only indexes commits **newer** than that SHA. If there are no new commits, the CLI reports **“No new git history events found”**; the API job may fail with **“No git history events found”**.

### 5. Code index (CLI)

```bash
cd /path/to/your-repo
andro index .
andro index history .
andro search "SomeClass"
```

---

## Explore / investigate behavior

`ExploreService` (used by `andro ask` and `/api/v1/investigate`) formats GIT hits with full chunk `content` (capped at 1500 chars), `commitSha`, and `timestamp` so the LLM sees commit messages and changed files.

History-intent queries (`why`, `when`, `changed`, `commit`, `history`, `who`) apply a **git history boost** (`andromedia.ranking.git-history-boost`, default `2.0`) to GIT chunks during heuristic ranking.

---

## What each chunk contains

Indexed text shape (simplified):

```text
[GIT_COMMIT] ea8d8e1 · 2026-07-10T... · Author Name
implemented impact and plan options

Changed: cli/.../ImpactShellHandler.java, ...
Symbols: ImpactShellHandler#handle, ...

<body up to 500 chars>

PR #42: title
<description up to 300 chars>
```

Lucene fields for GIT documents:

| Field | Purpose |
|-------|---------|
| `sourceType` | `GIT` |
| `commitSha` | Full SHA |
| `timestamp` | Commit time (ISO string) |
| `filePaths` | Changed paths (multi-valued) |
| `author` | Commit author |
| `content` | Chunk text above |
| `symbolIds` / `symbolNames` | Changed symbols |

Code chunks have no `sourceType` field (implicitly “code”).

---

## What works today

| Capability | Status |
|------------|--------|
| JGit commit walk + diff metadata | Yes |
| Java symbol resolution from diff hunks | Yes |
| GitHub merge/squash PR message parsing | Yes |
| API `POST /api/v1/index/history` | Yes |
| Incremental cursor (`lastIndexedCommitSha`) | Yes |
| BM25 search over git chunks (service index) | Yes |
| Embeddings on history (`"embed": true`) | Yes (needs `OPENROUTER_API_KEY`) |
| `SearchHit` fields `sourceType`, `commitSha`, `timestamp` | Yes (in search layer) |

---

## Problems and limitations (git history)

### 1. CLI vs API — two indexes

**Problem:** `andro search` reads the **project** index; git history is written to the **service** index.

**Symptom:** After a successful `POST /api/v1/index/history`, `andro search "implemented embeddings"` still returns **Java source files**, not `[GIT_COMMIT]` lines.

**Workaround:** Use the API (`/investigate` or future CLI) against the service name, or add `andro index history` (not shipped yet).

---

### 2. No `andro index history` command

**Problem:** History ingestion is **API-only**. Users who live in the `andro` CLI must use `curl` for the history job.

---

### 3. `/investigate` does not surface commit text well

**Problem:** `ExploreService` passes only **file names** (and optional class/method) to the LLM:

```text
Hybrid search hits:
- ImpactShellHandler.java  score=0.021
```

It does **not** include the indexed **`content`** field (`[GIT_COMMIT] …`, subject, body). The model never sees *why* the commit happened.

**Symptom:** Investigate suggests “review commit history” while search hits look like code references, even when the index contains **only** 11 git chunks.

---

### 4. No GIT boost or filter for “why / when” queries

**Problem:** Hybrid search treats code and history chunks equally. History chunks are short; code chunks repeat terms like “embedding”, “impact”, “Service” across many methods.

**Symptom:** Questions like “Why was ExploreService changed?” rank **source files** over **commit messages**, especially if both chunk types share one index.

---

### 5. Investigate output hides provenance

**Problem:** `ExploreService` does not print `sourceType`, `commitSha`, or `timestamp` even though `SearchHit` supports them.

**Symptom:** Users cannot tell whether a hit is a **code chunk** or a **git history chunk**.

---

### 6. Shallow clones and `GitCloneService`

**Problem:** `GitCloneService` (used by `POST /api/v1/index` for remote repos) uses **depth-1** clones. History ingestion needs **full local history**.

**Symptom:** Pointing history jobs at API-cloned repos under `~/.andromedia/repos/` will not walk old commits.

**Mitigation:** Pass a **local path** with full `.git` to `/index/history`.

---

### 7. `GitHubPullRequestProvider` is a stub

**Problem:** PR **bodies** from GitHub API are not fetched. Only merge-commit **subject/body** parsing works.

**Mitigation:** Squash/merge commits that include `(#123)` or `Merge pull request #123` still get PR numbers and titles from the commit message.

---

### 8. Re-run with no new commits

**Problem:** Incremental indexing stops at `lastIndexedCommitSha`. No new commits → empty event list → job error.

**Mitigation:** Make new commits, or reset metadata / use a fresh service name for a full re-index.

---

### 9. Server build after schema changes

**Problem:** Running `mvn -pl core spring-boot:run` without rebuilding dependent modules can load a stale `andromedia-indexing` JAR from `~/.m2`.

**Symptom:** `ClassNotFoundException: GitHistoryIndexingStats` on startup.

**Fix:**

```bash
mvn -pl core -am install -DskipTests
mvn -pl core spring-boot:run
```

---

## Example: what you saw in practice

```bash
# Project index — code chunks with line numbers
andro search "ImpactShellHandler"
# → ImpactShellHandler.java:35-47 ...

# Same CLI — still code index
andro search --hybrid "embeddings"
# → LuceneIndexingService.java, OpenRouterEmbeddingService.java, ...

# Service index — git history (separate)
curl -X POST .../api/v1/index/history ...
# → commitsIndexed: 11, sourceType: git-history
```

All of the above is **consistent** with the two-index design, not a failed history ingest.

---

## Recommended workflow (current)

| Goal | Tool |
|------|------|
| Index and search **source code** | `andro index .` → `andro search` / `andro ask` |
| Ingest **git history** | `POST /api/v1/index/history` |
| Ask **why** (best effort) | `POST /api/v1/investigate` with `service` set |
| Confirm history indexed | `GET /api/v1/index/services/<service>` |

---

## Likely next improvements

1. **`andro index history <path>`** — write into the **project** index (same as `andro index .`).
2. **Explore / investigate** — include `[GIT_COMMIT]` **content** and `commitSha` / date for `sourceType=GIT`.
3. **Query intent** — boost or filter GIT chunks for “why”, “when”, “changed”, “commit”.
4. **`andro investigate`** or **`andro history search`** — CLI parity with API.
5. **GitHub PR API provider** — full PR descriptions when a token is configured.

---

## Related files

- Plan (implementation spec): `.cursor/plans/git_history_ingestion_*.plan.md` (internal)
- HTTP routes: `core/.../ApiRouterConfig.java`
- Job orchestration: `core/.../GitHistoryJobService.java`
- Config: `andromedia.git.max-commits` in `core/src/main/resources/application.yml`
