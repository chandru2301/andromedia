# Reference Retrieval Architecture — Andro Stage Benchmark

**Date:** 2026-07-25  
**Purpose:** Rank Andro’s current query→evidence pipeline against a reference multi-source RAG design (CRM + Slack + Notion + Jira + Linear). This is a **gap analysis and remediation order**, not a rewrite mandate.

**Evidence basis:** code inspection (`InvestigationService`, `HybridSearchService`, `SourceCoverageMerger`, `LexicalAnchorFilter`) plus the Jul 25 Andro MCP / OpenRouter eval suite (P1–P6).

---

## Reference design (summary)

| Stage | Intent |
|---|---|
| **0** | Classify query **shape** before any retrieval (enumeration / aggregate / named-entity / reasoning / conflict). |
| **1a** | Enumeration & aggregate → full-dataset structured query; never top‑k semantic. |
| **1b** | Named-entity & conflict → fuzzy normalize → **direct lookup first**, semantic supplementary → truncate **last**; coverage per **(source, entity)**. |
| **1c** | Reasoning/ranking → structured filter over all eligible records → feed signals to LLM → **sort in code**. |
| **2** | Evidence assembly: dedupe by entity, provenance on every chunk, **deterministic conflict flag** before synthesis. |
| **3** | Synthesis guardrails + multi-turn carryover of established conclusions. |

---

## Scoring rubric

| Mark | Meaning |
|---|---|
| ✅ | Have it, works correctly |
| ⚠️ | Have it, but ordering / granularity / coverage is wrong |
| ❌ | Missing entirely |

---

## Stage-by-stage scorecard

| Stage | Status | Andro today | Gap vs reference |
|---|---|---|---|
| **0 — Query classification** | ⚠️ | LLM `classifyQuery` emits shape intents: `enumerate`, `aggregate`, `corpus_staleness`, `named_entity_staleness`, plus `rank` / `qualify` / `lookup` / `reason` / `general`. Jul 25 P4 paraphrases (“how many real accounts”, “total revenue in pipeline”) hit deterministic paths. | No dedicated **conflict/reconciliation** shape. Named-entity **value/status** questions usually land in `lookup`/`general` and still run hybrid explore first. Classification is intent-based (good), but routing after classify does not yet match Stage 1b. |
| **1a — Enumeration / aggregate bypass** | ✅ | Structured ledger path: `answerEnumerate` / `answerAggregate` with eligible + excluded demo counts (`PipelineHealthFacts`). Demo exclusion at ledger split, not post-hoc LLM filtering. | Minor: “count of open deals” reused the pipeline-value template wording (count present but buried). No SQL store — Lucene ledger is the source of truth (acceptable equivalent). |
| **1b — Entity-anchored direct lookup before semantic** | ❌ | Named-entity **staleness** uses `resolveNamedEntity` on the Zoho ledger. General lookup / “deal value” / “summarize across sources” use **hybrid top‑k explore only**. | **Al Habtoor root cause.** CRM Amount=310000 is retrievable (often ~rank 4 in top‑15) but missing from investigate top‑5 for *“current deal value for Al Habtoor?”* → answer locks onto Notion $450K. Need ledger (+ cross-source direct) pin **before** semantic, for all named-entity shapes — not only staleness. |
| **1b — Fuzzy entity normalization** | ⚠️ | `normalizeEntityToken` + contains/token match on ledger for `named_entity_staleness` (Sobha/Damac-style questions work). Hybrid path uses `QueryLexicalMatch` (stopwords + relative BM25 strength) — not canonical entity keys / suffix stripping across sources. | “Any update on Meraas?” failed (Closed Won / eligibility + no direct lookup). Notion section titles vs Zoho deal names do not share a canonical key. |
| **1b — Truncate last, not first** | ⚠️ | `HybridSearchService` fuses/repairs on `fusePool` (`min(max(maxResults*4, 25), candidatePool)`), then `SourceCoverageMerger` → `RelativeScoreFilter` → `LexicalAnchorFilter`, then `limit(maxResults)`. **Better than early RRF-at-5.** | Still not “entity-guaranteed.” Intermediate pool + relative filters + final `maxResults=5` can drop the anchored CRM hit. Eval proves truncate-last alone is insufficient without Stage 1b direct lookup. |
| **1b — Coverage per (source, entity)** | ❌ | `SourceCoverageMerger`: Pass 1 = missing **source type** from semantic; Pass 2 = at most **one** query-aligned BM25 inject per source type already “covered.” Coverage set is still source-type keyed. | Wrong Zoho (e.g. Chapman/Benton/HDFC) “covers” ZOHO → Habtoor is optional inject, not required. Reference wants coverage keyed by **(sourceType, canonicalEntity)**. |
| **1c — Structured signals + code sort** | ⚠️ | Intelligence path: full eligible ledger, `PipelineHealth`, code `sortEligibleForQuestion`, prompt “Ordered ledger”, post-guards repair `primaryRecords` / probability-order prose. | Jul 25 “rank accounts healthiest→risk” still produced prose ranking mixed with deal-level signals; not a clean code-sorted account health list. Sort repair helps qualify/rank deal lists more than free-form account ranking. |
| **2 — Deterministic conflict pre-detection** | ⚠️ | `conflictDetail()` parses amounts across hits for **progress UI** (“Found $X in Zoho, $Y in Notion…”). Multi-source general prompt asks the model to reconcile. | Conflict is **not** a hard `CONFLICT DETECTED: field=amount, values=[…]` marker forced into synthesis for every named-entity path. Entity key is raw `fileName` (Notion section ≠ Zoho deal name → miss). Explains Q3 working when both chunks happen to land in top‑k, and failing when CRM is dropped. |
| **3 — Multi-turn context carryover** | ❌ | `InvestigateRequest` = `{service, query, context, sourceTypes}` — no conversation id / prior conclusions. Each turn re-retrieves cold. | P1 follow-up “which number should I tell the client?” and P5 “why did the price change?” / “should I deprioritize them?” lost entity context; Slack noise (incl. Razorpay) entered the new top‑k. **Do not ship “skip re-retrieval when interpretive” without an explicit gate** (see remediation §6) — a wrong skip trades missing context for stale context. |

