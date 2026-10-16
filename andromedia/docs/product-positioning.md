# Andro: Product, Positioning, and the $19 Value Case

> Product positioning draft — July 2026  
> This document separates what Andro does today from launch hypotheses and future plans.

## The short version

Andro gives AI coding agents the organizational context missing from a repository.

Cursor and Claude Code can read code. They usually cannot explain why the team chose an approach, which incident changed a policy, what the Jira follow-up says, or how that work affects a live CRM account.

Andro indexes those missing sources into one local search layer:

- Source code and symbol relationships
- Git history
- Slack discussions
- Notion documents
- Jira issues and comments
- Linear issues and comments
- Zoho CRM deals, accounts, contacts, notes, and activities

Developers can then retrieve grounded context through the Andro UI, CLI, HTTP API, or MCP tools available directly inside an AI coding agent.

The core promise is:

> Your agent already knows the code. Andro gives it the history, decisions, tickets, and business context behind the code.

## The problem

Engineering knowledge is not missing. It is fragmented.

A single implementation decision may be split across:

- The current behavior in source code
- The original reasoning in a Slack thread
- The architecture proposal in Notion
- The acceptance criteria in Jira
- The regression fix in Git history
- The customer impact in Zoho CRM

AI coding agents are strong when the answer is visible in the current repository. They become generic when the question depends on organizational memory.

That creates predictable failure modes:

- The agent explains how JWT refresh usually works instead of how this team implemented it.
- It proposes a change that reopens a previously resolved incident.
- It cannot connect a code path to the Jira issue that introduced it.
- It answers confidently without showing where the answer came from.
- A developer leaves the editor and manually searches Slack, tickets, docs, and Git.

Andro turns that scattered evidence into searchable, agent-accessible context.

## The best use case

Andro is strongest for a developer or small engineering team that:

- Uses Cursor, Claude Code, or another MCP-capable coding agent
- Has important decisions spread across Git, Slack, Notion, Jira, or Linear
- Wants to deploy the index on infrastructure it controls
- Does not want an enterprise procurement or implementation project
- Needs evidence and source provenance, not only an AI-generated summary
- Values a developer-native CLI and API alongside a graphical interface

The highest-value moment is not a broad company search.

It is a developer asking a specific question while changing code:

> “Why is this authentication timeout 15 minutes?”

Without Andro, the agent can inspect the constant and describe standard security practices.

With Andro, it can retrieve:

- The line of code that defines the timeout
- The commit that changed it
- The Slack incident discussion that motivated the change
- The Notion postmortem
- The Jira follow-up for token rotation

The answer becomes actionable because the evidence is attached.

## What the application includes

### One index across engineering and business sources

Andro writes supported sources into a shared Lucene index organized by service. Related code, discussions, documents, tickets, and CRM records can therefore appear in the same investigation.

Text search works without an embedding provider. Optional vector embeddings add semantic retrieval for hybrid search.

### Source-aware ingestion

Andro does not flatten every source into anonymous text.

- Code remains connected to files and symbols.
- Git commits preserve change history.
- Slack replies are grouped as threads.
- Notion pages are split into meaningful sections.
- Jira and Linear records retain issue metadata and comments.
- Zoho records are enriched into deal-centric contexts with quality and demo signals.

This structure gives ranking and answer generation more useful evidence than a generic document dump.

### The FDE layer: from retrieved records to operational judgment

The FDE layer is a central part of Andro, not an implementation detail of the Zoho connector.

Search products retrieve matching documents. The FDE layer goes further: it converts raw operational records into a trusted, decision-ready evidence model before an LLM is allowed to summarize them.

The current end-to-end FDE path is:

```text
Zoho OAuth and schema discovery
  -> fetch Deals, Accounts, Contacts, Notes, and Activities
  -> resolve deal, account, and contact relationships
  -> score record quality and detect demo/test data
  -> build one unified deal-context record per deal
  -> separate structured facts, narrative, and trust signals
  -> index text fields and optional embeddings
  -> retrieve relevant evidence plus the full active-deal ledger
  -> calculate qualification and pipeline-health facts in Java
  -> ask the LLM to explain those facts without inventing records
```

This creates three explicit evidence buckets:

1. **Structured CRM facts** — stage, amount, probability, close date, owner, account, expected revenue, and CRM-authored `Next_Step`.
2. **Narrative evidence** — descriptions, contacts, notes, and activities. Free-text notes remain visibly labeled as unverified rather than being promoted to authoritative CRM facts.
3. **Quality and trust signals** — demo/test patterns, stale records, passed close dates, sparse records, missing contacts, missing notes, and missing account context.

