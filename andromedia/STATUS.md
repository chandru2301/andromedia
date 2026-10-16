# Andromedia — project status

Last updated: 2026-05-29

This document summarizes what is **finished**, what is **partially done / known gaps**, and what is **still pending** in the roadmap.

---

## Finished (shipped)

### Core CLI and indexing

| Area | Status | Notes |
|------|--------|--------|
| `andro index` | Done | Lucene BM25 indexing, semantic-boundary chunking (Java method-first) |
| `andro index --embed` | Done | OpenRouter embeddings batched into Lucene |
| `andro index --recreate` | Done | Full index rebuild |
| Default extensions | Done | `java`, `kt`, `kts`, `groovy`, **`py`** |
| Project-scoped index | Done | Per-workspace index under `~/.andromedia/index` |
| Index stats | Done | Files, chunks, symbols, imports, **references**, bytes |

### Search and RAG

| Area | Status | Notes |
|------|--------|--------|
| `andro search` (BM25) | Done | Content + heuristic ranking |
| `andro search --semantic` | Done | Vector search (requires embedded index) |
| `andro search --hybrid` | Done | BM25 + semantic RRF fusion |
| `andro ask` | Done | Hybrid retrieve → Groq chat, streaming, prompt budgeting |
| Ranking heuristics | Done | Test/main path boosts, identifier query handling |
| Context assembly | Done | `--debug --expand-context` on search |

### Interactive agent shell

| Area | Status | Notes |
|------|--------|--------|
| `andro` REPL (TTY) | Done | JLine shell, history at `~/.andromedia/history` |
| NL routing | Done | ask / search / index / usages / impact / plan intents |
| Explicit commands | Done | `ask`, `search`, `index`, **`usages`**, `help` |
| Progress UX | Done | Unified stderr phases for plan, impact, usages (matches index/search/ask) |
| Grounded planning | Done | Verified vs proposed files/symbols; architecture reuse hints; `--no-ground` to skip |
| System commands | Done | `/help`, `/exit`, `/debug`, `/index <path>`, etc. |

### Phase 1 — Structural metadata

| Area | Status | Notes |
|------|--------|--------|
| `CodeSymbol`, `StructuralMetadata`, `symbolId` | Done | Stable IDs: `java:…`, `python:…` |
| Java / Python extractors | Done | Imports, annotations, types, methods/functions |
| Lucene fields | Done | `imports`, `annotations`, `symbolIds`, `symbolNames`, `symbolTypes`, `referencedSymbolIds` |
| Chunk metadata wiring | Done | Attached during chunking / indexing |
| Tests + README | Done | Extractor, chunker, Lucene round-trip tests |

**Re-index required** after Phase 1 upgrade: `andro index --recreate /path`

### Phase 2 — Symbol usages

| Area | Status | Notes |
|------|--------|--------|
| `ProjectSymbolRegistry` + prepass | Done | Built before indexing for cross-file resolution |
| Reference resolvers | Done | Java + Python regex, import-aware |
| `referencedSymbolIds` at index time | Done | Stored on each Lucene chunk |
| `UsagesLookupService` | Done | Term queries on definitions + references |
| `andro usages` CLI | Done | Simple name, `Class#method`, full `symbolId` |
| Agent shell `usages` routing | Done | `usages of X`, `where is X used`, etc. |
| **Class#method no-match hints** | Done | Lists methods on class + `Try: andro usages 'Class#method'` |
| Tests + README | Done | Parser, lookup, CLI hint tests |

**Example (working after re-index):**

```bash
cd /path/to/project
andro index --recreate .
andro usages AnomalyEvent
andro usages 'AnomalyKafkaListener#consumeAnomaly'
```

---

## Partially done / known gaps

These work in some paths but have UX or coverage limitations.

| Item | What works | Gap |
|------|------------|-----|
| Agent shell `index --recreate .` | `/index .` or `index .` (path only) | **Fixed** — full Picocli forwarding in REPL (`index --recreate .`, `/index --recreate --embed .`) |
| **Reference resolution** | Java/Python v2 scoped resolution | Regex v2 — receiver typing, static imports, ambiguity omission; not a full type checker |
| **Method extraction** | Most methods | Annotated multi-line signatures (e.g. `@KafkaListener` above method) — method name must match extractor output (`consumeAnomaly`, not assumed `onMessage`) |
| **Usages reference display** | File + line range | Reference hits may show class label instead of method name on chunk |
| **README phase numbering** | Content accurate | Older “Retrieval architecture Phase 1–4” section uses different phase numbers than metadata/usages phases |

---

## Finished (recent)

### Reference precision v2 + field extraction

| Area | Status | Notes |
|------|--------|--------|
| Java reference resolver v2 | Done | Scoped types, receiver-typed calls, static imports, omit ambiguous edges |
| Field symbol extraction | Done | `SymbolType.FIELD`, `@Autowired` fields, `java:Class#fieldName` |
| Shell Picocli parity | Done | REPL forwards `--recreate`, `--embed`, etc. |