---

## Likely scorecard (confirmed)

| Stage | Status |
|---|---|
| 0 — Query classification | ⚠️ exists; shape-based for enum/agg/staleness; missing conflict shape + wrong route for named-entity value |
| 1a — Enumeration/aggregate bypass | ✅ works (ledger SUM/COUNT + demo denominator) |
| 1b — Entity-anchored direct lookup before semantic | ❌ missing — Al Habtoor root cause |
| 1b — Fuzzy entity normalization | ⚠️ ledger-only for staleness; not hybrid / cross-source |
| 1b — Truncate last, not first | ⚠️ partially fixed in hybrid; still drops entity at final top‑k |
| 1b — Coverage per (source, entity) | ❌ still per source-type (+ one aligned inject) |
| 1c — Structured signals + code sort | ⚠️ ledger + sort repair; free-form rank still soft |
| 2 — Deterministic conflict pre-detection | ⚠️ progress heuristic only; not synthesis-hard |
| 3 — Multi-turn context carryover | ❌ confirmed |

---

## Jul 25 eval → stage mapping

| Failure / win | Maps to |
|---|---|
| Habtoor “deal value” ×3 → $450K only; CRM not in top‑5 | 1b direct lookup ❌, coverage (source,entity) ❌, truncate-last ⚠️ |
| Same entity “What’s happening with Al Habtoor?” → $310K + $450K | Shows retrieval is query-fragile; not entity-anchored |
| Habtoor conflict when query names CRM vs Notion | 2 ⚠️ — works when both hits survive top‑k |
| Follow-ups lose thread / Razorpay Slack noise | 3 ❌ |
| Enumerate / pipeline total deterministic | 1a ✅ |
| Meraas fuzzy miss | 1b fuzzy ⚠️ + Closed Won eligibility |
| Al Maha → “not found” then narrates Habtoor | Stage 3 integrity + no entity lock |
| Rank accounts soft / deal-mixed | 1c ⚠️ |