The distinction matters. If an account note says “deal size may reach 90k” while the structured `Amount` field says 50k, Andro preserves both but does not pretend they are equally authoritative.

The FDE layer also performs deterministic checks outside the model:

- Keeps demo records available as evidence while blocking them from primary recommendations
- Builds a full active-deal ledger for corpus-wide questions
- Ranks and qualifies active deals using configurable probability, stage, and freshness factors
- Calculates demo-heavy and thin-pipeline observations
- Uses a 30% threshold for high-probability pipeline claims
- Cross-checks numeric superlatives such as “largest” or “highest probability”
- Rejects recommendations for deal names that do not exist in the indexed ledger
- Prevents invented `Next_Step` claims by checking the CRM-authored value
- Keeps structured CRM fields separate from unverified notes in the final evidence view

This is what turns Andro from a multi-source search box into an operational intelligence layer. Retrieval finds the evidence; the FDE layer decides what can be trusted, how records relate, and which conclusions are allowed.

### Grounded answers with provenance

Search and investigation results preserve their source type and metadata. The intended user experience is not “trust this generated paragraph.” It is “read the answer, then inspect the evidence.”

### Four ways to use the same context

- **MCP:** Give Cursor or Claude Code access to indexed context while coding.
- **UI:** Configure sources and run investigations.
- **CLI:** Index, search, ask questions, inspect usages, and analyze impact locally.
- **HTTP API:** Integrate Andro into an internal workflow or custom tool.

### Infrastructure controlled by the user

The API, ingestion jobs, and Lucene index run in the user’s deployment. There is no required Andro-hosted data plane that stores the index.

This does not mean no data ever leaves the machine. Connector requests reach the configured source systems, and retrieved snippets used for generated answers are sent to the AI provider selected by the user. The accurate claim is:

> The index stays on infrastructure you control, and you choose the model provider that receives retrieved context.

## Andro is not a smaller Glean or Guru

The strongest positioning does not claim that Andro replaces every enterprise knowledge product.

Glean and Guru solve broader, more mature enterprise problems. Andro takes a narrower developer-first wedge.

### Glean

Glean describes itself as a horizontal enterprise AI platform with search, an assistant, agents, an enterprise graph, governance, and more than 100 application connectors. It is designed for organization-wide deployment and permission-aware access across many departments.

Andro is different:

- It begins with the coding agent, not a company-wide search portal.
- It combines code and symbol context with work-history sources.
- It can be started locally with Docker Compose.
- It exposes context through MCP, a CLI, and a thin API.
- It targets individual developers and small teams before enterprise-wide rollout.

Glean is the better fit when a large company needs broad connectors, mature permission synchronization, centralized governance, and organization-wide agent orchestration.

Andro is the better fit when a developer wants useful context in the coding workflow today without buying or deploying a horizontal enterprise platform.

### Guru

Guru positions itself as an AI-powered knowledge layer with governed knowledge, verification workflows, enterprise search, knowledge agents, more than 100 integrations, and implementation expertise.

Andro is different:

- It does not require teams to create and maintain a second canonical knowledge base.
- It retrieves existing evidence from code, commits, discussions, tickets, and documents.
- It is optimized for engineering investigation rather than company-wide knowledge authoring.
- Its primary destination is the developer’s agent and terminal.
- Its proposed entry price and self-serve deployment avoid a sales-led knowledge-management program.

Guru is the better fit when an organization wants formal knowledge ownership, verification workflows, governance, and a managed knowledge architecture.

Andro is the better fit when the knowledge already exists but is scattered across engineering systems.

## The actual differentiation

### 1. The coding agent is the product surface

Many knowledge products expect the user to open another search or chat application.

Andro is designed to make organizational context callable from the agent already helping write the code. MCP makes the index part of the coding workflow instead of another destination.

### 2. Code and organizational memory live together

Code search products understand repositories. Knowledge products understand workplace documents.

Andro’s wedge is the connection between them:

```text
code behavior
  + commit history
  + team decision
  + implementation ticket
  + customer consequence
  = context an engineering agent can act on
```

### 3. Local-first deployment without a platform rollout

The initial setup can be:

```bash
docker compose up
```

Users can validate one repository and one source before expanding. There is no requirement to onboard an entire company or negotiate an enterprise contract to learn whether the product is useful.

### 4. Evidence is visible

Andro should win trust by showing:

- Which source produced a claim
- Which file or thread is relevant
- Which CRM field is authoritative
- Which text is unverified narrative
- Which quality flags affected ranking