### Phase 3 — Repository graph

| Area | Status | Notes |
|------|--------|--------|
| Adjacency graph at index time | Done | `SymbolGraphBuilder` from chunk references |
| JSON sidecar store | Done | `~/.andromedia/index/<id>/graph/{forward,reverse,manifest}.json` |
| No graph DB | Done | Lightweight adjacency lists only |

### Phase 4 — `andro impact`

| Area | Status | Notes |
|------|--------|--------|
| `ImpactAnalysisService` | Done | Single-direction traversal per invocation |
| `SymbolGraphTraversal` | Done | BFS with cycle-safe visited set, hop ranking |
| Reverse (incoming) blast radius | Done | Default — flat **Affected symbols** list |
| Forward (outgoing) dependencies | Done | `--forward` / `--dependencies` |
| Category heuristics | Done | `--grouped` for API, Kafka, repository, test, service |
| Category filters | Done | `--category kafka,test` + NL inference in shell |
| NL graph query parser | Done | `ImpactQueryParser` → `GraphQuery` (rules, no LLM traversal) |
| Shell impact handler | Done | Direct service call — no Picocli argv round-trip |
| LLM impact summary | Done | `--explain` / "summarize" — render only, graph-grounded |
| `andro impact` CLI | Done | Same query forms as usages |
| Agent shell routing | Done | blast radius, downstream, kafka consumers, test rerun, etc. |

**Re-index required:** `andro index --recreate .`

---

## Pending (roadmap — not implemented)

### Phase 3 — Repository graph

| Deliverable | Depends on |
|-------------|------------|
| ~~Persistent graph store~~ | **Done** — JSON adjacency sidecar |
| ~~Edges keyed by `symbolId`~~ | **Done** |
| Graph export / query API | Partial — impact traversal only |

### Phase 4 — `andro impact`

| Deliverable | Depends on |
|-------------|------------|
| ~~Impact analysis CLI~~ | **Done** — bidirectional: reverse (default) + `--forward` |
| ~~Confidence / ranking of affected sites~~ | **Done** — hop-distance ranking, cycle-safe BFS |

### Phase 5 — LLM + graph context

| Deliverable | Depends on |
|-------------|------------|
| Impact explanation layer | **Done** — `ImpactExplanationService` (summarize deterministic results) |
| Graph-aware `andro ask` prompts | **Done** — `--graph-context`, deterministic `GraphContextEnricher` |
| LLM JSON intent fallback for impact | **Done** — `SideQueryService` + `ChainedIntentResolver` |
| Project rules loader | **Done** — `ANDROMEDIA.md`, `.andromedia/rules/*.md` |
| Lightweight project memory | **Done** — `~/.andromedia/memory/<projectId>/` |
| Indexed explore for deep ask/plan | **Done** — `ExploreService`, `--deep` |

### Phase 6 — `andro plan`

| Deliverable | Depends on |
|-------------|------------|
| Change-plan generation CLI | **Done** — `PlanCommand`, graph-grounded `PlanGenerationService` |

### Other backlog (not scheduled)

| Item | Notes |
|------|--------|
| ~~Shell: forward full Picocli args for `index`~~ | **Done** |
| ~~Field-level symbol extraction~~ | **Done** |
| Kotlin / Groovy metadata + refs | FILE-level chunks only today |
| IDE-grade overload / generic resolution | Out of scope for v1 regex approach |
| `andro fix` in agent shell | Stub only — routes to “not available” |

---

## Quick reference — CLI commands

| Command | Status |
|---------|--------|
| `andro index [--recreate] [--embed] <path>` | Done |
| `andro search [--semantic \| --hybrid] <query>` | Done |
| `andro ask <question>` | Done |
| `andro usages <symbol>` | Done |
| `andro impact` | Done |
| `andro plan` | Done |
| `andro ask --graph-context` | Done |

---

## Module map (current)

| Module | Responsibility |
|--------|----------------|
| `common` | `CodeChunk`, `CodeSymbol`, `StructuralMetadata`, `ProjectSymbolRegistry`, `symbolId` |
| `ingestion` | Chunking, Java/Python metadata extractors, reference resolvers |
| `indexing` | Lucene write, symbol registry prepass, reference enrichment |
| `search` | BM25, semantic, hybrid, **`UsagesLookupService`** |
| `llm` | Embeddings, Groq chat, RAG prompt assembly |
| `cli` | All commands + agent shell |

---

## What you need to do locally

1. **Rebuild/install** after pulling latest Andromedia:
   ```bash
   cd ~/Downloads/andromedia && ./cli/bin/install-andro
   ```

2. **Re-index each project** after Phase 1/2 upgrades:
   ```bash
   cd ~/Downloads/causr   # or your project
   andro index --recreate .
   ```

3. **Quote `#` in bash** for method queries:
   ```bash
   andro usages 'AnomalyKafkaListener#consumeAnomaly'
   ```