---

## Remediation order (concrete)

Do these in order; each stage unblocks the next failure class.

### Sequencing note (confirmed before implementation)

- **Shared `canonicalEntityKey()` is a prerequisite for entity pin and (source, entity) coverage — not polish at the end.** Item 1 below (direct pin) needs one key that matches Zoho deal/account names, Notion section titles, and Slack message text. Item 2’s coverage pair is the same key. Shipping pin/coverage before a unified normalizer reintroduces “Notion section ≠ Zoho deal name” misses under a new API.
- **Multi-turn “skip re-retrieval when purely interpretive” is itself a classification problem.** A wrong skip reuses stale bag context when fresh evidence was needed (Razorpay-class bug inverted: wrong context kept instead of missing). Define the interpretive gate **before** that path ships; default must be **retrieve again + merge bag**, not skip.

### Ordered work

1. **Shared `canonicalEntityKey()` (prerequisite)**  
   One normalizer used by ledger resolve, hybrid inject, conflict grouping, and cross-source pin: lowercase; strip articles (`the`/`a`/`an`); strip common org/deal suffixes (`Group`, `Holding`, `Properties`, `Realty`, `Limited`, etc.); strip punctuation/dashes; collapse whitespace. Same function must map `Al Habtoor`, `Al Habtoor Group`, and Notion section `Al Habtoor – Phase 2 Expansion` onto a shared stem (deal vs account disambiguation can be a secondary key, but the stem must match). Expand `normalizeEntityToken` / `resolveNamedEntity` into this shared library — do not keep a ledger-only and a hybrid-only variant.

2. **Stage 1b — Direct entity pin (P0)**  
   After classify as named-entity / conflict / lookup-with-entity: resolve **canonical key** → load Zoho ledger row(s) + Notion/Slack/Jira/Linear chunks whose titles/body match that key → **force-include** in evidence → then run hybrid as supplementary only. Depends on (1).

3. **Stage 1b — Coverage key `(sourceType, canonicalEntity)`**  
   Replace “ZOHO already present” with “ZOHO + `canonicalEntityKey` already present.” Reserve slots for pinned entity hits before `maxResults` truncate. Depends on (1).

4. **Stage 0 — Add `conflict` / ensure named-entity value routes to 1b**  
   “Which number should I tell the client?” and “CRM vs Notion” must not be cold hybrid-only.

5. **Stage 2 — Hard conflict marker**  
   If pinned evidence has divergent Amount/Stage for the same canonical entity, inject structured conflict block into the synthesis prompt every time (grouped by `canonicalEntityKey`, not raw `fileName`).

6. **Stage 3 — Multi-turn bag (with interpretive gate defined first)**  
   Persist prior turn’s canonical entity, conflict conclusion, and trusted field values (amount/stage + source + timestamp).  
   **Default:** always re-retrieve, then **merge** prior bag into evidence (pin prior entity hits; do not replace retrieval with the bag alone).  
   **Optional skip of re-retrieval** only when the follow-up is classified **purely interpretive** — all of the following must hold:

   | Gate | Purely interpretive if… | Fresh retrieval required if… |
   |---|---|---|
   | Entity | No new named entity (deal/account/person) vs prior turn’s canonical key; pronouns/`them`/`it`/`the client` resolve to that same key | Names a different or additional entity |
   | Freshness | Does not ask for updated/latest/current/now/changed-since / “any new” / “did X update” | Asks for latest state, new activity, or whether something changed in the sources |
   | Scope | Asks only to interpret, choose, explain, or decide from **already established** conclusions (e.g. “which number should I tell the client?”, “why did the price change?” when prior turn already surfaced both amounts + conflict) | Asks for new fields, lists, ranks, aggregates, or cross-entity comparison not in the bag |
   | Ambiguity | Classifier confidence high that the above hold | Any gate fails or confidence is low → **retrieve + merge** (never skip) |

   Shipping rule: if the interpretive classifier is not implemented or is uncertain, **do not skip retrieval**. Prefer the Razorpay-avoidance path (retrieve with entity pin from bag) over stale-only answers.