The product should make unsupported confidence look obviously weaker than grounded evidence.

### 5. Developer-native control

Developers can use the UI, but they are not limited to it. The CLI, API, Lucene index, Docker deployment, and MCP interface provide control that a technical buyer can inspect and automate.

### 6. A focused connector set

Andro does not compete on hundreds of connectors today. It competes on a small set that forms a useful engineering context graph:

- Git and source code
- Slack
- Notion
- Jira
- Linear
- Zoho CRM

Depth and cross-source usefulness matter more than publishing a large connector count.

### 7. The FDE layer produces decisions, not only matches

Guru and Glean are strong at finding governed company knowledge across broad connector catalogs. Andro’s FDE differentiation is the domain-specific layer between retrieval and generation.

For CRM investigations, Andro does not merely return the five chunks most similar to “best deal.” It can inspect the full active-deal ledger, exclude demo records from recommendations, compare structured qualification factors, expose pipeline-health facts, and then constrain the generated explanation to records that actually exist.

That produces answers to operational questions:

- Which active deal is most qualified, and why?
- Is the apparent pipeline mostly demo data?
- Are there any real high-probability deals?
- Which close dates have passed?
- What CRM-authored next step should happen now?
- Is a claimed deal amount structured data or only an unverified note?
- Which account or contact relationship is missing?

The same pattern can later support additional domain-specific FDE layers. The durable product idea is:

```text
source connectors
  -> shared retrieval foundation
  -> domain-specific evidence model
  -> deterministic checks
  -> constrained explanation
```

## Why someone would pay $19 per month

The proposed Pro price is not justified by “AI search” as a category. Search is available in many existing products.

The value is avoiding one expensive context-reconstruction loop and receiving a checked conclusion instead of another list of search results.

### The developer-time argument

A developer commonly spends time:

1. Searching a symbol in the repository
2. Running `git blame` and reading old commits
3. Searching Slack with several keyword variations
4. Opening a Jira issue and its linked documents
5. Asking a teammate because the evidence is still unclear
6. Reconstructing the answer for the coding agent

If Andro avoids even one 20–30 minute investigation per month, the proposed $19 price can be lower than the cost of the time recovered.

This is a value hypothesis, not a guaranteed savings claim. It should be validated with product analytics and user interviews before becoming marketing copy.

### What Pro must make meaningfully better

A user should pay for Pro because it provides a complete, repeatable workflow—not because the free version is artificially broken.

The current pricing hypothesis is:

**Free**

- One repository
- Limited Git and Slack indexing
- BM25 text search
- Core MCP access
- Enough capability to prove the product on a real question

**Pro — proposed at $19/month**

- All implemented connectors
- Multiple or unlimited repositories, subject to sustainable limits
- Hybrid BM25 and vector search
- Full MCP tool set
- Cross-source investigation
- More generous indexing and history limits

These entitlements are not fully implemented as billing enforcement today. The landing page correctly treats pricing as launch-gated until billing, limits, and account management exist.

### The emotional value

The strongest purchase motivation is not “I can search six sources.”

It is:

> I can ask my coding agent why this code exists and receive an answer I can verify.

For a technical founder, sales engineer, or forward-deployed engineer working across product and customer data, it is also:

> I can ask which customer or deal needs attention and see exactly which structured facts, relationships, and quality signals produced the answer.

That reduces uncertainty during:

- Onboarding
- Incident response
- Legacy-code changes
- Architecture work
- Security-sensitive changes
- Customer escalation fixes
- Handoffs after an engineer leaves

### Why $19 is plausible

The proposed price is:

- Low enough for an individual developer to purchase without enterprise approval
- High enough to signal a serious professional tool
- Easier to evaluate than a custom sales quote
- Comparable to the monthly cost of other developer productivity subscriptions
- Justifiable if it saves one meaningful investigation

The price should stay simple. Model-provider usage is currently configured separately by the user, so Andro should be explicit that a Groq or OpenRouter account may create additional provider costs.

## Why users would choose Andro instead of doing nothing

The main competitor is not Glean or Guru. It is the existing manual workflow.

Users will continue searching tools manually unless Andro demonstrates all of the following:

- Setup takes minutes, not days.
- The first answer uses the user’s real evidence.
- Sources are visible and trustworthy.
- Results are better inside the coding agent than in another browser tab.
- The index does not require handing all company data to a new hosted vendor.
- Re-indexing and connector maintenance do not become a second job.

The landing page’s “Without Andro / With Andro” proof is therefore more important than a feature list. It must use real screenshots from the same question and workspace.

