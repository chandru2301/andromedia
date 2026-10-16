# Zoho retrieval validation (V1)

Run these after indexing a real or sandbox org. Compare answers against enriched chunks vs raw deal-only baseline.

**Verify in this order:** Lucene stored fields → explore `[ZOHO]` detail lines → LLM rewrite. Never trust only the final LLM answer.

## Explore line contract (three buckets)

Each Zoho hit should look like:

```text
- [ZOHO] ERP Implementation @ 2026-07-16 score=...
  Structured: Amount=$850000; Expected_Revenue=—; Probability=—; Close=...; Stage=...
  Flags: stale_record | close_date_passed | missing_account_description
  Contacts: Rajesh Kumar
  Notes: "expected revenue upto 90k" (unverified free-text)
  Narrative: ...
```

- `Structured:` = ground-truth CRM fields (Bucket 2)
- `Notes:` = unverified free-text (Bucket 1 content, labeled)
- `Flags:` = confidence/demo/stale/sparse signals (Bucket 3)

## Sample queries

| # | Query | Expected signal in chunks |
|---|-------|---------------------------|
| 1 | What is the status of the Acme renewal deal? | Stage, discount discussion, account context |
| 2 | Who is the champion on the Softtwig expansion? | Priya, closed won, account description |
| 3 | Which deals are in negotiation stage? | Acme Platform Renewal |
| 4 | What contacts do we have at Acme Corp? | Ravi Kumar, email |
| 5 | Summarize Softtwig account context | Design partner, Slack/Jira note |
| 6 | Which deals have empty descriptions? | Low quality / sparse flags |
| 7 | Any test or demo deals indexed? | Test Deal Demo **indexed** with `Flags: test_demo_pattern` |
| 8 | What is the deal amount for Acme renewal? | `Structured: Amount=120000` (not only narrative) |
| 9 | When was Infosys pilot last modified? | May show `stale_record` / sparse flags when applicable |
| 10 | Full story of Softtwig expansion deal | Unified chunk: deal + account + contact |
| 11 | What was last discussed on the Acme renewal? | `Notes:` / `Activities:` on deal narrative |
| 12 | Any calls related to CRM License Renewal? | Call activity subject/description when present in org |

## Four FDE examples (deal-scoped)

Inspect **`[ZOHO]` source hits**, not the LLM table rewrite.

| Example | Query | Pass criteria on explore line |
|---------|-------|-------------------------------|
| Demo data | Are there sample/demo deals? | `Flags: test_demo_pattern` present; do not present Sample/(Sample) as equal-confidence pipeline |
| Sandbox accounts | King / Chapman / Truhlar deals | With `sandbox-demo-signatures-enabled`, these accounts and all their deals are flagged demo even when deal names look real |
| Next step | What should we do next on CRM License Renewal? | `Next_Step` / `Next step:` from CRM (e.g. Schedule product demo), labeled as CRM-authored |
| Thin pipeline | Most important deal in a demo-heavy org | Eligible = real deals only; answer states demo composition + no high-probability (≥30%) real deals when applicable |
| Amount vs note | What's the deal size for ERP at King? | `Structured: Amount=...` separate from `Notes: "...90k..." (unverified free-text)` |
| Stale deal | What deals are in negotiation? | Past close / old Modified_Time → `close_date_passed` and/or `stale_record` |
| Missing relationship | Who are the contacts on CRM License Renewal? | `Contacts: Rajesh Kumar` plus `missing_account_description` when TechNova has no description |

## Enrichment check (Phase 5)

After a real-org index, confirm relationship enrichment separately from deal-level chunking. Query via `andro_investigate` (or `/api/v1/investigate`) on service `local`.

**Pass queries:**

1. Summarize King / ERP Implementation (account-level contacts).
2. What is the status of the CRM License Renewal deal? (deal-level `Contact_Name`)
3. What was last discussed on Acme / CRM License Renewal? (Notes / Activities)

**Pass criteria:**

- `Account context:` and `Contacts:` appear when Zoho has account description / linked contacts (independent blocks — empty account description must not suppress Contacts).
- Deals with a `Contact_Name` lookup must include that contact even when the contact’s `Account_Name` points at a different account.
- When Notes/Activities exist on a deal in Zoho, `[ZOHO]` narratives include `Notes:` and/or `Activities:` blocks (independent of account description).

| Deal | Account context | Contacts | Notes |
|------|-----------------|----------|-------|
| ERP Implementation | Yes (King) | Kris Marrier, Rajesh Kumar | Account-level join |
| King | Yes | Kris Marrier, Rajesh Kumar | Account-level join |
| Truhlar And Truhlar Attys | Yes | Sage Wieser | Account-level join |
| CRM License Renewal | No (TechNova description empty — OK) | Must include Rajesh Kumar via deal `Contact_Name` | Regression for deal-level contact |

**Bug (fixed):** V1 originally joined contacts only by account id and never fetched deal `Contact_Name`. CRM License Renewal showed Rajesh on the Zoho deal UI but the indexed chunk omitted Contacts — that was an enrichment linking gap, not sparse data. Account description emptiness was incorrectly conflated with missing contacts.

**After re-index:** CRM License Renewal `[ZOHO]` source hit must contain a `Contacts:` block with Rajesh Kumar. If the org has deal Notes/Calls, those blocks should appear too; if the sandbox has none, fixture tests remain the proof.

## Notes / Activities check

Inspect `[ZOHO]` source hits for:

```text
Notes:
- {title}: {content}

Activities:
- [Call|Task|Event] {subject} · {status/time}
```

Contact-only Notes (Parent_Id module ≠ Deals) must **not** appear on deal chunks.

## Immediate follow-up

If the LLM still conflates `Structured: Amount` with note text after labeled sections are correct, add RAG prompt constraints in InvestigationService: treat Structured as ground truth, Notes as unverified, Flags as user-facing.

## Breakpoints to capture for v2

- API rate-limit pressure at org scale
- Quotes / Leads / Emails as further enrichment
- Duplicate account naming (Infosys variants)
- Custom modules worth indexing
- Incremental sync watermark needs
- Per-deal Related Records API if bulk `$se_module` coverage is incomplete
- Explicit InvestigationService prompt rules for Structured / Notes / Flags