7. **Stage 1c polish**  
   Account-level health ranking: compute sort keys in code; pass already-ordered list; ban free-text reorder.

---

## What not to rip out

Already aligned with the reference and worth keeping:

- Shape intents for enumerate / aggregate / staleness + deterministic ledger answers with excluded counts.
- Hybrid fuse-pool repair (`SourceCoverageMerger`, `LexicalAnchorFilter`, relative floors) — keep as **supplementary** channel once entity pin exists.
- Intelligence ledger prompts, demo exclusion, sort-repair guards, integrity skill rules.
- Progress events (classify → search → conflicts → demos → compose).

---

## Code anchors

| Concern | Location |
|---|---|
| Classify + structured route | `InvestigationService.classifyQuery`, `shouldUseStructuredCrmPath`, `tryDeterministicStructuredAnswer` |
| Hybrid fuse / truncate-last | `HybridSearchService.search` |
| Source-type coverage | `SourceCoverageMerger` |
| Lexical promote/drop | `LexicalAnchorFilter`, `QueryLexicalMatch` |
| Ledger entity resolve (staleness) | `InvestigationService.resolveNamedEntity` |
| Conflict progress only | `InvestigationService.conflictDetail` |
| Integrity rules | `docs/andro-investigate-integrity-skill.md`, `investigate/integrity-rules.md` |

---

## Implementation status (2026-07-25)

**Track A shipped**
- Shared [`CanonicalEntityKey`](../search/src/main/java/com/andromedia/search/CanonicalEntityKey.java)
- Named-entity pin for `lookup` / `named_entity_staleness` / prior-bag entity (ledger incl. closed + related Notion/Slack)
- Coverage per `(sourceType, canonicalEntity)` in `SourceCoverageMerger`
- Hard `CONFLICT DETECTED` marker in synthesis prompts

**Track B v1 shipped (no skip-retrieval)**
- `metadata.conversationBag` on investigate responses
- Request `context.conversationBag` merged on follow-ups; always re-retrieve + pin prior entity
- Landing investigate UI passes the bag between turns

Restart core after deploy so investigate loads the new path.

---

## Reference architecture (full text retained for benchmarking)

### Stage 0 — Query classification (before any retrieval)

Classify into: **Enumeration**, **Aggregate**, **Named-entity lookup**, **Reasoning/ranking**, **Conflict/reconciliation**. Intent-based (classifier / embedding), not keyword lists. Paraphrases of the same shape must classify identically.

### Stage 1a — Enumeration / Aggregate path

Bypass semantic top‑k. Structured query over full eligible dataset; demo exclusion at query level. Always return eligible count **and** excluded count.

### Stage 1b — Named-entity / Conflict path

1. Fuzzy entity extraction / normalization.  
2. Direct lookup across all source types first.  
3. Semantic search supplementary only.  
4. Truncate to `maxResults` **last**, after entity-anchored hits are guaranteed.  
5. Coverage per `(source, entity)`, not source-type alone.

### Stage 1c — Reasoning / ranking path

Structured filter over all eligible records with risk/qualify signals; feed structured fields to the LLM; **sort in code**.

### Stage 2 — Evidence assembly

Dedupe by canonical entity (keep multi-source); provenance on every chunk; deterministic conflict flag before LLM.

### Stage 3 — Answer synthesis guardrails

Integrity rules (no untraceable entities, answer↔reasoning consistency, partial labeling) + multi-turn reuse of prior conclusions (Rule 10).