## Who should not buy Andro yet

Andro is not currently the right product for organizations that require:

- Enterprise SSO and application-level authorization
- Permission synchronization for every indexed document
- A managed SaaS deployment with contractual SLAs
- Hundreds of connectors
- Formal knowledge verification and ownership workflows
- Durable distributed job orchestration
- Real-time sync and deletion reconciliation across every source
- A credential vault and complete audit trail
- Large-company governance and analytics

Being explicit about these gaps improves trust and keeps early users aligned with the product that exists.

## Current product boundaries

The following should not be hidden in sales or marketing material:

- Runtime connector job state and submitted credentials are currently process-local.
- The API does not yet provide application-level authentication or authorization.
- Zoho synchronization is a functional V1 but still needs durable incremental sync, deletion reconciliation, broader regions, and production credential storage.
- The connector catalog is focused rather than broad.
- Chat answers rely on a configured external model provider.
- Pricing, billing, account limits, and entitlements are not live yet.

These are roadmap items, not reasons to dilute the current value proposition.

## Recommended positioning

### Category

**Local-first context and FDE intelligence layer for AI agents**

For developer-led acquisition, the shorter category remains **local-first context layer for AI coding agents**. The FDE layer should then be introduced as the reason Andro can answer operational questions instead of merely retrieving documents.

### One-line description

> Andro gives AI agents the evidence behind your code and customer operations, then uses its FDE layer to turn that evidence into checked, source-backed answers.

### Hero message

> Your AI agent knows the code.  
> Not the why.

### Supporting message

> Index Git, Slack, Notion, Jira, Linear, and Zoho on infrastructure you control. Ask grounded questions from Cursor or Claude Code through MCP.

### Primary proof

Show the same engineering question in Cursor:

- Without Andro: generic best-practice answer
- With Andro: project-specific answer with a file, commit, Slack thread, document, and ticket

### Primary call to action

> Run Andro locally

The immediate action should lead to a working setup, not a generic signup form.

## Messaging principles

### Say this

- “The index stays on infrastructure you control.”
- “Connect your configured model provider.”
- “Answers include source provenance.”
- “The FDE layer separates structured facts from narrative and trust signals.”
- “Deterministic checks constrain which CRM conclusions the model can make.”
- “Built for Cursor, Claude Code, CLI, and API workflows.”
- “Start with one repository and one source.”
- “Cross-reference code, decisions, docs, tickets, and CRM.”

### Do not say this

- “No data ever leaves your machine.”
- “Replaces Glean or Guru.”
- “Enterprise-ready” before security and governance hardening.
- “Understands everything your company knows.”
- “Eliminates hallucinations.”
- “Unlimited” before storage, model, and ingestion limits are defined.
- “Real-time” while connectors rely on explicit ingestion jobs.

## How to validate the $19 hypothesis

Before activating billing, test the price with a small developer cohort.

Measure:

- Time from installation to first indexed answer
- Percentage of users who connect a second source
- Questions asked through MCP per active week
- Percentage of answers where a cited source is opened
- Repeat usage after seven and thirty days
- Investigations users say they would otherwise perform manually
- Upgrade intent after encountering a real Free limit

Interview users after a successful investigation:

1. What would you have done without Andro?
2. How long would that have taken?
3. Which source made the answer trustworthy?
4. Would you personally pay $19 each month for this workflow?
5. Which missing capability prevents regular use?

Do not optimize conversion by making the free product frustrating. Optimize for a fast “this answer knew why” moment, then charge for breadth, scale, and repeated use.

## Competitive source notes

Competitor descriptions in this document were checked against public materials in July 2026:

- [Glean product overview](https://www.glean.com/product/overview)
- [Glean enterprise AI overview](https://www.glean.com/enterprise-ai-software)
- [Glean Enterprise Flex pricing documentation](https://docs.glean.com/glean-enterprise-flex-pricing)
- [Guru product and pricing overview](https://www.getguru.com/pricing)

Glean and Guru products evolve quickly. Review their official materials before publishing direct comparison copy. Avoid publishing unverified competitor prices or implying missing capabilities without current evidence.

## Final position

Andro should not try to look like the smallest enterprise knowledge platform.

Its best position is sharper:

> A developer-native, local-first context and FDE intelligence layer that lets agents answer “why” and “what needs attention” using real code history, decisions, documents, tickets, structured CRM facts, and explicit trust signals.

At a proposed $19 per month, the purchase case is simple:

> Save one manual context hunt, avoid one uninformed code change, and keep the supporting evidence inside the workflow where the change is being made.

