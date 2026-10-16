package com.andromedia.core.investigate;

import com.andromedia.common.IndexConstants;
import com.andromedia.common.IndexPathHolder;
import com.andromedia.core.index.ActiveZohoIndexStore;
import com.andromedia.llm.ChatCompletionService;
import com.andromedia.llm.ChatException;
import com.andromedia.llm.ChatProgressListener;
import com.andromedia.llm.RagPrompt;
import com.andromedia.search.ArtifactReference;
import com.andromedia.search.ArtifactReferenceDetector;
import com.andromedia.search.CanonicalEntityKey;
import com.andromedia.search.ExploreService;
import com.andromedia.search.IndexAccess;
import com.andromedia.search.SearchHit;
import com.andromedia.search.SearchResult;
import com.andromedia.search.ZohoHitMetadata;
import com.andromedia.search.ZohoQualificationService;
import com.andromedia.search.ZohoQualificationService.DealLedgerEntry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class InvestigationService {

  private static final Logger log = LoggerFactory.getLogger(InvestigationService.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  /** Pulls the "summary" string out of a JSON envelope even when the tail was truncated. */
  private static final Pattern PARTIAL_JSON_SUMMARY =
      Pattern.compile("\"summary\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)");

  private static final Pattern JSON_BLOCK =
      Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
  static final double CLASSIFICATION_CONFIDENCE_THRESHOLD = 0.70;
  static final String INTELLIGENCE_MODEL_ID = "zoho-crm-intelligence-v1";

  /** Answer came from a single structured CRM record. */
  static final String TRUST_STRUCTURED = "structured";
  /** The question genuinely could not be answered from indexed evidence. */
  static final String TRUST_INSUFFICIENT = "insufficient_data";
  /**
   * Sources disagreed on a field and the answer surfaced every value with its source (integrity
   * Rule 7). This is an ANSWERED state — the older model reported it as insufficient_data, which
   * put a "no data" badge on a fully-grounded cross-source answer.
   */
  static final String TRUST_CONFLICT_ADJUDICATED = "conflict_adjudicated";
  /**
   * Answer is bound to evidence that was verified by id or exact title (a ticket key, a document
   * title) rather than selected out of a single CRM record.
   */
  static final String TRUST_GROUNDED = "grounded";

  /** Hits pulled per named artifact before id/title verification. */
  static final int ARTIFACT_PIN_SEARCH_LIMIT = 25;
  /** Verified rows kept per named artifact. */
  static final int ARTIFACT_PIN_MAX_PER_REFERENCE = 3;
  static final String DEMO_FLAG = "test_demo_pattern";
  static final String ACCOUNT_MENTION_UNCONFIRMED_DEAL_FLAG =
      "possible_account_mention_unconfirmed_deal";
  /**
   * Slack/Chat risk language that conflicts with an open CRM stage (Bright Horizon / Fatima story).
   */
  static final Pattern RISK_STATUS_LANGUAGE =
      Pattern.compile(
          "(?i)\\b(stalled|at[- ]risk|went quiet|going quiet|no response|blocked|ghosted|stuck|radio silence)\\b");
  /**
   * SaaS metrics that are not present as indexed Zoho fields. Asking for these must never fall
   * through to open-pipeline aggregate (pipeline ≠ ARR; churn is not in the corpus).
   */
  static final Pattern UNSUPPORTED_INDEXED_METRIC =
      Pattern.compile(
          "(?i)\\b(churn(?:\\s+rate)?|ARR|MRR|NRR|net\\s+retention|annual\\s+recurring\\s+revenue|monthly\\s+recurring\\s+revenue)\\b");
  /** Scoped second search after a CRM pin (Phase 1.2B) — never demotes Google Chat globally. */
  static final Set<String> CRM_CONFLICT_SOURCE_TYPES =
      Set.of(
          IndexConstants.SOURCE_TYPE_ZOHO,
          IndexConstants.SOURCE_TYPE_SLACK,
          IndexConstants.SOURCE_TYPE_NOTION);
  /** Probability at or above this threshold counts as high-probability for pipeline-health facts. */
  static final double HIGH_PROBABILITY_THRESHOLD = 30.0;
  /** Aligns with andromedia.zoho.qualification.stale-after-days default. */
  static final int STALE_AFTER_DAYS = 180;
  /** Max chars of note text appended per deal in the intelligence ledger prompt. */
  static final int LEDGER_NOTES_MAX_CHARS = 400;

  private static final Set<String> STRUCTURED_CRM_INTENTS =
      Set.of(
          "rank",
          "qualify",
          "reason",
          "enumerate",
          "aggregate",
          "corpus_staleness",
          "named_entity_staleness");

  /** Intents that typically carry a named CRM entity for Track A pin. */
  @SuppressWarnings("unused")
  private static final Set<String> NAMED_ENTITY_PIN_INTENTS =
      Set.of("lookup", "named_entity_staleness");

  private static final Set<String> DETERMINISTIC_INTENTS =
      Set.of("enumerate", "aggregate", "corpus_staleness", "named_entity_staleness");

  private static final Set<String> CLASSIFIER_INTENTS =
      Set.of(
          "rank",
          "lookup",
          "qualify",
          "reason",
          "general",
          "enumerate",
          "aggregate",
          "corpus_staleness",
          "named_entity_staleness");

  /** Non-Zoho source types checked for live progress, in the order they are reported. */
  private static final List<String> SECONDARY_SOURCE_ORDER =
      List.of(
          IndexConstants.SOURCE_TYPE_SLACK,
          IndexConstants.SOURCE_TYPE_NOTION,
          IndexConstants.SOURCE_TYPE_JIRA,
          IndexConstants.SOURCE_TYPE_LINEAR,
          IndexConstants.SOURCE_TYPE_GIT,
          IndexConstants.SOURCE_TYPE_CODE);

  /** ISO dates and datetimes that must not be parsed as money (year 2026 in 2026-08-17). */
  private static final Pattern ISO_DATE_OR_TIMESTAMP =
      Pattern.compile(
          "\\b(?:19|20)\\d{2}-\\d{2}-\\d{2}(?:[T\\s]\\d{2}:\\d{2}(?::\\d{2})?(?:\\.\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?)?\\b");

  /**
   * Currency-like amounts only: optional code/$ , thousands separators, and/or K/M suffix. Bare
   * 4-digit years are rejected after date stripping.
   */
  private static final Pattern MONEY_PATTERN =
      Pattern.compile(
          "(?i)(?:(?<code>usd|aed|inr|eur|gbp|rs\\.?)\\s*)?(?<sym>\\$)?\\s*(?<num>\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?(?:\\s*(?<suffix>[km])\\b)?");

  static final String INTELLIGENCE_SYSTEM_PROMPT =
      """
      You are a CRM intelligence layer. You receive the user's question, deterministic pipeline-health \
      facts, and a complete structured deal ledger of NON-DEMO active deals (including Flags and any \
      Notes snippets). Demo/sample deals are already excluded from the eligible pool.

      Your job:
      - Understand what the question asks for in a sales context
      - Reason ONLY over the Eligible ledger, PipelineHealth, and Notes snippets in the user message
      - Never invent deals or fields that are not in the ledger
      - Explain reasoning transparently using structured fields
      - Use the provided PipelineHealth facts for aggregate claims — do not invent counts or thresholds

      Evidence-bound refuse rules (fail closed — same spirit as code RAG):
      - Answer using ONLY the provided ledger / PipelineHealth / Notes. Do not use world knowledge \
        (stock tickers, company aliases, industry boilerplate, or plausible sales activity).
      - If those sources do not contain enough to answer (entity identity, note/activity content, \
        contact on a named deal, etc.), return empty primaryRecords and state clearly in summary \
        that the indexed CRM evidence is insufficient — do not guess.
      - If Flags include no_notes or no_activities, never invent note or activity content; say \
        notes/activities are missing in CRM.
      - Notes are unverified free-text; cite them only when present on that deal's ledger line.
      - Entity / same-company questions: only assert identity if both account names appear in the \
        ledger with supporting CRM fields; otherwise say insufficient.
      - Contact questions: only cite a contact if it appears on that deal's ledger Contacts/Notes \
        line; never borrow a contact from another deal.

      Reasoning guidance (adapt to the question; do not treat as rigid formulas):
      - Eligible deals are code-sorted for this question (qualification / risk). Narrate that \
        order exactly — never reorder primaryRecords relative to the Ordered ledger.
      - "Most important" / prioritize: close likelihood, pipeline proximity to close, and value
      - "At risk" / stale: close date passed or approaching + low recent activity (Modified)
      - "Follow up this week" / "which deal this week": prefer Close Date within the next 7 days, \
        high Probability, Next Step present; demote no_notes / stale_record / overdue closes \
        unless no better eligible option. Prefer structured fields over Notes free-text.
      - "Follow up next month" / longer horizon: allow farther close dates; still prefer higher \
        probability and actionable Next Step
      - "What do we not know" / gaps: missing fields, no contacts, no description, no notes, stale
      - Explicit "highest/lowest X": pick the true extreme of that field from the ledger
      - Never invent contacts or activities; never invent Next Step values
      Pipeline health rules:
      - Demo-heavy sparse pipeline (few eligible deals, many demos): state the true composition, \
        name the real deal(s), list grounded gaps from Flags, surface CRM-authored "Next Step" \
        when present, and recommend adding real CRM opportunities before relying on pipeline \
        intelligence. Do not invent importance rankings over a single early-stage low-probability deal.
      - Thin pipeline (highProbabilityDealCount is 0 / every real deal below 30%%): explicitly state \
        that there are no high-probability real deals and that the pipeline needs development.
      - Label CRM-authored Next Step as a CRM fact. Label any sales coaching (e.g. move stage) as \
        "Recommendation" — never claim a stage transition is recorded CRM data.

      Rules:
      - primaryRecords must be exact deal names from the Eligible ledger only (or empty when insufficient)
      - Prefer multiple primaryRecords when several deals share the same signal (e.g. same close date)
      - peers lists other ledger deals sharing the same signal
      - Never recommend demo/sample deals (they are not in the eligible pool)
      - Never invent a Next Step value that is not on the selected eligible ledger record
      - Respond with ONLY valid JSON
      """;

  private final ExploreService exploreService;
  private final ChatCompletionService chatCompletionService;
  private final ZohoQualificationService zohoQualificationService;
  private final ActiveZohoIndexStore activeZohoIndexStore;

  public InvestigationService(
      ExploreService exploreService,
      ChatCompletionService chatCompletionService,
      ZohoQualificationService zohoQualificationService,
      ActiveZohoIndexStore activeZohoIndexStore) {
    this.exploreService = exploreService;
    this.chatCompletionService = chatCompletionService;
    this.zohoQualificationService = zohoQualificationService;
    this.activeZohoIndexStore = activeZohoIndexStore;
  }

  public InvestigateResponse investigate(String service, String query, Map<String, Object> context) {
    return investigate(service, query, context, InvestigationProgressSink.NOOP, List.of());
  }

  public InvestigateResponse investigate(
      String service, String query, Map<String, Object> context, InvestigationProgressSink sink) {
    return investigate(service, query, context, sink, List.of());
  }

  /**
   * Same as {@link #investigate(String, String, Map, InvestigationProgressSink)} but optionally
   * restricts hybrid evidence to the given source types (e.g. {@code ZOHO} from MCP).
   */
  public InvestigateResponse investigate(
      String service,
      String query,
      Map<String, Object> context,
      InvestigationProgressSink sink,
      List<String> sourceTypes) {
    InvestigationProgressSink progress = sink == null ? InvestigationProgressSink.NOOP : sink;
    Set<String> sourceFilter = normalizeSourceTypeFilter(sourceTypes);
    List<Path> indexPaths = activeZohoIndexStore.resolveIndexPaths(service);
    Path crmIndexPath = activeZohoIndexStore.resolveIndexPath(service);
    IndexAccess.validateAnyIndexExists(indexPaths);
    IndexPathHolder.setDirectIndexPaths(indexPaths);
    try {
      if (asksUnsupportedIndexedMetric(query)) {
        progress.emit(
            InvestigationProgressEvent.progress(
                "compose", "running", "Checking whether that metric exists in indexed CRM…"));
        InvestigationAnswer refused = answerUnsupportedIndexedMetric(query);
        progress.emit(
            InvestigationProgressEvent.progress(
                "compose", "done", "Finished writing the answer from the evidence above."));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("service", service);
        metadata.put("indexPath", crmIndexPath.toString());
        metadata.put(
            "indexPaths", indexPaths.stream().map(Path::toString).toList());
        metadata.put("referenceCount", 0);
        metadata.put("answerIntent", refused.intent());
        metadata.put("classificationIntent", "unsupported_metric");
        metadata.put("deterministicAnswer", true);
        metadata.put("unsupportedIndexedMetric", true);
        if (context != null) {
          metadata.put("context", context);
        }
        return new InvestigateResponse(
            refused.summaryText() == null ? "" : refused.summaryText(),
            "",
            metadata,
            List.of(),
            refused);
      }
      progress.emit(
          InvestigationProgressEvent.progress("classify", "running", askLeadIn(query)));
      QueryClassification classification =
          stabilizeClassification(query, context, progress, classifyQuery(query, context, progress));
      progress.emit(
          InvestigationProgressEvent.progress(
              "classify", "done", classifyDoneDetail(query, classification)));

      progress.emit(
          InvestigationProgressEvent.progress(
              "search_hybrid",
              "running",
              "Looking across indexed sources for \"" + shortQuery(query) + "\"…"));
      progress.emit(
          InvestigationProgressEvent.progress(
              "search_zoho",
              "running",
              "Looking in Zoho CRM for matches…",
              IndexConstants.SOURCE_TYPE_ZOHO));
      Map<String, Object> priorBag = readConversationBag(context);
      boolean namedEntityPinCandidate =
          shouldAttemptNamedEntityPin(query, classification, priorBag, crmIndexPath);
      boolean preferNamedEntityPin = namedEntityPinCandidate;
      ExploreService.ExploreResult explore =
          applySourceTypeFilter(exploreService.explore(query, null), sourceFilter);
      String details = explore.summaryText();
      List<InvestigationEvidence> exploreEvidence = excludeDemoEvidence(buildEvidence(explore));

      // Artifacts the question names outright (SOF-4, "the auth architecture doc") are resolved
      // by id/title and verified separately, then merged AHEAD of query-biased retrieval — a
      // whole-sentence ranker routinely spends its top-k on chat noise and drops the very
      // artifact that was asked for, which then reads as an honest "no record exists".
      ArtifactPinOutcome artifactPins = pinArtifactReferences(query, sourceFilter, progress);
      if (!artifactPins.evidence().isEmpty()) {
        exploreEvidence = mergeArtifactPins(artifactPins.evidence(), exploreEvidence);
        details =
            "Named artifacts (verified by id/title):\n"
                + formatEvidence(artifactPins.evidence())
                + "\n\n"
                + details;
      }

      List<SearchHit> rawHits = explore.searchResult().hits();
      progress.emit(
          InvestigationProgressEvent.progress(
              "search_hybrid", "done", hybridSearchDoneDetail(rawHits)));
      List<String> touchedSourceTypes = emitSourceSearchProgress(rawHits, progress);

      if (touchedSourceTypes.size() >= 2) {
        progress.emit(
            InvestigationProgressEvent.progress(
                "conflicts", "done", conflictDetail(rawHits, touchedSourceTypes)));
      }

      // The CRM entity-pin path refuses outright when a named entity has no ledger match. When
      // the "entity" the classifier extracted is really a ticket id or doc title we just verified
      // in the index, that refusal would deny an artifact we are holding — route to the grounded
      // general path instead.
      preferNamedEntityPin =
          preferNamedEntityPin
              && !isCorpusWideStructuredIntent(classification)
              && !entityIsPinnedArtifact(classification, artifactPins);

      AnswerIntent intent = intentFromClassification(classification);
      List<InvestigationEvidence> evidence = exploreEvidence;
      InvestigationAnswer answer;
      Map<String, Object> intelligenceMeta = new LinkedHashMap<>();
      Map<String, Object> conversationBag = new LinkedHashMap<>();

      if (shouldUseStructuredCrmPath(classification) && !preferNamedEntityPin) {
        List<DealLedgerEntry> ledger = zohoQualificationService.listActiveDealLedger(crmIndexPath);
        LedgerSplit split = splitLedger(ledger);
        evidence = evidenceFromLedger(ledger, exploreEvidence);
        progress.emit(
            InvestigationProgressEvent.progress(
                "ledger",
                "done",
                "From Zoho CRM we loaded "
                    + ledger.size()
                    + " active deal(s).",
                IndexConstants.SOURCE_TYPE_ZOHO));
        if (split.demoOnly() != null && !split.demoOnly().isEmpty()) {
          progress.emit(
              InvestigationProgressEvent.progress(
                  "demos",
                  "done",
                  "Set aside "
                      + split.demoOnly().size()
                      + " demo/sample record(s) so they do not skew the answer."));
        }
        progress.emit(
            InvestigationProgressEvent.progress(
                "compose", "running", composeRunningDetail(evidence)));
        Optional<InvestigationAnswer> deterministic =
            tryDeterministicStructuredAnswer(classification, split);
        if (deterministic.isPresent()) {
          answer = deterministic.get();
          PipelineHealthFacts health = computePipelineHealth(split);
          intelligenceMeta.put("intelligenceModel", INTELLIGENCE_MODEL_ID);
          intelligenceMeta.put("structuredPath", classification.intent());
          intelligenceMeta.put("deterministicAnswer", true);
          intelligenceMeta.put("eligibleDealCount", health.eligibleDealCount());
          intelligenceMeta.put("demoDealCount", health.demoDealCount());
          intelligenceMeta.put("eligibleAccountCount", health.eligibleAccountCount());
          intelligenceMeta.put("eligibleOpenPipelineAmount", health.eligibleOpenPipelineAmount());
        } else {
          IntelligenceOutcome outcome =
              runIntelligenceLayer(query, classification, split, context, progress);
          answer = outcome.answer();
          intelligenceMeta.putAll(outcome.metadata());
        }
        conversationBag =
            buildConversationBag(
                classification.entity(),
                answer,
                evidence,
                priorBag);
      } else if (shouldClarify(classification)) {
        progress.emit(
            InvestigationProgressEvent.progress(
                "compose", "running", composeRunningDetail(evidence)));
        answer = buildClarificationAnswer(classification);
        conversationBag = buildConversationBag(classification.entity(), answer, evidence, priorBag);
      } else if (preferNamedEntityPin) {
        NamedEntityPinOutcome pin =
            pinNamedEntityEvidence(
                query, classification, priorBag, exploreEvidence, crmIndexPath, progress);
        // A question can name both a CRM entity and an artifact ("did SOF-4 change the Wipro
        // renewal?"); the canonical-entity filter drops non-CRM rows, so re-attach the pins.
        evidence = mergeArtifactPins(artifactPins.evidence(), pin.evidence());
        details = pin.detailsOverride() != null ? pin.detailsOverride() : details;
        progress.emit(
            InvestigationProgressEvent.progress(
                "compose", "running", composeRunningDetail(evidence)));
        if (pin.notFoundAnswer().isPresent()) {
          answer = pin.notFoundAnswer().get();
        } else if (useCrmOnlyNamedEntityStaleness(classification, evidence)) {
          List<DealLedgerEntry> ledger =
              zohoQualificationService.listActiveDealLedger(crmIndexPath);
          LedgerSplit split = splitLedger(ledger);
          String entity = firstNonBlank(classification.entity(), pin.entityLabel(), query);
          answer = answerNamedEntityStaleness(entity, split);
          intelligenceMeta.put("deterministicAnswer", true);
          intelligenceMeta.put("structuredPath", classification.intent());
        } else {
          answer =
              buildGeneralAnswer(
                  query,
                  evidence,
                  details,
                  context,
                  progress,
                  pin.conflictMarker(),
                  !artifactPins.resolved().isEmpty());
          answer = guardDemoOnlyGeneralSummary(answer, evidence);
          answer = guardGeneralCompletenessClaims(answer);
        }
        intelligenceMeta.put("namedEntityPin", pin.pinned());
        if (pin.canonicalEntityKey() != null) {
          intelligenceMeta.put("canonicalEntityKey", pin.canonicalEntityKey());
        }
        conversationBag =
            buildConversationBag(
                pin.entityLabel() != null ? pin.entityLabel() : classification.entity(),
                answer,
                evidence,
                priorBag);
      } else {
        progress.emit(
            InvestigationProgressEvent.progress(
                "compose", "running", composeRunningDetail(exploreEvidence)));
        answer =
            buildGeneralAnswer(
                query,
                exploreEvidence,
                details,
                context,
                progress,
                null,
                !artifactPins.resolved().isEmpty());
        answer = guardDemoOnlyGeneralSummary(answer, exploreEvidence);
        answer = guardGeneralCompletenessClaims(answer);
        conversationBag = buildConversationBag(classification.entity(), answer, evidence, priorBag);
      }
      progress.emit(
          InvestigationProgressEvent.progress(
              "compose", "done", "Finished writing the answer from the evidence above."));

      Map<String, Object> metadata = new LinkedHashMap<>();
      metadata.put("service", service);
      metadata.put("indexPath", crmIndexPath.toString());
      metadata.put(
          "indexPaths", indexPaths.stream().map(Path::toString).toList());
      metadata.put("referenceCount", explore.references().size());
      metadata.put("answerIntent", answer.intent());
      if (!artifactPins.references().isEmpty()) {
        metadata.put("artifactsFound", artifactPins.resolved());
        metadata.put("artifactsNotFound", artifactPins.unresolved());
      }
      metadata.put("qualificationIntent", intent == AnswerIntent.QUALIFICATION);
      metadata.put("classificationIntent", classification.intent());
      metadata.put("classificationField", classification.field());
      metadata.put("classificationDirection", classification.direction());
      metadata.put("classificationConfidence", classification.confidence());
      if (classification.ambiguityReason() != null) {
        metadata.put("classificationAmbiguity", classification.ambiguityReason());
      }
      metadata.putAll(intelligenceMeta);
      if (!conversationBag.isEmpty()) {
        metadata.put("conversationBag", conversationBag);
      }
      if (context != null) {
        metadata.put("context", context);
      }
      // Callers (e.g. the SSE endpoint) emit the final "result"/"done" events themselves after
      // enriching metadata (such as trialRemaining), matching how the JSON endpoint does it.
      return new InvestigateResponse(
          answer.summaryText() == null ? "" : answer.summaryText(),
          details,
          metadata,
          evidence,
          answer);
    } finally {
      IndexPathHolder.clear();
    }
  }

  /**
   * Emits named retrieval progress for each source type actually present in the hybrid search
   * hits (never for a source the question did not touch). Returns the ordered list of all source
   * types touched, Zoho first.
   */
  private static List<String> emitSourceSearchProgress(
      List<SearchHit> rawHits, InvestigationProgressSink progress) {
    Map<String, List<SearchHit>> hitsBySource = new LinkedHashMap<>();
    float globalBest = 0f;
    for (SearchHit hit : rawHits) {
      String sourceType = hit.sourceType().orElse("");
      if (sourceType.isBlank()) {
        continue;
      }
      hitsBySource.computeIfAbsent(sourceType, key -> new ArrayList<>()).add(hit);
      if (hit.score() > globalBest) {
        globalBest = hit.score();
      }
    }

    List<String> touched = new ArrayList<>();
    List<SearchHit> zohoHits =
        hitsBySource.getOrDefault(IndexConstants.SOURCE_TYPE_ZOHO, List.of());
    if (!zohoHits.isEmpty()) {
      touched.add(IndexConstants.SOURCE_TYPE_ZOHO);
      progress.emit(
          InvestigationProgressEvent.progress(
              "search_zoho",
              "running",
              "Looking in "
                  + humanSourceLabel(IndexConstants.SOURCE_TYPE_ZOHO)
                  + " for matches…",
              IndexConstants.SOURCE_TYPE_ZOHO));
      progress.emit(
          InvestigationProgressEvent.progress(
              "search_zoho",
              "done",
              foundInSourceDetail(
                  humanSourceLabel(IndexConstants.SOURCE_TYPE_ZOHO), zohoHits, globalBest),
              IndexConstants.SOURCE_TYPE_ZOHO));
    }
    for (String sourceType : SECONDARY_SOURCE_ORDER) {
      List<SearchHit> sourceHits = hitsBySource.getOrDefault(sourceType, List.of());
      if (sourceHits.isEmpty()) {
        continue;
      }
      touched.add(sourceType);
      String label = humanSourceLabel(sourceType);
      String step = "search_" + sourceType.toLowerCase(Locale.ROOT);
      progress.emit(
          InvestigationProgressEvent.progress(
              step, "running", "Looking in " + label + " for related content…", sourceType));
      progress.emit(
          InvestigationProgressEvent.progress(
              step, "done", foundInSourceDetail(label, sourceHits, globalBest), sourceType));
    }
    return touched;
  }

  /** Truncate a user query for progress copy (~80 chars). */
  static String shortQuery(String query) {
    if (query == null) {
      return "";
    }
    String trimmed = query.trim().replaceAll("\\s+", " ");
    if (trimmed.length() <= 80) {
      return trimmed;
    }
    return trimmed.substring(0, 77) + "...";
  }

  /** Opening line that restates what the user asked. */
  static String askLeadIn(String query) {
    String q = shortQuery(query);
    if (q.isBlank()) {
      return "Reading your question…";
    }
    return "Looking at your question: \"" + q + "\"";
  }

  /** Classify-done line: intent plus entity/field when present. */
  static String classifyDoneDetail(String query, QueryClassification classification) {
    String q = shortQuery(query);
    String intent =
        classification != null && classification.intent() != null && !classification.intent().isBlank()
            ? classification.intent()
            : "general";
    StringBuilder sb = new StringBuilder();
    sb.append("Treating \"").append(q).append("\" as a ").append(intent);
    if (classification != null
        && classification.entity() != null
        && !classification.entity().isBlank()) {
      sb.append(" for \"").append(classification.entity().trim()).append("\"");
    }
    if (classification != null
        && classification.field() != null
        && !classification.field().isBlank()) {
      sb.append(" (").append(classification.field().trim()).append(")");
    }
    sb.append(".");
    return sb.toString();
  }

  /** Hybrid-search done: count plus a few top names across sources. */
  static String hybridSearchDoneDetail(List<SearchHit> rawHits) {
    if (rawHits == null || rawHits.isEmpty()) {
      return "Indexed search returned no hits yet.";
    }
    float best = 0f;
    for (SearchHit hit : rawHits) {
      if (hit.score() > best) {
        best = hit.score();
      }
    }
    float nameFloor = best > 0f ? best * 0.7f : 0f;
    List<String> tops = new ArrayList<>();
    for (SearchHit hit : rawHits) {
      if (hit.score() < nameFloor) {
        continue;
      }
      String name = hitDisplayName(hit);
      if (name == null || name.isBlank()) {
        continue;
      }
      String source = hit.sourceType().orElse("");
      String label = source.isBlank() ? name : humanSourceLabel(source) + " \"" + name + "\"";
      if (!tops.contains(label)) {
        tops.add(label);
      }
      if (tops.size() >= 3) {
        break;
      }
    }
    if (tops.isEmpty()) {
      return "Indexed search returned " + rawHits.size() + " hit(s).";
    }
    return "Indexed search returned "
        + rawHits.size()
        + " hit(s) · top "
        + String.join(", ", tops);
  }

  /**
   * Finding-voice wrapper around competitive named hits, e.g. {@code In Zoho CRM we found 3
   * matches: "Deal A", "Deal B" (+1 more).}
   */
  static String foundInSourceDetail(String label, List<SearchHit> hits, float globalBestScore) {
    if (hits == null || hits.isEmpty()) {
      return "In " + label + " we found no matching results.";
    }
    float nameFloor = globalBestScore > 0f ? globalBestScore * 0.7f : 0f;
    List<String> names =
        hits.stream()
            .filter(hit -> hit.score() >= nameFloor)
            .map(InvestigationService::hitDisplayName)
            .filter(name -> name != null && !name.isBlank())
            .distinct()
            .toList();
    if (names.isEmpty()) {
      return "In " + label + " we found " + hits.size() + " result(s).";
    }
    int show = Math.min(3, names.size());
    String joined =
        names.subList(0, show).stream()
            .map(name -> "\"" + name + "\"")
            .collect(Collectors.joining(", "));
    String countWord = names.size() == 1 ? "match" : "matches";
    if (names.size() > show) {
      return "In "
          + label
          + " we found "
          + names.size()
          + " "
          + countWord
          + ": "
          + joined
          + " (+"
          + (names.size() - show)
          + " more).";
    }
    return "In " + label + " we found " + names.size() + " " + countWord + ": " + joined + ".";
  }

  /** Compose-running line from evidence already in hand. */
  static String composeRunningDetail(List<InvestigationEvidence> evidence) {
    if (evidence == null || evidence.isEmpty()) {
      return "Writing a grounded answer from what we have so far…";
    }
    List<String> sources =
        evidence.stream()
            .map(InvestigationEvidence::source)
            .filter(source -> source != null && !source.isBlank())
            .map(InvestigationService::humanSourceLabel)
            .distinct()
            .toList();
    String sourceList = sources.isEmpty() ? "indexed sources" : String.join(", ", sources);
    return "Writing a grounded answer from "
        + evidence.size()
        + " evidence row"
        + (evidence.size() == 1 ? "" : "s")
        + " ("
        + sourceList
        + ").";
  }

  /** e.g. {@code Zoho CRM: matched "Deal A", "Deal B" (+1 more)}. */
  static String namedHitsDetail(String label, List<SearchHit> hits) {
    float best = 0f;
    if (hits != null) {
      for (SearchHit hit : hits) {
        if (hit.score() > best) {
          best = hit.score();
        }
      }
    }
    return namedHitsDetail(label, hits, best);
  }

  /**
   * Names only hits that are competitive with the global best score so weak Zoho KNN padding is
   * not listed as "matched" on every Notion/Slack question.
   */
  static String namedHitsDetail(String label, List<SearchHit> hits, float globalBestScore) {
    if (hits == null || hits.isEmpty()) {
      return label + ": no matching results.";
    }
    float nameFloor = globalBestScore > 0f ? globalBestScore * 0.7f : 0f;
    List<String> names =
        hits.stream()
            .filter(hit -> hit.score() >= nameFloor)
            .map(InvestigationService::hitDisplayName)
            .filter(name -> name != null && !name.isBlank())
            .distinct()
            .toList();
    if (names.isEmpty()) {
      return label + ": found " + hits.size() + " result(s).";
    }
    int show = Math.min(3, names.size());
    String joined =
        names.subList(0, show).stream()
            .map(name -> "\"" + name + "\"")
            .collect(Collectors.joining(", "));
    if (names.size() > show) {
      return label + ": matched " + joined + " (+" + (names.size() - show) + " more)";
    }
    return label + ": matched " + joined;
  }

  static String hitDisplayName(SearchHit hit) {
    if (hit.fileName() != null && !hit.fileName().isBlank()) {
      return hit.fileName().trim();
    }
    String content = hit.content().orElse("").trim();
    if (!content.isBlank()) {
      String firstLine = content.lines().findFirst().orElse(content).trim();
      if (firstLine.length() > 80) {
        return firstLine.substring(0, 77) + "...";
      }
      return firstLine;
    }
    if (hit.path() != null && !hit.path().isBlank()) {
      return hit.path().trim();
    }
    return "untitled";
  }

  static String humanSourceLabel(String sourceType) {
    return switch (sourceType) {
      case "ZOHO" -> "Zoho CRM";
      case "SLACK" -> "Slack";
      case "NOTION" -> "Notion";
      case "JIRA" -> "Jira";
      case "LINEAR" -> "Linear";
      case "GIT" -> "Git history";
      case "CODE" -> "code";
      default -> sourceType;
    };
  }

  /**
   * Best-effort live conflict summary: if two different sources mention the same entity with
   * different parseable amounts, name them; otherwise a generic "found N sources" message.
   */
  static String conflictDetail(List<SearchHit> rawHits, List<String> touchedSourceTypes) {
    Map<String, Map<String, String>> amountsByEntityAndSource = new LinkedHashMap<>();
    for (SearchHit hit : rawHits) {
      String sourceType = hit.sourceType().orElse("");
      if (sourceType.isBlank()) {
        continue;
      }
      String entityKey = CanonicalEntityKey.key(hit.fileName());
      if (entityKey.isBlank()) {
        continue;
      }
      String amount = firstMoneyValue(hit);
      if (amount == null) {
        continue;
      }
      amountsByEntityAndSource
          .computeIfAbsent(entityKey, key -> new LinkedHashMap<>())
          .putIfAbsent(sourceType, amount);
    }
    for (Map.Entry<String, Map<String, String>> entry : amountsByEntityAndSource.entrySet()) {
      Map<String, String> bySource = entry.getValue();
      if (bySource.size() < 2) {
        continue;
      }
      Set<String> distinctAmounts = new LinkedHashSet<>(bySource.values());
      if (distinctAmounts.size() < 2) {
        continue;
      }
      String mismatch =
          bySource.entrySet().stream()
              .map(e -> "$" + e.getValue() + " in " + humanSourceLabel(e.getKey()))
              .collect(Collectors.joining(", "));
      return "Found " + mismatch + " for the same record — reconciling...";
    }
    String sources =
        touchedSourceTypes.stream().map(InvestigationService::humanSourceLabel).collect(Collectors.joining(", "));
    return "Found " + touchedSourceTypes.size() + " source(s) (" + sources + ") — checking for conflicts...";
  }

  private static String firstMoneyValue(SearchHit hit) {
    if (IndexConstants.SOURCE_TYPE_ZOHO.equals(hit.sourceType().orElse(""))) {
      Optional<String> amount = hit.zoho().flatMap(ZohoHitMetadata::amount);
      if (amount.isPresent() && !amount.get().isBlank() && amount.get().matches(".*\\d.*")) {
        return amount.get().replace(",", "").trim();
      }
    }
    return moneyFromText(hit.content().orElse(""));
  }

  private static ChatProgressListener retryListener(InvestigationProgressSink progress) {
    return new ChatProgressListener() {
      @Override
      public void onRetry(int attempt, int maxAttempts, long delayMs, int statusCode) {
        progress.emit(
            InvestigationProgressEvent.progress(
                "retry_llm",
                "running",
                "Model call failed — retrying (attempt "
                    + attempt
                    + "/"
                    + maxAttempts
                    + ")…"));
      }
    };
  }

  /**
   * @deprecated Keyword detection retained only for tests/legacy callers. Investigation uses LLM
   *     classification.
   */
  @Deprecated
  static AnswerIntent detectIntent(String query) {
    QueryClassification classification = heuristicClassification(query);
    return intentFromClassification(classification);
  }

  static boolean isQualificationIntent(String query) {
    String intent = heuristicClassification(query).intent();
    return "qualify".equals(intent) || "reason".equals(intent);
  }

  static boolean shouldUseIntelligenceLayer(QueryClassification classification) {
    return shouldUseStructuredCrmPath(classification);
  }

  /**
   * Structured CRM path: ledger + demo split. Triggered only by classified intent enum — never by
   * phrase matching on the user query.
   */
  static boolean shouldUseStructuredCrmPath(QueryClassification classification) {
    if (!classification.confident()) {
      return false;
    }
    return STRUCTURED_CRM_INTENTS.contains(classification.intent());
  }

  static boolean isDeterministicStructuredIntent(QueryClassification classification) {
    if (!classification.confident()) {
      return false;
    }
    if (DETERMINISTIC_INTENTS.contains(classification.intent())) {
      return true;
    }
    // Explicit max/min-by-field questions ("largest deal by amount", "highest probability deal")
    // are a pure Java comparison over the eligible ledger — no LLM judgment required. Routed by
    // the classified intent + field only, never by phrase matching on the raw query.
    return "rank".equals(classification.intent())
        && classification.field() != null
        && !classification.field().isBlank();
  }

  /** @deprecated Prefer {@link #shouldUseIntelligenceLayer}. */
  @Deprecated
  static boolean shouldExecuteRank(QueryClassification classification) {
    return classification.confident()
        && "rank".equals(classification.intent())
        && classification.field() != null
        && !classification.field().isBlank();
  }

  /** @deprecated Prefer {@link #shouldUseIntelligenceLayer}. */
  @Deprecated
  static boolean shouldExecuteQualify(QueryClassification classification) {
    return classification.confident()
        && ("qualify".equals(classification.intent()) || "reason".equals(classification.intent()));
  }

  static boolean shouldClarify(QueryClassification classification) {
    if (classification.parsed()
        && classification.confidence() > 0
        && classification.confidence() < CLASSIFICATION_CONFIDENCE_THRESHOLD
        && STRUCTURED_CRM_INTENTS.contains(classification.intent())) {
      return true;
    }
    return classification.parsed()
        && "rank".equals(classification.intent())
        && classification.confident()
        && (classification.field() == null || classification.field().isBlank());
  }

  static LedgerSplit splitLedger(List<DealLedgerEntry> ledger) {
    if (ledger == null || ledger.isEmpty()) {
      return new LedgerSplit(List.of(), List.of());
    }
    List<DealLedgerEntry> eligible = new ArrayList<>();
    List<DealLedgerEntry> demoOnly = new ArrayList<>();
    for (DealLedgerEntry entry : ledger) {
      if (entry.demo() || entry.flags().contains(DEMO_FLAG)) {
        demoOnly.add(entry);
      } else {
        eligible.add(entry);
      }
    }
    return new LedgerSplit(List.copyOf(eligible), List.copyOf(demoOnly));
  }

  /**
   * Deterministic answers keyed only by classified intent enum (never by phrase or deal-name
   * matchers on the raw query).
   */
  static Optional<InvestigationAnswer> tryDeterministicStructuredAnswer(
      QueryClassification classification, LedgerSplit split) {
    if (!isDeterministicStructuredIntent(classification)) {
      return Optional.empty();
    }
    PipelineHealthFacts health = computePipelineHealth(split);
    return switch (classification.intent()) {
      case "enumerate" -> Optional.of(answerEnumerate(classification, split, health));
      case "aggregate" -> Optional.of(answerAggregateOrExposure(classification, split, health));
      case "corpus_staleness" -> Optional.of(answerCorpusStaleness(split));
      case "named_entity_staleness" ->
          Optional.of(answerNamedEntityStaleness(classification.entity(), split));
      case "rank" -> Optional.of(answerRankExtreme(classification, split, health));
      default -> Optional.empty();
    };
  }

  /**
   * Deterministic max/min-by-field answer for "rank" questions with a classified field (e.g.
   * "largest deal by amount", "highest probability deal"). Never touches the LLM.
   */
  static InvestigationAnswer answerRankExtreme(
      QueryClassification classification, LedgerSplit split, PipelineHealthFacts health) {
    String field = classification == null ? null : classification.field();
    String direction =
        classification == null || classification.direction() == null
            ? "desc"
            : classification.direction();
    List<String> reasons = new ArrayList<>();
    if (field == null || field.isBlank()) {
      String message = "I need a specific field (such as amount or probability) to rank deals by.";
      reasons.add(message);
      return new InvestigationAnswer(
          "Ranked deal answer",
          null,
          Map.of(),
          List.copyOf(reasons),
          Map.of(),
          INTELLIGENCE_MODEL_ID,
          "insufficient_data",
          AnswerIntent.RANK.name(),
          message);
    }
    if (split.eligible().isEmpty()) {
      String message =
          health.demoDealCount() > 0
              ? "Only demo/sample deals are indexed; there is no eligible deal to report."
              : "No eligible deals were found in the indexed CRM data.";
      reasons.add(message);
      return new InvestigationAnswer(
          "Ranked deal answer",
          null,
          Map.of(),
          List.copyOf(reasons),
          Map.of(),
          INTELLIGENCE_MODEL_ID,
          "insufficient_data",
          AnswerIntent.RANK.name(),
          message);
    }
    Optional<DealLedgerEntry> extreme = deterministicExtreme(split.eligible(), field, direction);
    if (extreme.isEmpty()) {
      String message = "No eligible deal has a comparable " + field + " value to rank.";
      reasons.add(message);
      return new InvestigationAnswer(
          "Ranked deal answer",
          null,
          Map.of(),
          List.copyOf(reasons),
          Map.of(),
          INTELLIGENCE_MODEL_ID,
          "insufficient_data",
          AnswerIntent.RANK.name(),
          message);
    }
    DealLedgerEntry entry = extreme.get();
    String amount = entry.primaryFields().getOrDefault("Amount", "");
    if (amount.isBlank()) {
      amount = formatMoney(entry.amountValue());
    }
    String stage = nullToDash(entry.primaryFields().get("Stage"));
    String probability = nullToDash(entry.primaryFields().get("Probability"));
    String account = nullToDash(entry.accountName());
    String kind = "asc".equalsIgnoreCase(direction) ? "lowest" : "highest";
    boolean derivedExpectedValue =
        field != null
            && (field.toLowerCase(Locale.ROOT).contains("expected") || "ev".equalsIgnoreCase(field));
    StringBuilder summary = new StringBuilder();
    if (derivedExpectedValue) {
      double ev =
          entry.amountValue()
              * (Double.isNaN(entry.probability()) ? 0.0 : entry.probability())
              / 100.0;
      summary
          .append(entry.name())
          .append(" (")
          .append(account)
          .append(") has the ")
          .append(kind)
          .append(" expected value among eligible deals: expected value ")
          .append(formatMoney(ev))
          .append(" (amount ")
          .append(amount)
          .append(" x probability ")
          .append(probability)
          .append("), stage ")
          .append(stage)
          .append('.');
    } else {
      summary
          .append(entry.name())
          .append(" (")
          .append(account)
          .append(") has the ")
          .append(kind)
          .append(' ')
          .append(field)
          .append(" among eligible deals: amount ")
          .append(amount)
          .append(", stage ")
          .append(stage)
          .append(", probability ")
          .append(probability)
          .append('.');
    }
    reasons.add(
        derivedExpectedValue
            ? "Deterministic expected-value (amount x probability) comparison over eligible deals."
            : "Deterministic " + field + " " + direction + " comparison over eligible deals.");
    if (health.demoDealCount() > 0) {
      summary
          .append(" Excluded ")
          .append(health.demoDealCount())
          .append(" demo/sample deal(s) from this comparison.");
      reasons.add("Excluded " + health.demoDealCount() + " demo/sample deal(s) from this comparison.");
    }
    return new InvestigationAnswer(
        "Ranked deal answer",
        entry.name(),
        new LinkedHashMap<>(entry.primaryFields()),
        List.copyOf(reasons),
        Map.of(),
        INTELLIGENCE_MODEL_ID,
        "deterministic",
        AnswerIntent.RANK.name(),
        summary.toString());
  }

  static InvestigationAnswer answerEnumerate(
      QueryClassification classification, LedgerSplit split, PipelineHealthFacts health) {
    if (classification != null
        && "stage".equalsIgnoreCase(classification.field())
        && classification.fieldValues() != null
        && !classification.fieldValues().isEmpty()) {
      return answerEnumerateDealsByStage(classification.fieldValues(), split, health);
    }
    List<String> accounts = eligibleAccountNames(split);
    String summary;
    List<String> reasons = new ArrayList<>();
    if (accounts.isEmpty()) {
      summary =
          health.demoDealCount() > 0
              ? "No active accounts found. "
                  + health.demoDealCount()
                  + " demo/sample deal(s) were excluded."
              : "No active accounts found in the indexed CRM data.";
      reasons.add(summary);
    } else {
      summary =
          "Active accounts ("
              + accounts.size()
              + "): "
              + String.join(", ", accounts)
              + ".";
      if (health.demoDealCount() > 0) {
        summary +=
            " Excluded " + health.demoDealCount() + " demo/sample deal(s) from this inventory.";
        reasons.add("Excluded " + health.demoDealCount() + " demo/sample deal(s) from inventory.");
      }
      reasons.add("Enumerated unique Account names from the eligible active deals.");
    }
    return new InvestigationAnswer(
        "Account inventory",
        null,
        Map.of(
            "eligibleAccountCount",
            String.valueOf(health.eligibleAccountCount()),
            "demoDealCount",
            String.valueOf(health.demoDealCount())),
        List.copyOf(reasons),
        Map.of(),
        INTELLIGENCE_MODEL_ID,
        "deterministic",
        AnswerIntent.GENERAL.name(),
        summary);
  }

  /**
   * Enumerate ELIGIBLE DEALS (not just account names) filtered to one or more classified Stage
   * values, as rows of deal · account · stage · amount · probability. Used when an enumerate
   * question carries a Stage selector (F2: "which deals are in Proposal stage").
   */
  static InvestigationAnswer answerEnumerateDealsByStage(
      List<String> stages, LedgerSplit split, PipelineHealthFacts health) {
    Set<String> wanted =
        stages.stream()
            .filter(s -> s != null && !s.isBlank())
            .map(s -> s.trim().toLowerCase(Locale.ROOT))
            .collect(Collectors.toCollection(LinkedHashSet::new));
    String stageLabel = String.join(", ", stages);
    List<String> reasons = new ArrayList<>();
    if (wanted.isEmpty()) {
      String message = "I need at least one stage name to filter deals by.";
      reasons.add(message);
      return new InvestigationAnswer(
          "Deals by stage",
          null,
          Map.of(),
          List.copyOf(reasons),
          Map.of(),
          INTELLIGENCE_MODEL_ID,
          "insufficient_data",
          AnswerIntent.GENERAL.name(),
          message);
    }
    List<DealLedgerEntry> matches =
        split.eligible().stream()
            .filter(
                entry -> {
                  String stage = entry.primaryFields().getOrDefault("Stage", "");
                  if (stage.isBlank()) {
                    return false;
                  }
                  String normalizedStage = stage.trim().toLowerCase(Locale.ROOT);
                  return wanted.stream()
                      .anyMatch(w -> normalizedStage.equals(w) || normalizedStage.contains(w));
                })
            .toList();
    String summary;
    if (matches.isEmpty()) {
      summary = "No eligible deals are currently in " + stageLabel + " stage.";
      reasons.add(summary);
    } else {
      String rows =
          matches.stream()
              .map(
                  entry -> {
                    String account = nullToDash(entry.accountName());
                    String amount = entry.primaryFields().getOrDefault("Amount", "");
                    if (amount.isBlank()) {
                      amount = formatMoney(entry.amountValue());
                    }
                    String stage = nullToDash(entry.primaryFields().get("Stage"));
                    String probability = nullToDash(entry.primaryFields().get("Probability"));
                    return entry.name()
                        + " ("
                        + account
                        + ") — stage "
                        + stage
                        + ", amount "
                        + amount
                        + ", probability "
                        + probability;
                  })
              .collect(Collectors.joining("; "));
      summary = "Deals in " + stageLabel + " stage (" + matches.size() + "): " + rows + ".";
      reasons.add("Filtered eligible deals where Stage matches " + stageLabel + ".");
      if (health.demoDealCount() > 0) {
        summary += " Excluded " + health.demoDealCount() + " demo/sample deal(s) from this list.";
        reasons.add("Excluded " + health.demoDealCount() + " demo/sample deal(s) from this list.");
      }
    }
    return new InvestigationAnswer(
        "Deals by stage",
        matches.isEmpty() ? null : matches.getFirst().name(),
        matches.isEmpty() ? Map.of() : new LinkedHashMap<>(matches.getFirst().primaryFields()),
        List.copyOf(reasons),
        Map.of(),
        INTELLIGENCE_MODEL_ID,
        "deterministic",
        AnswerIntent.GENERAL.name(),
        summary);
  }


  /** True when the question asks for SaaS metrics that are not indexed Zoho fields. */
  static boolean asksUnsupportedIndexedMetric(String query) {
    if (query == null || query.isBlank()) {
      return false;
    }
    return UNSUPPORTED_INDEXED_METRIC.matcher(query).find();
  }

  /**
   * Honest refuse for churn / ARR / MRR / NRR — never substitute open-pipeline totals.
   */
  static InvestigationAnswer answerUnsupportedIndexedMetric(String query) {
    String message =
        "Indexed Zoho CRM data does not include churn rate, ARR, MRR, or NRR fields, "
            + "so I cannot answer that from the current corpus. A deal-amount pipeline sum is a "
            + "different metric and is not a substitute.";
    return new InvestigationAnswer(
        "Unsupported indexed metric",
        null,
        Map.of(),
        List.of(message),
        Map.of(),
        INTELLIGENCE_MODEL_ID,
        TRUST_INSUFFICIENT,
        AnswerIntent.GENERAL.name(),
        message);
  }

  static InvestigationAnswer answerAggregate(PipelineHealthFacts health) {
    String amount = formatMoney(health.eligibleOpenPipelineAmount());
    String summary =
        "Open pipeline value across "
            + health.eligibleDealCount()
            + " eligible active deal(s) is "
            + amount
            + ".";
    List<String> reasons = new ArrayList<>();
    reasons.add("Sum of parseable Amount on eligible, open deals = " + amount + ".");
    if (health.demoDealCount() > 0) {
      summary +=
          " Excluded "
              + health.demoDealCount()
              + " demo/sample deal(s) from this total.";
      reasons.add("Demo/sample deals were excluded from the sum.");
    }
    return new InvestigationAnswer(
        "Pipeline aggregate",
        null,
        Map.of(
            "eligibleOpenPipelineAmount",
            amount,
            "eligibleDealCount",
            String.valueOf(health.eligibleDealCount()),
            "demoDealCount",
            String.valueOf(health.demoDealCount())),
        List.copyOf(reasons),
        Map.of(),
        INTELLIGENCE_MODEL_ID,
        "deterministic",
        AnswerIntent.GENERAL.name(),
        summary);
  }

  /**
   * Routes aggregate questions: account-exposure rollups (sum per account) vs the plain open
   * pipeline total. Keyed only on the classified intent + field — never raw query phrases.
   */
  static InvestigationAnswer answerAggregateOrExposure(
      QueryClassification classification, LedgerSplit split, PipelineHealthFacts health) {
    String field = classification == null ? null : classification.field();
    boolean exposureRollup =
        field != null && field.toLowerCase(Locale.ROOT).contains("exposure");
    return exposureRollup
        ? answerAccountExposureRollup(split, health)
        : answerAggregate(health);
  }

  /**
   * Deterministic per-account exposure rollup: sums eligible deal amounts GROUPED BY ACCOUNT
   * (P3: "largest total exposure across all its deals"). Pure Java over the ledger — no LLM.
   */
  static InvestigationAnswer answerAccountExposureRollup(
      LedgerSplit split, PipelineHealthFacts health) {
    Map<String, Double> exposureByAccount = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    Map<String, List<String>> dealsByAccount = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    for (DealLedgerEntry entry : split.eligible()) {
      String account = entry.accountName();
      if (account == null || account.isBlank() || Double.isNaN(entry.amountValue())) {
        continue;
      }
      exposureByAccount.merge(account, entry.amountValue(), Double::sum);
      dealsByAccount
          .computeIfAbsent(account, ignored -> new ArrayList<>())
          .add(entry.name() + " (" + formatMoney(entry.amountValue()) + ")");
    }
    if (exposureByAccount.isEmpty()) {
      String message =
          health.demoDealCount() > 0
              ? "Only demo/sample deals are indexed; there is no eligible exposure to roll up."
              : "No eligible deals with a parseable amount were found to roll up per account.";
      return new InvestigationAnswer(
          "Account exposure rollup",
          null,
          Map.of(),
          List.of(message),
          Map.of(),
          INTELLIGENCE_MODEL_ID,
          "insufficient_data",
          AnswerIntent.GENERAL.name(),
          message);
    }
    Map.Entry<String, Double> top = null;
    for (Map.Entry<String, Double> account : exposureByAccount.entrySet()) {
      if (top == null || account.getValue() > top.getValue()) {
        top = account;
      }
    }
    String topAccount = top.getKey();
    String deals = String.join("; ", dealsByAccount.getOrDefault(topAccount, List.of()));
    int dealCount = dealsByAccount.getOrDefault(topAccount, List.of()).size();
    StringBuilder summary = new StringBuilder();
    summary
        .append("Largest account exposure: ")
        .append(topAccount)
        .append(" — total ")
        .append(formatMoney(top.getValue()))
        .append(" across ")
        .append(dealCount)
        .append(" active deal(s): ")
        .append(deals)
        .append('.');
    List<String> reasons = new ArrayList<>();
    reasons.add(
        "Added up each account's deal amounts across all real (non-demo) deals, then picked the"
            + " account with the largest total.");
    if (health.demoDealCount() > 0) {
      summary
          .append(" Excluded ")
          .append(health.demoDealCount())
          .append(" demo/sample deal(s) from this rollup.");
      reasons.add("Excluded " + health.demoDealCount() + " demo/sample deal(s) from this rollup.");
    }
    return new InvestigationAnswer(
        "Account exposure rollup",
        topAccount,
        Map.of(
            "account", topAccount,
            "totalExposure", formatMoney(top.getValue()),
            "dealCount", String.valueOf(dealCount),
            "demoDealCount", String.valueOf(health.demoDealCount())),
        List.copyOf(reasons),
        Map.of(),
        INTELLIGENCE_MODEL_ID,
        "deterministic",
        AnswerIntent.GENERAL.name(),
        summary.toString());
  }

  static InvestigationAnswer answerCorpusStaleness(LedgerSplit split) {
    List<DealLedgerEntry> stale =
        split.eligible().stream().filter(InvestigationService::isStale).toList();
    String summary;
    List<String> reasons = new ArrayList<>();
    if (stale.isEmpty()) {
      summary =
          "No eligible active deals are stale or overdue (closed date passed, no recent "
              + "activity, or not updated in over "
              + STALE_AFTER_DAYS
              + " days).";
      reasons.add(summary);
    } else {
      String names =
          stale.stream()
              .map(
                  entry ->
                      entry.name()
                          + " ("
                          + (entry.accountName() == null || entry.accountName().isBlank()
                              ? "—"
                              : entry.accountName())
                          + ")")
              .collect(Collectors.joining("; "));
      summary =
          "Stale / quiet eligible deals ("
              + stale.size()
              + "): "
              + names
              + ".";
      if (split.demoOnly() != null && !split.demoOnly().isEmpty()) {
        summary +=
            " Excluded " + split.demoOnly().size() + " demo/sample deal(s) from this list.";
      }
      reasons.add(
          "Applied isStale: close_date_passed | stale_record | no_activities | Modified older than "
              + STALE_AFTER_DAYS
              + " days.");
    }
    return new InvestigationAnswer(
        "Corpus staleness",
        stale.isEmpty() ? null : stale.getFirst().name(),
        stale.isEmpty() ? Map.of() : new LinkedHashMap<>(stale.getFirst().primaryFields()),
        List.copyOf(reasons),
        Map.of(),
        INTELLIGENCE_MODEL_ID,
        "deterministic",
        AnswerIntent.REASON.name(),
        summary);
  }

  static InvestigationAnswer answerNamedEntityStaleness(String entity, LedgerSplit split) {
    if (entity == null || entity.isBlank()) {
      return new InvestigationAnswer(
          "Named entity staleness",
          null,
          Map.of(),
          List.of("Classification did not provide an entity for named_entity_staleness."),
          Map.of(),
          INTELLIGENCE_MODEL_ID,
          "insufficient_data",
          AnswerIntent.REASON.name(),
          "I need a specific deal or account name to assess staleness.");
    }
    Optional<DealLedgerEntry> inDemo =
        resolveNamedEntity(entity, split.demoOnly() == null ? List.of() : split.demoOnly());
    if (inDemo.isPresent()) {
      String message =
          "\""
              + entity
              + "\" matches a demo/sample CRM record, which is excluded from staleness answers.";
      return new InvestigationAnswer(
          "Named entity staleness",
          null,
          Map.of(),
          List.of(message),
          Map.of(),
          INTELLIGENCE_MODEL_ID,
          "insufficient_data",
          AnswerIntent.REASON.name(),
          message);
    }
    Optional<DealLedgerEntry> resolved = resolveNamedEntity(entity, split.eligible());
    if (resolved.isEmpty()) {
      String message =
          "No eligible deal or account matching \"" + entity + "\" was found in the indexed CRM data.";
      return new InvestigationAnswer(
          "Named entity staleness",
          null,
          Map.of(),
          List.of(message),
          Map.of(),
          INTELLIGENCE_MODEL_ID,
          "insufficient_data",
          AnswerIntent.REASON.name(),
          message);
    }
    DealLedgerEntry entry = resolved.get();
    boolean stale = isStale(entry);
    String staleReason = staleReason(entry);
    String summary =
        stale
            ? entry.name()
                + " ("
                + nullToDash(entry.accountName())
                + ") is stale/quiet: "
                + staleReason
                + "."
            : entry.name()
                + " ("
                + nullToDash(entry.accountName())
                + ") does not meet the staleness rule "
                + "(no close_date_passed / stale_record / no_activities and Modified within "
                + STALE_AFTER_DAYS
                + " days).";
    List<String> reasons = new ArrayList<>();
    reasons.add(stale ? "isStale=true: " + staleReason : "isStale=false");
    reasons.add(
        "Flags: "
            + (entry.flags().isEmpty() ? "none" : String.join(",", entry.flags())));
    reasons.add("Modified: " + entry.primaryFields().getOrDefault("Modified", "—"));
    reasons.add("Close Date: " + entry.primaryFields().getOrDefault("Close Date", "—"));
    return new InvestigationAnswer(
        "Named entity staleness",
        entry.name(),
        new LinkedHashMap<>(entry.primaryFields()),
        List.copyOf(reasons),
        Map.of(),
        INTELLIGENCE_MODEL_ID,
        "deterministic",
        AnswerIntent.REASON.name(),
        summary);
  }

  /** Shared staleness predicate for corpus_staleness and named_entity_staleness. */
  static boolean isStale(DealLedgerEntry entry) {
    if (entry == null) {
      return false;
    }
    List<String> flags = entry.flags() == null ? List.of() : entry.flags();
    if (flags.contains("close_date_passed")
        || flags.contains("stale_record")
        || flags.contains("no_activities")) {
      return true;
    }
    Instant modified = entry.modified();
    return modified != null
        && modified.isBefore(Instant.now().minus(STALE_AFTER_DAYS, ChronoUnit.DAYS));
  }

  static String staleReason(DealLedgerEntry entry) {
    List<String> flags = entry.flags() == null ? List.of() : entry.flags();
    List<String> parts = new ArrayList<>();
    if (flags.contains("close_date_passed")) {
      parts.add("close_date_passed");
    }
    if (flags.contains("stale_record")) {
      parts.add("stale_record");
    }
    if (flags.contains("no_activities")) {
      parts.add("no_activities");
    }
    Instant modified = entry.modified();
    if (modified != null
        && modified.isBefore(Instant.now().minus(STALE_AFTER_DAYS, ChronoUnit.DAYS))) {
      parts.add("Modified older than " + STALE_AFTER_DAYS + " days");
    }
    return parts.isEmpty() ? "staleness rule" : String.join(", ", parts);
  }

  static Optional<DealLedgerEntry> resolveNamedEntity(
      String entity, List<DealLedgerEntry> ledger) {
    if (entity == null || entity.isBlank() || ledger == null || ledger.isEmpty()) {
      return Optional.empty();
    }
    String canonical = CanonicalEntityKey.key(entity);
    String normalized = normalizeEntityToken(entity);
    if (!canonical.isBlank()) {
      Optional<DealLedgerEntry> byCanonical =
          ledger.stream()
              .filter(
                  e ->
                      CanonicalEntityKey.queryMentionsRecord(entity, e.name())
                          || CanonicalEntityKey.queryMentionsRecord(entity, e.accountName()))
              .findFirst();
      if (byCanonical.isPresent()) {
        return byCanonical;
      }
    }
    Optional<DealLedgerEntry> exact =
        ledger.stream()
            .filter(
                e ->
                    normalizeEntityToken(e.name()).equals(normalized)
                        || normalizeEntityToken(e.accountName()).equals(normalized))
            .findFirst();
    if (exact.isPresent()) {
      return exact;
    }
    Optional<DealLedgerEntry> contains =
        ledger.stream()
            .filter(
                e -> {
                  String deal = normalizeEntityToken(e.name());
                  String account = normalizeEntityToken(e.accountName());
                  // Only treat the query as a short-form of a longer deal/account name
                  // (never the reverse — that accepts blended hallucinations like
                  // "King Chapman Mega Deal" against "Chapman").
                  return (!deal.isBlank() && deal.contains(normalized) && normalized.length() >= 3)
                      || (!account.isBlank()
                          && account.contains(normalized)
                          && normalized.length() >= 3);
                })
            .findFirst();
    if (contains.isPresent()) {
      return contains;
    }
    Set<String> queryTokens = significantTokens(normalized);
    if (queryTokens.isEmpty()) {
      queryTokens = CanonicalEntityKey.significantTokens(entity);
    }
    if (queryTokens.isEmpty()) {
      return Optional.empty();
    }
    Set<String> tokens = queryTokens;
    // Require every query token to appear, and reject when the query introduces extra
    // significant tokens the entity does not have (blocks blended names).
    return ledger.stream()
        .filter(
            e -> {
              Set<String> dealTokens = significantTokens(normalizeEntityToken(e.name()));
              Set<String> accountTokens =
                  significantTokens(normalizeEntityToken(e.accountName()));
              Set<String> entityTokens = new LinkedHashSet<>();
              entityTokens.addAll(dealTokens);
              entityTokens.addAll(accountTokens);
              if (entityTokens.isEmpty()) {
                return false;
              }
              return entityTokens.containsAll(tokens)
                  || (tokens.containsAll(entityTokens)
                      && entityTokens.size() >= tokens.size());
            })
        .findFirst();
  }

  static List<String> eligibleAccountNames(LedgerSplit split) {
    if (split == null || split.eligible() == null) {
      return List.of();
    }
    LinkedHashSet<String> accounts = new LinkedHashSet<>();
    for (DealLedgerEntry entry : split.eligible()) {
      String account = entry.accountName();
      if (account == null || account.isBlank()) {
        account = entry.primaryFields().getOrDefault("Account", "");
      }
      if (account != null && !account.isBlank()) {
        accounts.add(account.trim());
      }
    }
    return List.copyOf(accounts);
  }

  static List<InvestigationEvidence> excludeDemoEvidence(List<InvestigationEvidence> evidence) {
    if (evidence == null || evidence.isEmpty()) {
      return evidence == null ? List.of() : evidence;
    }
    return evidence.stream()
        .filter(
            item ->
                item.flags() == null
                    || (!item.flags().contains(DEMO_FLAG)
                        && item.flags().stream()
                            .noneMatch(flag -> "demo".equalsIgnoreCase(flag))))
        .toList();
  }

  static InvestigationAnswer guardDemoOnlyGeneralSummary(
      InvestigationAnswer answer, List<InvestigationEvidence> evidence) {
    if (answer == null || answer.summaryText() == null) {
      return answer;
    }
    // Soft guard already excludes demo from evidence; nothing further required when evidence is clean.
    if (evidence != null
        && evidence.stream()
            .anyMatch(
                item ->
                    item.flags() != null && item.flags().contains(DEMO_FLAG))) {
      return answer;
    }
    return answer;
  }

  private static String formatMoney(double amount) {
    if (Double.isNaN(amount) || amount <= 0) {
      return "0";
    }
    if (Math.abs(amount - Math.rint(amount)) < 0.005) {
      return String.format(Locale.ROOT, "%.0f", amount);
    }
    return String.format(Locale.ROOT, "%.2f", amount);
  }

  private static String nullToDash(String value) {
    return value == null || value.isBlank() ? "—" : value;
  }

  private IntelligenceOutcome runIntelligenceLayer(
      String query,
      QueryClassification classification,
      LedgerSplit split,
      Map<String, Object> context,
      InvestigationProgressSink progress) {
    Map<String, Object> meta = new LinkedHashMap<>();
    PipelineHealthFacts health = computePipelineHealth(split);
    meta.put("intelligenceModel", INTELLIGENCE_MODEL_ID);
    meta.put("eligibleDealCount", health.eligibleDealCount());
    meta.put("demoDealCount", health.demoDealCount());
    meta.put("highProbabilityDealCount", health.highProbabilityDealCount());
    meta.put("maxEligibleProbability", health.maxEligibleProbability());
    meta.put("demoHeavy", health.demoHeavy());
    meta.put("thinPipeline", health.thinPipeline());

    if (split.eligible().isEmpty()) {
      String message =
          split.demoOnly().isEmpty()
              ? "No active Zoho deals were found in the local index."
              : "Only demo/sample deals are indexed. No eligible deals are available for a primary answer.";
      return new IntelligenceOutcome(
          new InvestigationAnswer(
              "Insufficient data",
              null,
              Map.of(),
              List.of(message),
              Map.of(),
              INTELLIGENCE_MODEL_ID,
              "insufficient_data",
              AnswerIntent.REASON.name(),
              message),
          meta);
    }

    String raw =
        streamAnswer(
            intelligenceUserPrompt(query, classification, split, health, context),
            "",
            INTELLIGENCE_SYSTEM_PROMPT,
            progress);
    Optional<IntelligenceAnswer> parsed = parseIntelligenceAnswer(raw);
    GuardedIntelligence guarded =
        applyInvariantGuard(query, classification, split, health, parsed.orElse(null));
    meta.put("intelligenceGuard", guarded.status());
    if (guarded.crossCheckApplied()) {
      meta.put("numericCrossCheck", true);
    }

    if (!guarded.success()) {
      InvestigationAnswer graceful = gracefulLedgerFallback(split, health, guarded.message());
      meta.put("intelligenceGuard", "graceful_fallback");
      meta.put("intelligenceGuardReason", guarded.message());
      return new IntelligenceOutcome(graceful, meta);
    }

    List<DealLedgerEntry> primaries = guarded.primaryEntries();
    if (primaries.isEmpty()) {
      meta.put("intelligenceGuard", "insufficient_evidence");
      return new IntelligenceOutcome(
          new InvestigationAnswer(
              "Insufficient evidence",
              null,
              Map.of(),
              guarded.reasons().isEmpty()
                  ? List.of("Indexed CRM evidence is insufficient to answer this question.")
                  : List.copyOf(guarded.reasons()),
              Map.of(),
              INTELLIGENCE_MODEL_ID,
              "insufficient_data",
              intentFromClassification(classification).name(),
              guarded.summary()),
          meta);
    }

    DealLedgerEntry first = primaries.getFirst();
    Map<String, String> primaryFields = new LinkedHashMap<>(first.primaryFields());
    List<String> reasons = new ArrayList<>(guarded.reasons());
    if (!guarded.peers().isEmpty()) {
      reasons.add("Related peers: " + String.join(", ", guarded.peers()) + ".");
    }
    if (primaries.size() > 1) {
      reasons.add(
          0,
          "Primary matches: "
              + primaries.stream().map(DealLedgerEntry::name).collect(Collectors.joining(", "))
              + ".");
    }

    String recordName =
        primaries.size() == 1
            ? first.name()
            : primaries.stream().map(DealLedgerEntry::name).collect(Collectors.joining(", "));

    return new IntelligenceOutcome(
        new InvestigationAnswer(
            titleFor(classification),
            recordName,
            primaryFields,
            List.copyOf(reasons),
            Map.of(),
            INTELLIGENCE_MODEL_ID,
            "structured",
            intentFromClassification(classification).name(),
            guarded.summary()),
        meta);
  }

  /**
   * C4 — when the intelligence guard rejects an open-ended pipeline question, return a usable
   * list of eligible deals instead of a sole "could not ground / please rephrase" dead-end.
   */
  static InvestigationAnswer gracefulLedgerFallback(
      LedgerSplit split, PipelineHealthFacts health, String guardMessage) {
    List<DealLedgerEntry> eligible =
        split == null || split.eligible() == null ? List.of() : split.eligible();
    if (eligible.isEmpty()) {
      String message =
          "No eligible Zoho deals are indexed for this question. Connect Zoho or name a deal that exists in the index.";
      return new InvestigationAnswer(
          "Insufficient data",
          null,
          Map.of(),
          List.of(message),
          Map.of(),
          INTELLIGENCE_MODEL_ID,
          TRUST_INSUFFICIENT,
          AnswerIntent.REASON.name(),
          message);
    }
    List<DealLedgerEntry> ranked =
        eligible.stream()
            .sorted(
                Comparator.comparingDouble(DealLedgerEntry::probability)
                    .reversed()
                    .thenComparing(DealLedgerEntry::name, Comparator.nullsLast(String::compareTo)))
            .limit(5)
            .toList();
    DealLedgerEntry first = ranked.getFirst();
    StringBuilder summary = new StringBuilder();
    summary.append("Here are eligible deals that look most relevant right now:\n");
    for (DealLedgerEntry entry : ranked) {
      String stage =
          entry.primaryFields() == null
              ? ""
              : entry.primaryFields().getOrDefault("Stage", "");
      summary
          .append("- ")
          .append(entry.name())
          .append(" (")
          .append(String.format(Locale.ROOT, "%.0f", entry.probability()))
          .append("%");
      if (stage != null && !stage.isBlank()) {
        summary.append(", ").append(stage);
      }
      summary.append(")\n");
    }
    summary.append("Ask about one of these by name for a grounded cross-source answer.");
    if (guardMessage != null && !guardMessage.isBlank()) {
      summary.append(" (").append(guardMessage.trim()).append(')');
    }
    List<String> reasons = new ArrayList<>();
    reasons.add(
        "Graceful ledger fallback — listed "
            + ranked.size()
            + " of "
            + (health == null ? eligible.size() : health.eligibleDealCount())
            + " eligible deal(s).");
    return new InvestigationAnswer(
        "Eligible deals",
        first.name(),
        new LinkedHashMap<>(first.primaryFields() == null ? Map.of() : first.primaryFields()),
        reasons,
        Map.of(),
        INTELLIGENCE_MODEL_ID,
        TRUST_STRUCTURED,
        AnswerIntent.REASON.name(),
        summary.toString().trim());
  }

  /**
   * Enforces two anti-hallucination invariants on intelligence-layer output:
   * <ol>
   *   <li>Demo exclusion — primary picks must not be demo/sample deals.</li>
   *   <li>Ledger membership — primary picks must be exact names from the eligible
   *       non-demo ledger (blocks confabulated, blended, or out-of-index deal names).</li>
   * </ol>
   * Also applies a deterministic numeric-superlative cross-check when the query is an
   * explicit max/min over a structured field.
   */
  static GuardedIntelligence applyInvariantGuard(
      String query,
      QueryClassification classification,
      LedgerSplit split,
      IntelligenceAnswer parsed) {
    return applyInvariantGuard(
        query, classification, split, computePipelineHealth(split), parsed);
  }

  static GuardedIntelligence applyInvariantGuard(
      String query,
      QueryClassification classification,
      LedgerSplit split,
      PipelineHealthFacts health,
      IntelligenceAnswer parsed) {
    if (parsed == null || parsed.summary().isBlank()) {
      return GuardedIntelligence.fail(
          "Could not ground an answer against the eligible deals. Please rephrase the question.");
    }

    List<DealLedgerEntry> resolved = new ArrayList<>();
    for (String name : parsed.primaryRecords()) {
      Optional<DealLedgerEntry> match = findLedgerEntry(split.eligible(), name);
      if (match.isEmpty()) {
        if (findLedgerEntry(split.demoOnly(), name).isPresent()) {
          return GuardedIntelligence.fail(
              "The model selected a demo/sample deal as the primary answer. Demo records are excluded from primary recommendations. Please rephrase or ask about a specific eligible deal.");
        }
        return GuardedIntelligence.fail(
            "The answer referenced \""
                + name
                + "\", which is not in the indexed eligible deals (hallucinated or unknown deal). Please rephrase or name a deal that exists in the index.");
      }
      resolved.add(match.get());
    }

    if (resolved.isEmpty()) {
      if (admitsInsufficientEvidence(parsed.summary())) {
        return GuardedIntelligence.ok(
            List.of(),
            parsed.summary(),
            parsed.reasons(),
            List.of(),
            false);
      }
      if (isSelectionQuestion(classification)) {
        return GuardedIntelligence.fail(
            "No eligible deal was selected for this question. Please rephrase.");
      }
    }

    boolean crossCheckApplied = false;
    boolean structuralCrossCheck = false;
    List<String> reasons = new ArrayList<>(parsed.reasons());
    String summary = parsed.summary();

    NextStepRepair nextStepRepair = repairFabricatedNextSteps(parsed, resolved);
    if (nextStepRepair.repaired()) {
      crossCheckApplied = true;
      summary = nextStepRepair.summary();
      reasons = new ArrayList<>(nextStepRepair.reasons());
      reasons.add(
          "Invented Next Step wording was relabeled as Recommendation (CRM Next Step unchanged).");
    }

    if (health.thinPipeline() && claimsHealthyHighProbabilityPipeline(parsed)) {
      return GuardedIntelligence.fail(
          "The answer claimed a high-probability pipeline, but no eligible deal is at or above "
              + (int) HIGH_PROBABILITY_THRESHOLD
              + "%. Please rephrase.");
    }

    if (isNumericSuperlative(query, classification) && classification.field() != null) {
      Optional<DealLedgerEntry> extreme =
          deterministicExtreme(
              split.eligible(),
              classification.field(),
              classification.direction() == null ? "desc" : classification.direction());
      if (extreme.isPresent()) {
        crossCheckApplied = true;
        structuralCrossCheck = true;
        DealLedgerEntry expected = extreme.get();
        boolean matches =
            resolved.stream()
                .anyMatch(entry -> entry.name().equalsIgnoreCase(expected.name()));
        if (!matches) {
          resolved = List.of(expected);
          reasons = new ArrayList<>();
          reasons.add(
              "Deterministic "
                  + classification.field()
                  + " "
                  + (classification.direction() == null ? "desc" : classification.direction())
                  + " over eligible deals selected "
                  + expected.name()
                  + ".");
          summary =
              expected.name()
                  + " has the "
                  + ("asc".equalsIgnoreCase(classification.direction()) ? "lowest" : "highest")
                  + " "
                  + classification.field()
                  + " among eligible deals ("
                  + expected.primaryFields().getOrDefault(classification.field(), "n/a")
                  + ").";
        }
      }
    }

    if (!structuralCrossCheck
        && isFollowUpQualifyCue(query == null ? "" : query.toLowerCase(Locale.ROOT))
        && !resolved.isEmpty()
        && !split.eligible().isEmpty()) {
      List<DealLedgerEntry> orderedForFollowUp =
          sortEligibleForQuestion(query, classification, split.eligible());
      DealLedgerEntry top = orderedForFollowUp.get(0);
      DealLedgerEntry pick = resolved.get(0);
      if (pick.qualificationScore() + 0.15 < top.qualificationScore()) {
        crossCheckApplied = true;
        structuralCrossCheck = true;
        resolved = List.of(top);
        reasons = new ArrayList<>();
        reasons.add(
            "Follow-up ranking preferred "
                + top.name()
                + " (qualification score "
                + String.format(Locale.ROOT, "%.3f", top.qualificationScore())
                + ") over a lower-scoring model pick.");
        summary =
            top.name()
                + " is the strongest follow-up candidate among eligible deals (score "
                + String.format(Locale.ROOT, "%.3f", top.qualificationScore())
                + "; close "
                + top.primaryFields().getOrDefault("Close Date", "n/a")
                + "; probability "
                + top.primaryFields().getOrDefault("Probability", "n/a")
                + ").";
      }
    }

    if (isOrderedSelectionIntent(classification) && resolved.size() > 1) {
      List<DealLedgerEntry> ordered =
          sortEligibleForQuestion(query, classification, split.eligible());
      List<DealLedgerEntry> repaired = reorderResolvedToMatch(ordered, resolved);
      if (!sameEntryOrder(resolved, repaired)) {
        crossCheckApplied = true;
        resolved = repaired;
        reasons.add("Reordered the deals listed here to match their true CRM field order.");
      }
    }

    if (claimsBrokenNumericSort(summary, resolved)) {
      crossCheckApplied = true;
      List<DealLedgerEntry> ordered =
          sortEligibleForQuestion(query, classification, split.eligible());
      resolved = reorderResolvedToMatch(ordered, resolved);
      reasons.add(
          "The order the answer described did not match the deals' actual CRM values; the list was"
              + " corrected from those values.");
    }

    LinkedHashSet<String> peers = new LinkedHashSet<>();
    for (String peer : parsed.peers()) {
      findLedgerEntry(split.eligible(), peer).ifPresent(entry -> peers.add(entry.name()));
    }

    return GuardedIntelligence.ok(resolved, summary, reasons, List.copyOf(peers), crossCheckApplied);
  }

  static PipelineHealthFacts computePipelineHealth(LedgerSplit split) {
    int eligible = split == null || split.eligible() == null ? 0 : split.eligible().size();
    int demo = split == null || split.demoOnly() == null ? 0 : split.demoOnly().size();
    double maxProb = 0.0;
    int highProb = 0;
    int missingContacts = 0;
    int missingAccountDescription = 0;
    int missingNotes = 0;
    int missingActivities = 0;
    double openAmount = 0.0;
    Set<String> accounts = new LinkedHashSet<>();
    Map<String, Integer> stages = new LinkedHashMap<>();
    if (split != null && split.eligible() != null) {
      for (DealLedgerEntry entry : split.eligible()) {
        double probability = entry.probability();
        if (!Double.isNaN(probability)) {
          maxProb = Math.max(maxProb, probability);
          if (probability >= HIGH_PROBABILITY_THRESHOLD) {
            highProb++;
          }
        }
        if (!Double.isNaN(entry.amountValue()) && entry.amountValue() > 0) {
          openAmount += entry.amountValue();
        }
        String account = entry.accountName();
        if (account == null || account.isBlank()) {
          account = entry.primaryFields().getOrDefault("Account", "");
        }
        if (account != null && !account.isBlank()) {
          accounts.add(account.trim());
        }
        String stage = entry.primaryFields().getOrDefault("Stage", "Unknown");
        if (stage == null || stage.isBlank()) {
          stage = "Unknown";
        }
        stages.merge(stage, 1, Integer::sum);
        if (entry.flags().contains("no_contacts")) {
          missingContacts++;
        }
        if (entry.flags().contains("missing_account_description")) {
          missingAccountDescription++;
        }
        if (entry.flags().contains("no_notes")) {
          missingNotes++;
        }
        if (entry.flags().contains("no_activities")) {
          missingActivities++;
        }
      }
    }
    boolean demoHeavy = demo >= 3 && eligible <= 2;
    boolean thinPipeline = eligible > 0 && highProb == 0;
    return new PipelineHealthFacts(
        eligible,
        demo,
        highProb,
        maxProb,
        demoHeavy,
        thinPipeline,
        Map.copyOf(stages),
        missingContacts,
        missingAccountDescription,
        missingNotes,
        missingActivities,
        openAmount,
        accounts.size());
  }

  static boolean claimsFabricatedNextStep(
      IntelligenceAnswer parsed, List<DealLedgerEntry> resolved) {
    return repairFabricatedNextSteps(parsed, resolved).repaired();
  }

  /**
   * Relabels invented {@code Next Step: …} claims as {@code Recommendation:} instead of failing the
   * whole answer. CRM-authored Next Step values that match the ledger are left unchanged.
   */
  static NextStepRepair repairFabricatedNextSteps(
      IntelligenceAnswer parsed, List<DealLedgerEntry> resolved) {
    if (parsed == null) {
      return NextStepRepair.unchanged("", List.of());
    }
    String summary = parsed.summary() == null ? "" : parsed.summary();
    List<String> reasons =
        parsed.reasons() == null ? List.of() : new ArrayList<>(parsed.reasons());
    String repairedSummary = rewriteFabricatedNextStepText(summary, resolved);
    List<String> repairedReasons = new ArrayList<>();
    boolean changed = !repairedSummary.equals(summary);
    for (String reason : reasons) {
      String rewritten = rewriteFabricatedNextStepText(reason, resolved);
      repairedReasons.add(rewritten);
      if (!rewritten.equals(reason)) {
        changed = true;
      }
    }
    if (!changed) {
      return NextStepRepair.unchanged(summary, reasons);
    }
    return new NextStepRepair(true, repairedSummary, repairedReasons);
  }

  private static String rewriteFabricatedNextStepText(
      String text, List<DealLedgerEntry> resolved) {
    if (text == null || text.isBlank()) {
      return text == null ? "" : text;
    }
    Matcher matcher =
        Pattern.compile("(?i)next\\s*step\\s*[:\\-]?\\s*([^.;\\n]+)").matcher(text);
    StringBuffer out = new StringBuffer();
    boolean any = false;
    while (matcher.find()) {
      String claimed = matcher.group(1).trim();
      if (claimed.isBlank() || isGroundedNextStep(claimed, resolved)) {
        matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group(0)));
        continue;
      }
      any = true;
      matcher.appendReplacement(
          out, Matcher.quoteReplacement("Recommendation: " + claimed));
    }
    if (!any) {
      return text;
    }
    matcher.appendTail(out);
    return out.toString();
  }

  private static boolean isGroundedNextStep(String claimed, List<DealLedgerEntry> resolved) {
    if (claimed == null || claimed.isBlank() || resolved == null) {
      return false;
    }
    return resolved.stream()
        .map(entry -> entry.primaryFields().getOrDefault("Next Step", ""))
        .anyMatch(
            next ->
                !next.isBlank()
                    && (next.equalsIgnoreCase(claimed)
                        || claimed.toLowerCase(Locale.ROOT).contains(next.toLowerCase(Locale.ROOT))
                        || next.toLowerCase(Locale.ROOT)
                            .contains(claimed.toLowerCase(Locale.ROOT))));
  }

  /**
   * Deterministic ordering for rank/qualify/reason. Healthiest-first by default; risk-first when
   * the question asks for most at-risk without a healthiest→risk framing.
   */
  static List<DealLedgerEntry> sortEligibleForQuestion(
      String query, QueryClassification classification, List<DealLedgerEntry> eligible) {
    if (eligible == null || eligible.isEmpty()) {
      return List.of();
    }
    List<DealLedgerEntry> copy = new ArrayList<>(eligible);
    if (classification != null
        && "rank".equals(classification.intent())
        && classification.field() != null
        && !classification.field().isBlank()) {
      boolean descending =
          classification.direction() == null
              || !classification.direction().equalsIgnoreCase("asc");
      String field = classification.field();
      copy.sort(
          (a, b) -> {
            int cmp = compareField(a, b, field);
            return descending ? -cmp : cmp;
          });
      return List.copyOf(copy);
    }
    if (prefersRiskFirst(query)) {
      copy.sort(
          Comparator.comparingDouble(InvestigationService::riskScore)
              .reversed()
              .thenComparing(DealLedgerEntry::name, String.CASE_INSENSITIVE_ORDER));
    } else {
      copy.sort(
          Comparator.comparingDouble(DealLedgerEntry::qualificationScore)
              .reversed()
              .thenComparing(DealLedgerEntry::name, String.CASE_INSENSITIVE_ORDER));
    }
    return List.copyOf(copy);
  }

  static boolean prefersRiskFirst(String query) {
    if (query == null || query.isBlank()) {
      return false;
    }
    String lower = query.toLowerCase(Locale.ROOT);
    if (lower.contains("healthiest")
        || lower.contains("healthiest to")
        || lower.contains("from healthiest")
        || lower.contains("least at risk")) {
      return false;
    }
    return lower.contains("most at risk")
        || lower.contains("at risk")
        || lower.contains("riskiest")
        || lower.contains("least healthy")
        || lower.contains("unhealthiest");
  }

  static double riskScore(DealLedgerEntry entry) {
    if (entry == null) {
      return 0.0;
    }
    double score = 0.0;
    List<String> flags = entry.flags() == null ? List.of() : entry.flags();
    if (flags.contains("overdue") || flags.contains("close_overdue")) {
      score += 2.0;
    }
    if (flags.contains("stale_record") || flags.contains("stale")) {
      score += 1.0;
    }
    if (flags.contains("no_notes") || flags.contains("no_activities") || flags.contains("no_contacts")) {
      score += 0.5;
    }
    double probability = Double.isNaN(entry.probability()) ? 0.0 : entry.probability();
    score += Math.max(0.0, (100.0 - probability) / 100.0);
    return score;
  }

  private static int compareField(DealLedgerEntry a, DealLedgerEntry b, String field) {
    String normalized = field == null ? "" : field.trim().toLowerCase(Locale.ROOT);
    if (normalized.contains("prob")) {
      return Double.compare(a.probability(), b.probability());
    }
    if (normalized.contains("amount") || normalized.contains("revenue")) {
      double av = Double.isNaN(a.amountValue()) ? Double.NEGATIVE_INFINITY : a.amountValue();
      double bv = Double.isNaN(b.amountValue()) ? Double.NEGATIVE_INFINITY : b.amountValue();
      return Double.compare(av, bv);
    }
    if (normalized.contains("modified")) {
      Instant am = a.modified();
      Instant bm = b.modified();
      if (am == null && bm == null) {
        return 0;
      }
      if (am == null) {
        return -1;
      }
      if (bm == null) {
        return 1;
      }
      return am.compareTo(bm);
    }
    if (normalized.contains("close")) {
      String as = a.primaryFields().getOrDefault("Close Date", "");
      String bs = b.primaryFields().getOrDefault("Close Date", "");
      return as.compareToIgnoreCase(bs);
    }
    return Double.compare(a.qualificationScore(), b.qualificationScore());
  }

  static boolean isOrderedSelectionIntent(QueryClassification classification) {
    return classification != null
        && List.of("rank", "qualify", "reason").contains(classification.intent());
  }

  static List<DealLedgerEntry> reorderResolvedToMatch(
      List<DealLedgerEntry> ordered, List<DealLedgerEntry> resolved) {
    if (ordered == null || ordered.isEmpty() || resolved == null || resolved.isEmpty()) {
      return resolved == null ? List.of() : List.copyOf(resolved);
    }
    Set<String> selected =
        resolved.stream()
            .map(e -> e.name().trim().toLowerCase(Locale.ROOT))
            .collect(Collectors.toCollection(LinkedHashSet::new));
    List<DealLedgerEntry> repaired = new ArrayList<>();
    for (DealLedgerEntry entry : ordered) {
      if (entry.name() != null
          && selected.contains(entry.name().trim().toLowerCase(Locale.ROOT))) {
        repaired.add(entry);
      }
    }
    if (repaired.isEmpty()) {
      return List.copyOf(resolved);
    }
    return List.copyOf(repaired);
  }

  static boolean sameEntryOrder(List<DealLedgerEntry> a, List<DealLedgerEntry> b) {
    if (a == null || b == null || a.size() != b.size()) {
      return false;
    }
    for (int i = 0; i < a.size(); i++) {
      if (!a.get(i).name().equalsIgnoreCase(b.get(i).name())) {
        return false;
      }
    }
    return true;
  }

  /**
   * Detects summaries that claim a probability sort but cite percentages out of monotonic order.
   */
  static boolean claimsBrokenNumericSort(String summary, List<DealLedgerEntry> resolved) {
    if (summary == null || summary.isBlank() || resolved == null || resolved.size() < 2) {
      return false;
    }
    String lower = summary.toLowerCase(Locale.ROOT);
    boolean claimsProbSort =
        (lower.contains("probability") || lower.contains("prob %") || lower.contains("%"))
            && (lower.contains("rank")
                || lower.contains("sorted")
                || lower.contains("descending")
                || lower.contains("ascending")
                || lower.contains("order")
                || lower.contains("highest")
                || lower.contains("lowest"));
    if (!claimsProbSort) {
      return false;
    }
    List<Double> probs = new ArrayList<>();
    for (DealLedgerEntry entry : resolved) {
      if (!Double.isNaN(entry.probability())) {
        probs.add(entry.probability());
      }
    }
    if (probs.size() < 2) {
      return false;
    }
    boolean descOk = true;
    boolean ascOk = true;
    for (int i = 1; i < probs.size(); i++) {
      if (probs.get(i) > probs.get(i - 1) + 0.01) {
        descOk = false;
      }
      if (probs.get(i) < probs.get(i - 1) - 0.01) {
        ascOk = false;
      }
    }
    return !descOk && !ascOk;
  }

  static boolean claimsCorpusCompleteness(String summary) {
    if (summary == null || summary.isBlank()) {
      return false;
    }
    String lower = summary.toLowerCase(Locale.ROOT);
    if (lower.contains("partial")
        || lower.contains("sampled")
        || lower.contains("retrieved evidence")
        || lower.contains("top-k")
        || lower.contains("not a full")) {
      return false;
    }
    return lower.contains("all accounts")
        || lower.contains("all deals")
        || lower.contains("all companies")
        || lower.contains("entire pipeline")
        || lower.contains("every account")
        || lower.contains("every deal")
        || lower.contains("complete list of accounts")
        || lower.contains("complete list of deals");
  }

  static InvestigationAnswer guardGeneralCompletenessClaims(InvestigationAnswer answer) {
    if (answer == null || answer.summaryText() == null || answer.summaryText().isBlank()) {
      return answer;
    }
    if (!claimsCorpusCompleteness(answer.summaryText())) {
      return answer;
    }
    String rewritten =
        "Partial result from retrieved evidence (not a full CRM inventory): "
            + answer.summaryText();
    List<String> reasons = new ArrayList<>(answer.reasons() == null ? List.of() : answer.reasons());
    reasons.add(
        "Completeness claim rewritten: general-path answers cannot assert a full corpus inventory.");
    return new InvestigationAnswer(
        answer.title(),
        answer.recordName(),
        answer.primaryFields(),
        reasons,
        answer.scoreBreakdown(),
        answer.modelId(),
        answer.trustState(),
        answer.intent(),
        rewritten);
  }

  static boolean claimsHealthyHighProbabilityPipeline(IntelligenceAnswer parsed) {
    if (parsed == null) {
      return false;
    }
    String blob =
        ((parsed.summary() == null ? "" : parsed.summary())
                + " "
                + String.join(" ", parsed.reasons() == null ? List.of() : parsed.reasons()))
            .toLowerCase(Locale.ROOT);
    return (blob.contains("high-probability") || blob.contains("high probability"))
        && (blob.contains("pipeline") || blob.contains("deals"))
        && !(blob.contains("no high")
            || blob.contains("not high")
            || blob.contains("none")
            || blob.contains("no deals")
            || blob.contains("below 30")
            || blob.contains("under 30")
            || blob.contains("needs development")
            || blob.contains("needs more focus")
            || blob.contains("thin"));
  }

  static Optional<DealLedgerEntry> deterministicExtreme(
      List<DealLedgerEntry> eligible, String field, String direction) {
    if (eligible == null || eligible.isEmpty() || field == null) {
      return Optional.empty();
    }
    boolean descending = direction == null || !direction.equalsIgnoreCase("asc");
    String normalized = field.trim().toLowerCase(Locale.ROOT);
    Comparator<DealLedgerEntry> comparator;
    // Derived metric: expected value = amount x probability. MUST be compared as the product,
    // never aliased to raw amount via the "revenue" substring below ("expected_revenue" bug).
    if (normalized.contains("expected") || normalized.equals("ev")) {
      List<DealLedgerEntry> comparable =
          eligible.stream()
              .filter(entry -> !Double.isNaN(entry.amountValue()))
              .toList();
      if (comparable.isEmpty()) {
        return Optional.empty();
      }
      comparator =
          Comparator.comparingDouble(
              entry ->
                  entry.amountValue()
                      * (Double.isNaN(entry.probability()) ? 0.0 : entry.probability())
                      / 100.0);
      Comparator<DealLedgerEntry> withName = comparator.thenComparing(DealLedgerEntry::name);
      return descending
          ? comparable.stream().max(withName)
          : comparable.stream().min(withName);
    }
    if (normalized.contains("prob")) {
      comparator = Comparator.comparingDouble(DealLedgerEntry::probability);
    } else if (normalized.contains("amount") || normalized.equals("revenue")) {
      comparator =
          Comparator.comparingDouble(
              entry -> Double.isNaN(entry.amountValue()) ? Double.NEGATIVE_INFINITY : entry.amountValue());
    } else if (normalized.contains("modified")) {
      comparator =
          Comparator.comparing(
              entry -> entry.modified() == null ? java.time.Instant.EPOCH : entry.modified());
    } else if (normalized.contains("close")) {
      comparator =
          Comparator.comparing(
              entry -> entry.primaryFields().getOrDefault("Close Date", ""),
              String.CASE_INSENSITIVE_ORDER);
    } else {
      return Optional.empty();
    }
    Comparator<DealLedgerEntry> withName = comparator.thenComparing(DealLedgerEntry::name);
    return descending
        ? eligible.stream().max(withName)
        : eligible.stream().min(withName);
  }

  static boolean isNumericSuperlative(String query, QueryClassification classification) {
    if (!"rank".equals(classification.intent()) || classification.field() == null) {
      return false;
    }
    if (query == null) {
      return true;
    }
    String normalized = query.toLowerCase(Locale.ROOT);
    return normalized.contains("highest")
        || normalized.contains("lowest")
        || normalized.contains("greatest")
        || normalized.contains("largest")
        || normalized.contains("smallest")
        || normalized.contains("max")
        || normalized.contains("min")
        || normalized.contains("high ")
        || normalized.contains("top ");
  }

  private static boolean isSelectionQuestion(QueryClassification classification) {
    return List.of("rank", "qualify", "reason").contains(classification.intent());
  }

  /** True when the model summary refuses to answer due to missing CRM evidence. */
  static boolean admitsInsufficientEvidence(String summary) {
    if (summary == null || summary.isBlank()) {
      return false;
    }
    String lower = summary.toLowerCase(Locale.ROOT);
    return lower.contains("insufficient")
        || lower.contains("not enough")
        || lower.contains("does not contain")
        || lower.contains("don't have enough")
        || lower.contains("do not have enough")
        || lower.contains("cannot confirm")
        || lower.contains("can't confirm")
        || lower.contains("no indexed")
        || lower.contains("missing from the ledger")
        || lower.contains("not present in the");
  }

  private static String titleFor(QueryClassification classification) {
    return switch (classification.intent()) {
      case "rank" -> "Ranked deal answer";
      case "qualify" -> "Most qualified deal";
      case "reason" -> "CRM intelligence answer";
      default -> "CRM intelligence answer";
    };
  }

  private QueryClassification classifyQuery(
      String query, Map<String, Object> context, InvestigationProgressSink progress) {
    String raw =
        streamAnswer(
            classificationPrompt(query, context),
            "",
            "You classify CRM investigation questions into typed query plans. Respond with ONLY valid JSON.",
            progress);
    Optional<QueryClassification> parsed = parseClassification(raw);
    if (parsed.isPresent()) {
      return parsed.get();
    }
    log.debug("Classification parse failed; falling back to safe lookup path");
    return QueryClassification.unparsed();
  }

  /**
   * Post-classification validation at the LLM classifier seam.
   *
   * <p>The classifier is the one non-deterministic step left in the pipeline, and the same
   * question can land on different intents across runs — the original F1 failure was exactly
   * this: "general" answered the deal ledger while "reason" refused it. On stage that means a
   * question that answered in rehearsal can refuse live.
   *
   * <p>When a classification lands on an {@link #UNSTABLE_INTENTS unstable} shape, re-classify
   * once and reconcile. Reconciliation reads only the classified intent enum and field — never
   * the raw query — so the "structured answers are keyed only by the intent enum" invariant
   * holds; this widens nothing, it only settles a coin flip the classifier already made.
   */
  private QueryClassification stabilizeClassification(
      String query,
      Map<String, Object> context,
      InvestigationProgressSink progress,
      QueryClassification first) {
    if (!needsStabilityCheck(first)) {
      return first;
    }
    QueryClassification second;
    try {
      second = classifyQuery(query, context, progress);
    } catch (RuntimeException e) {
      log.debug("Stability re-classification unavailable; keeping first route: {}", e.toString());
      return first;
    }
    QueryClassification chosen = reconcileClassifications(first, second);
    if (!chosen.intent().equals(first.intent())) {
      log.debug("Classifier route unstable: {} vs {}; chose {}",
          first.intent(), second.intent(), chosen.intent());
    }
    return chosen;
  }

  /**
   * Shapes that flip between an answering route and a refusing route across runs. "general" and
   * "reason" are the pair behind F1; a confident "rank" with no field cannot be executed at all,
   * so it is worth a second read before falling through to clarification.
   */
  private static final Set<String> UNSTABLE_INTENTS = Set.of("general", "reason", "lookup");

  static boolean needsStabilityCheck(QueryClassification classification) {
    if (classification == null || !classification.parsed()) {
      return false;
    }
    if (classification.confident() && UNSTABLE_INTENTS.contains(classification.intent())) {
      return true;
    }
    // Confident "rank" without a field is unexecutable — it can only clarify.
    return classification.confident()
        && "rank".equals(classification.intent())
        && (classification.field() == null || classification.field().isBlank());
  }

  /**
   * Prefer the route that can actually answer. A known-shape ledger question that one run reads
   * as deterministic and another reads as free-form should take the deterministic reading; ties
   * fall back to the higher-confidence classification, then to the first.
   */
  static QueryClassification reconcileClassifications(
      QueryClassification first, QueryClassification second) {
    if (second == null || !second.parsed()) {
      return first;
    }
    if (first == null || !first.parsed()) {
      return second;
    }
    if (first.intent().equals(second.intent())) {
      // Same route both times — keep whichever carries the field/entity detail needed to execute.
      return routeDetail(second) > routeDetail(first) ? second : first;
    }
    int firstRank = routeStrength(first);
    int secondRank = routeStrength(second);
    if (firstRank != secondRank) {
      return firstRank > secondRank ? first : second;
    }
    return second.confidence() > first.confidence() ? second : first;
  }

  /** 2 = deterministic structured answer, 1 = structured CRM path, 0 = free-form. */
  private static int routeStrength(QueryClassification classification) {
    if (isDeterministicStructuredIntent(classification)) {
      return 2;
    }
    return shouldUseStructuredCrmPath(classification) ? 1 : 0;
  }

  private static int routeDetail(QueryClassification classification) {
    int detail = 0;
    if (classification.field() != null && !classification.field().isBlank()) {
      detail++;
    }
    if (classification.entity() != null && !classification.entity().isBlank()) {
      detail++;
    }
    if (classification.fieldValues() != null && !classification.fieldValues().isEmpty()) {
      detail++;
    }
    return detail;
  }

  static Optional<QueryClassification> parseClassification(String raw) {
    JsonNode root = parseJson(raw);
    if (root == null) {
      return Optional.empty();
    }
    String intent = root.path("intent").asText("").trim().toLowerCase(Locale.ROOT);
    if (intent.isBlank()) {
      return Optional.empty();
    }
    if (!CLASSIFIER_INTENTS.contains(intent)) {
      return Optional.empty();
    }
    String field = null;
    JsonNode fieldNode = root.get("field");
    if (fieldNode != null && !fieldNode.isNull()) {
      String value = fieldNode.asText("").trim();
      if (!value.isBlank() && !"null".equalsIgnoreCase(value)) {
        field = value;
      }
    }
    String direction = null;
    JsonNode directionNode = root.get("direction");
    if (directionNode != null && !directionNode.isNull()) {
      String value = directionNode.asText("").trim().toLowerCase(Locale.ROOT);
      if ("asc".equals(value) || "desc".equals(value)) {
        direction = value;
      }
    }
    String entity = null;
    JsonNode entityNode = root.get("entity");
    if (entityNode != null && !entityNode.isNull()) {
      String value = entityNode.asText("").trim();
      if (!value.isBlank() && !"null".equalsIgnoreCase(value)) {
        entity = value;
      }
    }
    double confidence = root.path("confidence").asDouble(0.0);
    if (confidence < 0) {
      confidence = 0;
    }
    if (confidence > 1) {
      confidence = 1;
    }
    String ambiguity = null;
    JsonNode ambiguityNode = root.get("ambiguityReason");
    if (ambiguityNode != null && !ambiguityNode.isNull()) {
      String value = ambiguityNode.asText("").trim();
      if (!value.isBlank() && !"null".equalsIgnoreCase(value)) {
        ambiguity = value;
      }
    }
    List<String> fieldValues = readStringArray(root.get("fieldValues"));
    return Optional.of(
        new QueryClassification(
            true, intent, field, direction, confidence, ambiguity, entity, fieldValues));
  }

  static Optional<IntelligenceAnswer> parseIntelligenceAnswer(String raw) {
    JsonNode root = parseJson(raw);
    if (root == null) {
      return Optional.empty();
    }
    String summary = root.path("summary").asText("").trim();
    if (summary.isBlank()) {
      return Optional.empty();
    }
    List<String> primaryRecords = new ArrayList<>();
    JsonNode primaryNode = root.get("primaryRecords");
    if (primaryNode != null && primaryNode.isArray()) {
      primaryNode.forEach(
          node -> {
            String name = node.asText("").trim();
            if (!name.isBlank() && !"null".equalsIgnoreCase(name)) {
              primaryRecords.add(name);
            }
          });
    }
    List<String> reasons = readStringArray(root.get("reasons"));
    List<String> peers = readStringArray(root.get("peers"));
    String understood = root.path("understoodIntent").asText("").trim();
    return Optional.of(
        new IntelligenceAnswer(understood, List.copyOf(primaryRecords), summary, reasons, peers));
  }

  private static List<String> readStringArray(JsonNode node) {
    List<String> values = new ArrayList<>();
    if (node != null && node.isArray()) {
      node.forEach(
          item -> {
            String value = item.asText("").trim();
            if (!value.isBlank()) {
              values.add(value);
            }
          });
    }
    return List.copyOf(values);
  }

  /**
   * Offline heuristic used only when callers still invoke deprecated keyword helpers in unit tests.
   * Production investigate() always prefers the LLM classifier.
   */
  static QueryClassification heuristicClassification(String query) {
    if (query == null || query.isBlank()) {
      return QueryClassification.unparsed();
    }
    String normalized = query.toLowerCase(Locale.ROOT);
    boolean dealScoped =
        normalized.contains("deal")
            || normalized.contains("opportunity")
            || normalized.contains("pipeline")
            || normalized.contains("account");
    if (normalized.contains("not know")
        || normalized.contains("don't know")
        || normalized.contains("do we not know")
        || normalized.contains("missing")
        || normalized.contains("gaps")) {
      return new QueryClassification(true, "reason", null, null, 0.92, null);
    }
    if (normalized.contains("stale")
        || normalized.contains("at risk")
        || normalized.contains("this week") && !isFollowUpQualifyCue(normalized)
        || normalized.contains("most important")
        || normalized.contains("push hardest")) {
      return new QueryClassification(true, "reason", null, null, 0.93, null);
    }
    if (isFollowUpQualifyCue(normalized)
        || normalized.contains("most qualified")
        || normalized.contains("best qualified")
        || normalized.contains("strongest active deal")
        || (normalized.contains("most valuable") && normalized.contains("deal"))) {
      return new QueryClassification(true, "qualify", null, null, 0.95, null);
    }
    if (dealScoped
        && (normalized.contains("probability")
            || normalized.contains("most likely")
            || normalized.contains("best chance")
            || normalized.contains("amount")
            || normalized.contains("deal size"))) {
      boolean rankingCue =
          normalized.contains("highest")
              || normalized.contains("greatest")
              || normalized.contains("largest")
              || normalized.contains("high ")
              || normalized.contains("best ")
              || normalized.contains("top ")
              || normalized.contains("most ")
              || normalized.contains("which");
      boolean lookupCue =
          normalized.contains(" on the ")
              || normalized.contains(" for ")
              || normalized.contains(" at ");
      if (rankingCue) {
        if (lookupCue
            && !normalized.contains("which")
            && !normalized.contains("highest")
            && !normalized.contains("best")
            && !normalized.contains("most")
            && !normalized.contains("largest")) {
          return new QueryClassification(true, "lookup", null, null, 0.9, null);
        }
        String field =
            normalized.contains("amount") || normalized.contains("size") || normalized.contains("largest")
                ? "Amount"
                : "Probability";
        return new QueryClassification(true, "rank", field, "desc", 0.9, null);
      }
    }
    return new QueryClassification(true, "lookup", null, null, 0.8, null);
  }

  private static boolean isFollowUpQualifyCue(String normalized) {
    return normalized.contains("follow up")
        || normalized.contains("follow-up")
        || normalized.contains("followup")
        || normalized.contains("next action")
        || normalized.contains("prioritize")
        || normalized.contains("best deal")
        || normalized.contains("which deal")
        || normalized.contains("focus on")
        || normalized.contains("work on next")
        || normalized.contains("close next")
        || (normalized.contains("pipeline")
            && (normalized.contains("prioritize")
                || normalized.contains("follow")
                || normalized.contains("focus")
                || normalized.contains("best")));
  }

  private static AnswerIntent intentFromClassification(QueryClassification classification) {
    if (!classification.parsed()) {
      return AnswerIntent.GENERAL;
    }
    return switch (classification.intent()) {
      case "rank" -> AnswerIntent.RANK;
      case "qualify" -> AnswerIntent.QUALIFICATION;
      case "reason", "corpus_staleness", "named_entity_staleness" -> AnswerIntent.REASON;
      default -> AnswerIntent.GENERAL;
    };
  }

  private static String classificationPrompt(String query, Map<String, Object> context) {
    return """
        Query: %s
        Context: %s

        Classify this CRM investigation question by SHAPE / INTENT (not by matching fixed phrases).
        Respond with ONLY valid JSON:
        {
          "intent": "rank" | "lookup" | "qualify" | "reason" | "enumerate" | "aggregate" | "corpus_staleness" | "named_entity_staleness" | "general",
          "field": "Probability" | "Amount" | "Expected_Revenue" | "Account_Exposure" | "Close Date" | "Modified" | "Stage" | null,
          "direction": "desc" | "asc" | null,
          "entity": "deal or account name when the question focuses on one entity, else null",
          "fieldValues": ["exact category values to filter by, e.g. Stage names, else []"],
          "confidence": 0.0-1.0,
          "ambiguityReason": "short reason or null"
        }

        Intent definitions (assign by meaning of the question):
        - enumerate: corpus inventory of accounts, companies, clients, or deals (list/show all/what are we tracking)
        - aggregate: corpus metric — sum, count, total/open pipeline value, how much pipeline is worth
        - corpus_staleness: which deals/accounts are stale, quiet, overdue, or inactive in CRM hygiene terms (no single focus entity)
        - named_entity_staleness: is a NAMED deal/account stale, quiet, overdue, or inactive as CRM hygiene (set entity). NOT "at risk" as sales judgment, NOT what a person did.
        - rank: explicit max/min by one structured field (highest probability, largest amount)
        - reason: sales judgment over gaps/risk (not pure stale-list or stale-named), e.g. most important, at risk, stalled
        - lookup: specific deal/account/contact narrative, conflict, person activity, or hygiene that is NOT CRM-staleness-shaped
        - qualify: prioritize / follow up / best deal to work next / most qualified
        - general: anything else

        Examples (enumerate — any wording with this shape):
        - list all accounts / which companies are we tracking / show every client

        Examples (enumerate with a Stage filter — question wants DEALS, not just account names,
        filtered/scoped to one or more pipeline stages; set field to "Stage" and fieldValues to the
        exact stage name(s) mentioned):
        - which deals are in Proposal or Price Quote stage and what are their probabilities →
          field: "Stage", fieldValues: ["Proposal", "Price Quote"]
        - show me every deal in Negotiation → field: "Stage", fieldValues: ["Negotiation"]

        Examples (aggregate — any wording with this shape):
        - total pipeline value / how much is open pipeline worth / sum up our deals
        - which account has the largest total exposure across all its deals →
          field: "Account_Exposure" (rollup sums an account's deals — never a single-deal rank)
        - how much are we exposed to Cascade Health Systems across their deals →
          field: "Account_Exposure"
        NOT aggregate (use general — these metrics are not open pipeline):
        - what is our Q3 churn rate and ARR / what is our MRR / net retention

        Examples (corpus_staleness):
        - which deals are going stale / what accounts have gone quiet

        Examples (named_entity_staleness — set entity to the named deal or account):
        - is the Acme expansion deal stale? / has TechNova gone quiet? / is this CRM record overdue?

        Examples (rank):
        - Which deal has the highest probability?
        - largest deal by amount

        Examples (reason):
        - What's going on with our most important deal?
        - What do we not know about our top account?
        - Is this deal at risk?

        Examples (lookup — NOT rank/reason/staleness):
        - what's the probability on the King deal
        - What contacts do we have at TechNova?
        - what did the AE do on this deal / is the named deal at risk and what did X do?
        - the Infosys digital transformation deal says the owner left — how should we handle
          follow-up? → intent: "lookup", entity: "Infosys Digital Transformation"
          (named deal with a specific record question → set entity so the ledger record is pinned)

        Examples (qualify):
        - What is the most qualified active deal?
        - Who should we follow up with this week?

        If ambiguous between rank and lookup, set confidence below 0.70.
        Prefer named_entity_staleness over lookup ONLY when the question is CRM hygiene (stale / gone quiet / overdue / inactive) for a named entity.
        Prefer lookup or reason when the question asks if a deal is "at risk", stalled, or what a person did — those need Slack/Notion narrative, not CRM stale flags.
        Prefer corpus_staleness over reason when the question is listing stale/quiet deals without picking a winner.
        """
        .formatted(query, context == null ? "{}" : context.toString());
  }

  private static String intelligenceUserPrompt(
      String query,
      QueryClassification classification,
      LedgerSplit split,
      PipelineHealthFacts health,
      Map<String, Object> context) {
    List<DealLedgerEntry> ordered =
        sortEligibleForQuestion(query, classification, split.eligible());
    String sortNote =
        prefersRiskFirst(query)
            ? "Ordered most at-risk / least qualified first (deterministic risk score). Do not reorder."
            : "Ordered healthiest / most qualified first (deterministic qualification score). Do not reorder.";
    return """
        Query: %s
        Context: %s
        Classified intent: %s
        Classified field: %s
        Classified direction: %s

        PipelineHealth (deterministic facts — use these for aggregate claims):
        %s

        Ordered eligible non-demo ledger (%s)
        ONLY these deals may appear in primaryRecords; preserve this relative order:
        %s

        Respond with ONLY valid JSON:
        {
          "understoodIntent": "short restatement of what the user asked",
          "primaryRecords": ["exact deal name(s) from the Ordered ledger, preserving relative order, or [] if insufficient"],
          "summary": "2-4 sentence grounded answer, or clearly state insufficient evidence",
          "reasons": ["why these, citing probability/stage/close date/gaps/next step/notes"],
          "peers": ["other eligible deals sharing the same signal"]
        }
        If the ledger cannot answer without outside knowledge, use primaryRecords: [] and say so.
        Label CRM Next Step as a CRM fact; label coaching as Recommendation — never invent Next Step values.
        """
        .formatted(
            query,
            context == null ? "{}" : context.toString(),
            classification.intent(),
            classification.field() == null ? "null" : classification.field(),
            classification.direction() == null ? "null" : classification.direction(),
            formatPipelineHealth(health),
            sortNote,
            formatLedgerTable(ordered));
  }

  static String formatPipelineHealth(PipelineHealthFacts health) {
    if (health == null) {
      return "(unavailable)";
    }
    return """
        eligibleDealCount=%d
        demoDealCount=%d
        eligibleAccountCount=%d
        eligibleOpenPipelineAmount=%.2f
        highProbabilityThreshold=%.0f
        highProbabilityDealCount=%d
        maxEligibleProbability=%.1f
        demoHeavy=%s
        thinPipeline=%s
        stageDistribution=%s
        missingContacts=%d
        missingAccountDescription=%d
        missingNotes=%d
        missingActivities=%d
        """
        .formatted(
            health.eligibleDealCount(),
            health.demoDealCount(),
            health.eligibleAccountCount(),
            health.eligibleOpenPipelineAmount(),
            HIGH_PROBABILITY_THRESHOLD,
            health.highProbabilityDealCount(),
            health.maxEligibleProbability(),
            health.demoHeavy(),
            health.thinPipeline(),
            health.stageDistribution(),
            health.missingContacts(),
            health.missingAccountDescription(),
            health.missingNotes(),
            health.missingActivities())
        .trim();
  }

  static String formatLedgerTable(List<DealLedgerEntry> entries) {
    if (entries == null || entries.isEmpty()) {
      return "(empty)";
    }
    StringBuilder table = new StringBuilder();
    for (DealLedgerEntry entry : entries) {
      Map<String, String> fields = entry.primaryFields();
      table
          .append("[DEAL] ")
          .append(entry.name())
          .append(" · Score: ")
          .append(String.format(Locale.ROOT, "%.3f", entry.qualificationScore()))
          .append(" · Stage: ")
          .append(fields.getOrDefault("Stage", "—"))
          .append(" · Amount: ")
          .append(fields.getOrDefault("Amount", "—"))
          .append(" · Probability: ")
          .append(fields.getOrDefault("Probability", "—"))
          .append(" · Close: ")
          .append(fields.getOrDefault("Close Date", "—"))
          .append(" · Modified: ")
          .append(fields.getOrDefault("Modified", "—"))
          .append(" · Account: ")
          .append(entry.accountName() == null || entry.accountName().isBlank()
              ? fields.getOrDefault("Account", "—")
              : entry.accountName())
          .append(" · Next Step: ")
          .append(fields.getOrDefault("Next Step", "—"))
          .append(" · Flags: ")
          .append(entry.flags().isEmpty() ? "none" : String.join(",", entry.flags()))
          .append('\n');
      String contacts = extractNarrativeSection(entry.narrative(), "Contacts:");
      if (!contacts.isBlank()) {
        table.append("  Contacts: ").append(contacts).append('\n');
      } else if (entry.flags().contains("no_contacts")) {
        table.append("  Contacts: none\n");
      }
      String notes = truncateForLedger(extractNarrativeSection(entry.narrative(), "Notes:"));
      if (!notes.isBlank()) {
        table.append("  Notes (unverified): ").append(notes).append('\n');
      } else if (entry.flags().contains("no_notes")) {
        table.append("  Notes: none\n");
      } else {
        table.append("  Notes: none\n");
      }
      if (entry.flags().contains("no_activities")) {
        table.append("  Activities: none\n");
      } else {
        String activities = truncateForLedger(extractNarrativeSection(entry.narrative(), "Activities:"));
        if (!activities.isBlank()) {
          table.append("  Activities: ").append(activities).append('\n');
        }
      }
    }
    return table.toString().trim();
  }

  /**
   * Pull bullet lines under a narrative section header (e.g. {@code Notes:}) until the next
   * section header or end of text.
   */
  static String extractNarrativeSection(String narrative, String header) {
    if (narrative == null || narrative.isBlank() || header == null || header.isBlank()) {
      return "";
    }
    String text = narrative.replace("\r\n", "\n");
    String marker = header.endsWith(":") ? header : header + ":";
    int start = indexOfIgnoreCase(text, marker);
    if (start < 0) {
      return "";
    }
    int bodyStart = start + marker.length();
    while (bodyStart < text.length()
        && (text.charAt(bodyStart) == ' ' || text.charAt(bodyStart) == '\n')) {
      bodyStart++;
    }
    int end = text.length();
    for (String next : List.of("\nNotes:", "\nActivities:", "\nContacts:", "\nDescription:")) {
      if (next.substring(1).equalsIgnoreCase(marker)) {
        continue;
      }
      int at = indexOfIgnoreCase(text.substring(bodyStart), next);
      if (at >= 0) {
        end = Math.min(end, bodyStart + at);
      }
    }
    String section = text.substring(bodyStart, end).trim();
    if (section.isBlank()) {
      return "";
    }
    return section
        .lines()
        .map(String::trim)
        .filter(line -> !line.isBlank())
        .map(line -> line.startsWith("- ") ? line.substring(2).trim() : line)
        .collect(Collectors.joining(" | "));
  }

  static String truncateForLedger(String value) {
    if (value == null || value.isBlank()) {
      return "";
    }
    String trimmed = value.trim().replaceAll("\\s+", " ");
    if (trimmed.length() <= LEDGER_NOTES_MAX_CHARS) {
      return trimmed;
    }
    return trimmed.substring(0, LEDGER_NOTES_MAX_CHARS - 1).trim() + "…";
  }

  private static int indexOfIgnoreCase(String haystack, String needle) {
    return haystack.toLowerCase(Locale.ROOT).indexOf(needle.toLowerCase(Locale.ROOT));
  }

  private InvestigationAnswer buildClarificationAnswer(QueryClassification classification) {
    String message =
        classification.ambiguityReason() == null || classification.ambiguityReason().isBlank()
            ? "The question is ambiguous between ranking all deals and looking up a specific record. Please rephrase (for example: \"which deal has the highest probability\" or \"what is the probability for King\")."
            : classification.ambiguityReason();
    return new InvestigationAnswer(
        "Clarification needed",
        null,
        Map.of(),
        List.of(message),
        Map.of(),
        "zoho-query-plan-v1",
        "insufficient_data",
        AnswerIntent.GENERAL.name(),
        message);
  }

  private InvestigationAnswer buildGeneralAnswer(
      String query,
      List<InvestigationEvidence> evidence,
      String details,
      Map<String, Object> context,
      InvestigationProgressSink progress) {
    return buildGeneralAnswer(query, evidence, details, context, progress, null, false);
  }

  private InvestigationAnswer buildGeneralAnswer(
      String query,
      List<InvestigationEvidence> evidence,
      String details,
      Map<String, Object> context,
      InvestigationProgressSink progress,
      String conflictMarker,
      boolean artifactPinned) {
    if (evidence.isEmpty()) {
      String narrative =
          streamAnswer(
              generalPrompt(query, details, context),
              "No structured evidence was returned for this query.",
              progress);
      return new InvestigationAnswer(
          "LLM final answer",
          null,
          Map.of(),
          List.of("No structured evidence was returned for this query."),
          Map.of(),
          "zoho-grounded-v1",
          "insufficient_data",
          AnswerIntent.GENERAL.name(),
          narrative);
    }

    String prompt =
        hasNonZohoEvidence(evidence)
            ? multiSourceGeneralPrompt(query, evidence, details, context, conflictMarker)
            : structuredGeneralPrompt(query, evidence, context, conflictMarker);
    String raw =
        streamAnswer(
            prompt,
            "Evidence retrieval completed, but answer synthesis is unavailable.",
            progress);
    if (raw == null || raw.isBlank()) {
      if (conflictMarker != null && !conflictMarker.isBlank()) {
        return deterministicConflictAnswer(evidence, conflictMarker);
      }
      return new InvestigationAnswer(
          "LLM final answer",
          null,
          Map.of(),
          List.of("Answer synthesis returned empty output."),
          Map.of(),
          "zoho-grounded-v1",
          TRUST_INSUFFICIENT,
          AnswerIntent.GENERAL.name(),
          "Evidence was retrieved, but the model returned an empty answer. Please retry or name the deal.");
    }
    Optional<StructuredGeneralAnswer> parsed = parseStructuredGeneral(raw);
    if (parsed.isPresent()) {
      StructuredGeneralAnswer finalAnswer = parsed.get();
      Optional<InvestigationEvidence> selected =
          findEvidenceByName(evidence, finalAnswer.selectedRecord());
      if (selected.isPresent()) {
        InvestigationEvidence match = selected.get();
        if (match.flags().contains(DEMO_FLAG)) {
          return new InvestigationAnswer(
              "LLM final answer",
              null,
              Map.of(),
              List.of(
                  "The retrieved match is demo/sample data and cannot be the primary answer.",
                  "Ask about a specific eligible deal, or rephrase for a ranking over eligible deals."),
              Map.of(),
              "zoho-grounded-v1",
              "insufficient_data",
              AnswerIntent.GENERAL.name(),
              "A demo/sample deal matched retrieval, but demo records are excluded from primary answers. Please rephrase or name an eligible deal.");
        }
        String summary =
            enforceConflictPostCheck(finalAnswer.summary(), conflictMarker, evidence);
        return new InvestigationAnswer(
            "LLM final answer",
            match.name(),
            primaryFieldsFromEvidence(match),
            finalAnswer.reasons(),
            Map.of(),
            "zoho-grounded-v1",
            groundedTrustState(
                conflictMarker, artifactPinned, false, summary, TRUST_STRUCTURED),
            AnswerIntent.GENERAL.name(),
            summary);
      }
      String summary =
          enforceConflictPostCheck(finalAnswer.summary(), conflictMarker, evidence);
      return new InvestigationAnswer(
          "LLM final answer",
          null,
          Map.of(),
          finalAnswer.reasons(),
          Map.of(),
          "zoho-grounded-v1",
          groundedTrustState(
              conflictMarker,
              artifactPinned,
              answerCitesEvidence(summary, evidence),
              summary,
              TRUST_INSUFFICIENT),
          AnswerIntent.GENERAL.name(),
          summary);
    }
    // The model sometimes returns a JSON envelope that fails to parse — most often because the
    // response hit the output-token cap mid-string. Echoing `raw` put a literal
    // {"selectedRecord": null, ... on the user's screen; salvage the prose instead.
    String narrative =
        enforceConflictPostCheck(salvageNarrative(raw), conflictMarker, evidence);
    return new InvestigationAnswer(
        "LLM final answer",
        null,
        Map.of(),
        List.of("Answer narrative only — no single indexed record was confidently selected."),
        Map.of(),
        "zoho-grounded-v1",
        groundedTrustState(
            conflictMarker,
            artifactPinned,
            answerCitesEvidence(narrative, evidence),
            narrative,
            TRUST_INSUFFICIENT),
        AnswerIntent.GENERAL.name(),
        narrative);
  }

  /**
   * Required Phase 2.2 post-check: if a conflict marker is present but the model omitted a side,
   * replace with a deterministic conflict template so the stage narrative never silently degrades.
   */
  static String enforceConflictPostCheck(
      String summary, String conflictMarker, List<InvestigationEvidence> evidence) {
    if (conflictMarker == null || conflictMarker.isBlank()) {
      return summary;
    }
    if (answerCoversConflictSides(summary, conflictMarker, evidence)) {
      return summary == null ? "" : summary;
    }
    return deterministicConflictAnswer(evidence, conflictMarker).summaryText();
  }

  static boolean answerCoversConflictSides(
      String summary, String conflictMarker, List<InvestigationEvidence> evidence) {
    if (summary == null || summary.isBlank() || conflictMarker == null || conflictMarker.isBlank()) {
      return false;
    }
    String lower = summary.toLowerCase(Locale.ROOT);
    boolean mentionsConflict =
        lower.contains("conflict")
            || lower.contains("disagree")
            || lower.contains("differ")
            || lower.contains("mismatch")
            || lower.contains("stalled")
            || lower.contains("at risk")
            || lower.contains("vs")
            || lower.contains("versus")
            || lower.contains("while")
            || lower.contains("but ");
    Set<String> sources = new LinkedHashSet<>();
    if (evidence != null) {
      for (InvestigationEvidence item : evidence) {
        if (item != null && item.source() != null && !item.source().isBlank()) {
          sources.add(item.source().toUpperCase(Locale.ROOT));
        }
      }
    }
    Matcher valueMatcher =
        Pattern.compile("\\(([^,]+),\\s*([A-Z_]+)\\)").matcher(conflictMarker);
    List<String> markerSources = new ArrayList<>();
    while (valueMatcher.find()) {
      markerSources.add(valueMatcher.group(2).toUpperCase(Locale.ROOT));
    }
    if (markerSources.size() >= 2) {
      boolean bothNamed =
          markerSources.stream()
              .allMatch(
                  src ->
                      lower.contains(src.toLowerCase(Locale.ROOT))
                          || (src.equals("ZOHO")
                              && (lower.contains("crm") || lower.contains("zoho")))
                          || (src.equals("SLACK") && lower.contains("slack"))
                          || (src.equals("NOTION") && lower.contains("notion")));
      if (bothNamed) {
        return true;
      }
    }
    long distinctCited =
        sources.stream()
            .filter(
                src ->
                    lower.contains(src.toLowerCase(Locale.ROOT))
                        || (src.equals("ZOHO")
                            && (lower.contains("crm") || lower.contains("zoho"))))
            .count();
    return mentionsConflict && distinctCited >= 2;
  }

  static InvestigationAnswer deterministicConflictAnswer(
      List<InvestigationEvidence> evidence, String conflictMarker) {
    StringBuilder body = new StringBuilder();
    body.append("Cross-source conflict detected. ");
    if (conflictMarker != null && !conflictMarker.isBlank()) {
      body.append(conflictMarker).append(' ');
    }
    body.append("Cite each side with its source:\n");
    if (evidence != null) {
      for (InvestigationEvidence item : evidence) {
        if (item == null || item.source() == null) {
          continue;
        }
        body.append("- [")
            .append(item.source())
            .append("] ")
            .append(item.name() == null ? "(unnamed)" : item.name());
        if (item.structured() != null && !item.structured().isEmpty()) {
          String stage = item.structured().get("Stage");
          String amount = item.structured().get("Amount");
          String probability = item.structured().get("Probability");
          List<String> bits = new ArrayList<>();
          if (stage != null && !stage.isBlank()) {
            bits.add("Stage=" + stage);
          }
          if (probability != null && !probability.isBlank()) {
            bits.add("Probability=" + probability);
          }
          if (amount != null && !amount.isBlank()) {
            bits.add("Amount=" + amount);
          }
          if (!bits.isEmpty()) {
            body.append(" (").append(String.join(", ", bits)).append(')');
          }
        } else if (item.narrative() != null && !item.narrative().isBlank()) {
          String snip = item.narrative().replace('\n', ' ').trim();
          if (snip.length() > 160) {
            snip = snip.substring(0, 160) + "…";
          }
          body.append(": ").append(snip);
        }
        body.append('\n');
      }
    }
    String summary = body.toString().trim();
    InvestigationEvidence zoho =
        evidence == null
            ? null
            : evidence.stream()
                .filter(e -> e != null && IndexConstants.SOURCE_TYPE_ZOHO.equals(e.source()))
                .findFirst()
                .orElse(null);
    return new InvestigationAnswer(
        "Cross-source conflict",
        zoho == null ? null : zoho.name(),
        zoho == null ? Map.of() : primaryFieldsFromEvidence(zoho),
        List.of("Deterministic conflict template — model omitted a conflict side."),
        Map.of(),
        "zoho-grounded-v1",
        TRUST_CONFLICT_ADJUDICATED,
        AnswerIntent.GENERAL.name(),
        summary);
  }

  /**
   * Trust state for an evidence-grounded narrative answer.
   *
   * <p>Two states are answered states the old model could not express:
   *
   * <ul>
   *   <li>{@link #TRUST_CONFLICT_ADJUDICATED} — sources disagreed on a field and the answer cited
   *       every value with its source. That is integrity Rule 7 working, not missing data; the
   *       flagship cross-source answer (Zoho vs Notion deal value) was being badged
   *       "insufficient_data" purely because no single CRM record could be named as THE record.
   *   <li>{@link #TRUST_GROUNDED} — the answer is bound to evidence verified by artifact id or
   *       exact title, so it is grounded even without a single structured record behind it.
   * </ul>
   *
   * <p>The honesty invariant holds in both cases: when the model itself says the evidence was not
   * enough, the answer stays at its unanswered fallback no matter what signals were present.
   */
  static String groundedTrustState(
      String conflictMarker, boolean artifactPinned, String summary, String fallback) {
    return groundedTrustState(conflictMarker, artifactPinned, false, summary, fallback);
  }

  static String groundedTrustState(
      String conflictMarker,
      boolean artifactPinned,
      boolean evidenceBacked,
      String summary,
      String fallback) {
    if (summary == null || summary.isBlank() || admitsInsufficientEvidence(summary)) {
      return fallback;
    }
    if (conflictMarker != null && !conflictMarker.isBlank()) {
      return TRUST_CONFLICT_ADJUDICATED;
    }
    if (artifactPinned || evidenceBacked) {
      return TRUST_GROUNDED;
    }
    return fallback;
  }

  /**
   * Recovers readable prose from a model response that failed JSON parsing. A truncated envelope
   * still carries a usable {@code "summary"} string; anything else falls back to the raw text with
   * JSON scaffolding stripped, so a buyer never sees a serialization artifact on screen.
   */
  /**
   * True when the summary actually names one of the retrieved records — the difference between an
   * answer built from the evidence and one built from the model's world knowledge that merely
   * happened to run alongside some evidence. Only the former may be called grounded; an answer
   * like "Infosys and INFY are the same company (INFY ticker)" over King ERP evidence must stay
   * insufficient_data no matter how confident it sounds.
   */
  static boolean answerCitesEvidence(String summary, List<InvestigationEvidence> evidence) {
    if (summary == null || summary.isBlank() || evidence == null || evidence.isEmpty()) {
      return false;
    }
    Set<String> summaryTokens = CanonicalEntityKey.contentTokensKeepingDealType(summary);
    if (summaryTokens.isEmpty()) {
      return false;
    }
    for (InvestigationEvidence item : evidence) {
      Set<String> nameTokens = CanonicalEntityKey.contentTokensKeepingDealType(item.name());
      if (nameTokens.isEmpty()) {
        continue;
      }
      // Require a real word, not a stray one-or-two-character token.
      if (nameTokens.stream().noneMatch(token -> token.length() >= 4)) {
        continue;
      }
      if (summaryTokens.containsAll(nameTokens)) {
        return true;
      }
    }
    return false;
  }

  static String salvageNarrative(String raw) {
    if (raw == null || raw.isBlank()) {
      return "";
    }
    String text = raw.trim();
    Matcher fenced = JSON_BLOCK.matcher(text);
    if (fenced.find()) {
      text = fenced.group(1).trim();
    }
    if (!text.startsWith("{")) {
      return text;
    }
    Matcher summary = PARTIAL_JSON_SUMMARY.matcher(text);
    if (summary.find()) {
      String value = summary.group(1);
      String unescaped =
          value
              .replace("\\n", "\n")
              .replace("\\\"", "\"")
              .replace("\\\\", "\\")
              .trim();
      if (!unescaped.isBlank()) {
        return unescaped;
      }
    }
    // No recoverable summary — say so plainly rather than printing the envelope.
    return "The answer could not be rendered completely. Please ask again.";
  }

  static boolean hasNonZohoEvidence(List<InvestigationEvidence> evidence) {
    if (evidence == null || evidence.isEmpty()) {
      return false;
    }
    return evidence.stream()
        .anyMatch(
            item ->
                item.source() != null
                    && !item.source().isBlank()
                    && !IndexConstants.SOURCE_TYPE_ZOHO.equals(item.source()));
  }

  static String structuredGeneralPrompt(
      String query, List<InvestigationEvidence> evidence, Map<String, Object> context) {
    return structuredGeneralPrompt(query, evidence, context, null);
  }

  static String structuredGeneralPrompt(
      String query,
      List<InvestigationEvidence> evidence,
      Map<String, Object> context,
      String conflictMarker) {
    String names = evidence.stream().map(InvestigationEvidence::name).toList().toString();
    String conflictBlock =
        conflictMarker == null || conflictMarker.isBlank()
            ? ""
            : "\n\n" + conflictMarker + "\nTreat this conflict marker as authoritative — cite both values.\n";
    return """
        Query: %s
        Context: %s
        %s
        Exact evidence names: %s
        Evidence:
        %s

        Respond with ONLY valid JSON:
        {
          "selectedRecord": "exact evidence name or null",
          "summary": "2-4 sentence grounded answer",
          "reasons": ["short reasons derived from selected structured evidence"]
        }
        selectedRecord must exactly match one evidence name, or be null when no single record is
        the final answer or when evidence is insufficient.
        Answer using ONLY the Evidence block above. Do not use world knowledge (tickers, company
        aliases, industry boilerplate, or invented activities/notes).
        If evidence does not contain enough to answer, set selectedRecord to null and say so in
        summary. If Flags include no_notes or no_activities, never invent note or activity content.
        Never invent fields. Treat Notes as unverified free-text.
        Do not invent corpus-wide ranking claims from this top-k evidence alone.
        Do not select demo/sample flagged records as selectedRecord.
        Only cite a contact that appears on the selected record's evidence — never borrow contacts
        from other deals. Owner is not a contact.
        When the question names an entity that is not in Exact evidence names, say no such
        deal/account was found — do not answer about a different entity.
        If Flags include possible_account_mention_unconfirmed_deal, cite that Slack/Notion
        mention as an account-level signal and do not treat it as confirmed for a specific deal.
        """
        .formatted(
            query,
            context == null ? "{}" : context.toString(),
            conflictBlock,
            names,
            formatEvidence(evidence));
  }

  /**
   * Prompt for cross-source questions (CRM vs Slack/Notion proposal, etc.). Includes structured
   * evidence rows and the multi-source explore {@code details} so Slack/Notion are not dropped
   * just because Zoho hits also exist.
   */
  static String multiSourceGeneralPrompt(
      String query,
      List<InvestigationEvidence> evidence,
      String details,
      Map<String, Object> context) {
    return multiSourceGeneralPrompt(query, evidence, details, context, null);
  }

  static String multiSourceGeneralPrompt(
      String query,
      List<InvestigationEvidence> evidence,
      String details,
      Map<String, Object> context,
      String conflictMarker) {
    String names = evidence.stream().map(InvestigationEvidence::name).toList().toString();
    String conflictBlock =
        conflictMarker == null || conflictMarker.isBlank()
            ? ""
            : "\n\n" + conflictMarker + "\nTreat this conflict marker as authoritative — cite both values.\n";
    return """
        Query: %s
        Context: %s
        %s
        Exact evidence names: %s
        Evidence (multi-source):
        %s

        Hybrid search excerpts (may include Slack, Notion, and other indexed sources):
        %s

        Respond with ONLY valid JSON:
        {
          "selectedRecord": "exact evidence name or null",
          "summary": "2-4 sentence grounded answer that reconciles sources when they conflict",
          "reasons": ["short reasons citing which source supports each claim"]
        }
        selectedRecord must exactly match one evidence name, or be null when the answer is a
        cross-source reconciliation / no single record is the final answer.
        Use BOTH the Evidence block and Hybrid search excerpts. When Zoho CRM and Slack/Notion
        disagree (e.g. amount in a proposal vs CRM record), call out both numbers and which
        source each comes from — do not silently prefer CRM. Prefer CRM Amount as the current
        deal value when explaining which number to tell a client, while still citing the proposal.
        Do not use world knowledge. Never invent notes/activities. Treat Notes as unverified.
        Do not select demo/sample flagged records as selectedRecord.
        Ignore unrelated Slack noise (e.g. payment-provider chit-chat) unless it names this entity.
        Owner is not a contact. When the named entity is absent from evidence, say not found.
        If Flags include possible_account_mention_unconfirmed_deal, cite that Slack/Notion
        mention as an account-level signal and do not treat it as confirmed for a specific deal.
        """
        .formatted(
            query,
            context == null ? "{}" : context.toString(),
            conflictBlock,
            names,
            formatEvidence(evidence),
            details == null || details.isBlank() ? "(none)" : details);
  }

  static String generalPrompt(
      String query, String details, Map<String, Object> context) {
    return """
        Query: %s
        Context: %s

        Indexed evidence:
        %s

        Answer concisely in 2-4 sentences using ONLY the indexed evidence above. If it does not
        contain enough to answer, say so clearly — do not use world knowledge or invent
        notes/activities. Treat structured fields as authoritative and Notes as unverified
        free-text. Do not invent ranking claims from incomplete retrieval.
        """
        .formatted(query, context == null ? "{}" : context.toString(), details);
  }

  private String streamAnswer(
      String userMessage, String fallback, InvestigationProgressSink progress) {
    return streamAnswer(
        userMessage,
        fallback,
        "You are a trustworthy CRM investigation assistant. Answer using ONLY the provided "
            + "indexed evidence. If the evidence does not contain enough information, say so "
            + "clearly. Do not invent deals, contacts, notes, activities, or use outside knowledge.",
        progress);
  }

  private String streamAnswer(
      String userMessage,
      String fallback,
      String systemPrompt,
      InvestigationProgressSink progress) {
    try {
      StringBuilder answer = new StringBuilder();
      chatCompletionService.streamAnswer(
          new RagPrompt(withIntegrityRules(systemPrompt), userMessage),
          answer::append,
          retryListener(progress == null ? InvestigationProgressSink.NOOP : progress));
      String value = answer.toString().trim();
      return value.isBlank() ? fallback : value;
    } catch (ChatException ex) {
      if (InvestigationRateLimitException.isRateLimitMessage(ex.getMessage())) {
        throw new InvestigationRateLimitException(
            "LLM rate-limited while investigating; retry shortly. (" + ex.getMessage() + ")", ex);
      }
      log.warn("Chat summarization unavailable: {}", ex.getMessage());
      return fallback;
    }
  }

  /** Prepends the canonical integrity rules ahead of path-specific system instructions. */
  static String withIntegrityRules(String systemPrompt) {
    String base = systemPrompt == null ? "" : systemPrompt.trim();
    String block = InvestigationIntegrityRules.systemBlock();
    if (base.isBlank()) {
      return block;
    }
    if (base.startsWith("INVESTIGATE INTEGRITY RULES")) {
      return base;
    }
    return block + "\n\n" + base;
  }

  static Optional<StructuredGeneralAnswer> parseStructuredGeneral(String raw) {
    JsonNode root = parseJson(raw);
    if (root == null) {
      return Optional.empty();
    }
    String summary = root.path("summary").asText("").trim();
    if (summary.isBlank()) {
      return Optional.empty();
    }
    String selectedRecord = null;
    JsonNode selectedNode = root.get("selectedRecord");
    if (selectedNode != null && !selectedNode.isNull()) {
      String selected = selectedNode.asText("").trim();
      if (!selected.isBlank() && !"null".equalsIgnoreCase(selected)) {
        selectedRecord = selected;
      }
    }
    List<String> reasons = readStringArray(root.get("reasons"));
    return Optional.of(
        new StructuredGeneralAnswer(selectedRecord, summary, reasons));
  }

  private static JsonNode parseJson(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String candidate = raw.trim();
    Matcher matcher = JSON_BLOCK.matcher(candidate);
    if (matcher.find()) {
      candidate = matcher.group(1).trim();
    }
    try {
      return OBJECT_MAPPER.readTree(candidate);
    } catch (Exception ex) {
      int start = candidate.indexOf('{');
      int end = candidate.lastIndexOf('}');
      if (start >= 0 && end > start) {
        try {
          return OBJECT_MAPPER.readTree(candidate.substring(start, end + 1));
        } catch (Exception ignored) {
          return null;
        }
      }
      return null;
    }
  }

  static Optional<DealLedgerEntry> findLedgerEntry(
      List<DealLedgerEntry> ledger, String name) {
    return resolveNamedEntity(name, ledger);
  }

  static boolean shouldAttemptNamedEntityPin(
      QueryClassification classification, Map<String, Object> priorBag) {
    return shouldAttemptNamedEntityPin(null, classification, priorBag, List.of());
  }

  /**
   * Named-entity pin candidacy. Prefer bag/entity/lookup triggers; for reason/general/unparsed,
   * only when a ledger probe resolves the query to a CRM deal/account (avoids corpus false
   * positives from leftover CanonicalEntityKey tokens).
   */
  static boolean shouldAttemptNamedEntityPin(
      String query,
      QueryClassification classification,
      Map<String, Object> priorBag,
      List<DealLedgerEntry> entityLookupLedger) {
    if (priorBag != null
        && priorBag.get("entity") != null
        && !String.valueOf(priorBag.get("entity")).isBlank()) {
      return true;
    }
    if (classification != null
        && classification.entity() != null
        && !classification.entity().isBlank()) {
      return true;
    }
    if (classification != null && classification.confident()) {
      String intent = classification.intent();
      if ("lookup".equals(intent) || "named_entity_staleness".equals(intent)) {
        return true;
      }
      if (isCorpusWideStructuredIntent(classification)) {
        return false;
      }
      if ("reason".equals(intent) || "general".equals(intent) || "qualify".equals(intent)) {
        return ledgerProbeMatches(query, entityLookupLedger);
      }
      return false;
    }
    // Unparsed / low-confidence: probe only — never pin on blank key alone.
    return ledgerProbeMatches(query, entityLookupLedger);
  }

  private boolean shouldAttemptNamedEntityPin(
      String query,
      QueryClassification classification,
      Map<String, Object> priorBag,
      Path crmIndexPath) {
    if (priorBag != null
        && priorBag.get("entity") != null
        && !String.valueOf(priorBag.get("entity")).isBlank()) {
      return true;
    }
    if (classification != null
        && classification.entity() != null
        && !classification.entity().isBlank()) {
      return true;
    }
    if (classification != null && classification.confident()) {
      String intent = classification.intent();
      if ("lookup".equals(intent) || "named_entity_staleness".equals(intent)) {
        return true;
      }
      if (isCorpusWideStructuredIntent(classification)) {
        return false;
      }
      if (!("reason".equals(intent) || "general".equals(intent) || "qualify".equals(intent))) {
        return false;
      }
    }
    if (query == null || query.isBlank() || CanonicalEntityKey.key(query).isBlank()) {
      return false;
    }
    List<DealLedgerEntry> ledger =
        zohoQualificationService.listDealLedgerForEntityLookup(crmIndexPath);
    return ledgerProbeMatches(query, ledger);
  }

  static boolean isCorpusWideStructuredIntent(QueryClassification classification) {
    if (classification == null || !classification.confident()) {
      return false;
    }
    String intent = classification.intent();
    return "enumerate".equals(intent)
        || "aggregate".equals(intent)
        || "corpus_staleness".equals(intent);
  }

  /**
   * CRM-flag staleness answers only when there is no Slack/Notion/Jira evidence for the pinned
   * entity. Matching non-Zoho hits are kept regardless of retrieval score.
   */
  static boolean useCrmOnlyNamedEntityStaleness(
      QueryClassification classification, List<InvestigationEvidence> evidence) {
    if (classification == null || !classification.confident()) {
      return false;
    }
    if (!"named_entity_staleness".equals(classification.intent())) {
      return false;
    }
    return !hasNonZohoEvidence(evidence);
  }

  static boolean ledgerProbeMatches(String query, List<DealLedgerEntry> entityLookupLedger) {
    if (query == null || query.isBlank() || CanonicalEntityKey.key(query).isBlank()) {
      return false;
    }
    return resolveNamedEntity(query, entityLookupLedger).isPresent();
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> readConversationBag(Map<String, Object> context) {
    if (context == null || context.isEmpty()) {
      return Map.of();
    }
    Object bag = context.get("conversationBag");
    if (bag == null) {
      bag = context.get("priorTurn");
    }
    if (bag instanceof Map<?, ?> map) {
      Map<String, Object> out = new LinkedHashMap<>();
      for (Map.Entry<?, ?> e : map.entrySet()) {
        if (e.getKey() != null) {
          out.put(String.valueOf(e.getKey()), e.getValue());
        }
      }
      return out;
    }
    return Map.of();
  }

  /** Evidence pinned by verified artifact id/title, kept separate from ranked retrieval. */
  record ArtifactPinOutcome(
      List<ArtifactReference> references,
      List<InvestigationEvidence> evidence,
      List<String> resolved,
      List<String> unresolved) {

    static final ArtifactPinOutcome EMPTY =
        new ArtifactPinOutcome(List.of(), List.of(), List.of(), List.of());

    ArtifactPinOutcome {
      references = references == null ? List.of() : List.copyOf(references);
      evidence = evidence == null ? List.of() : List.copyOf(evidence);
      resolved = resolved == null ? List.of() : List.copyOf(resolved);
      unresolved = unresolved == null ? List.of() : List.copyOf(unresolved);
    }
  }

  /**
   * Resolve every artifact the question names by id/title, verifying each candidate before it is
   * allowed to stand in for the named artifact. An unresolved reference is reported as unresolved
   * rather than silently substituted — the honesty invariant is unchanged, only recall improves.
   */
  private ArtifactPinOutcome pinArtifactReferences(
      String query, Set<String> sourceFilter, InvestigationProgressSink progress) {
    List<ArtifactReference> references = ArtifactReferenceDetector.detect(query);
    if (references.isEmpty()) {
      return ArtifactPinOutcome.EMPTY;
    }
    List<InvestigationEvidence> pinned = new ArrayList<>();
    Set<String> seenKeys = new LinkedHashSet<>();
    List<String> resolved = new ArrayList<>();
    List<String> unresolved = new ArrayList<>();
    for (ArtifactReference reference : references) {
      List<InvestigationEvidence> verified = resolveArtifactReference(reference, sourceFilter);
      if (verified.isEmpty()) {
        unresolved.add(reference.label());
        continue;
      }
      resolved.add(reference.label());
      for (InvestigationEvidence item : verified) {
        if (seenKeys.add(evidenceKey(item))) {
          pinned.add(item);
        }
      }
    }
    if (!resolved.isEmpty()) {
      progress.emit(
          InvestigationProgressEvent.progress(
              "artifact_pin",
              "done",
              "Found "
                  + String.join(", ", resolved)
                  + " directly by "
                  + (references.getFirst().kind() == ArtifactReference.Kind.TICKET
                      ? "ticket id"
                      : "title")
                  + "."));
    } else {
      progress.emit(
          InvestigationProgressEvent.progress(
              "artifact_pin",
              "done",
              "No indexed record matches " + String.join(", ", unresolved) + "."));
    }
    return new ArtifactPinOutcome(references, pinned, resolved, unresolved);
  }

  private List<InvestigationEvidence> resolveArtifactReference(
      ArtifactReference reference, Set<String> sourceFilter) {
    try {
      ExploreService.ExploreResult targeted =
          applySourceTypeFilter(
              exploreService.explore(reference.token(), null, ARTIFACT_PIN_SEARCH_LIMIT),
              sourceFilter);
      return excludeDemoEvidence(buildEvidence(targeted)).stream()
          .filter(item -> ArtifactReferenceDetector.verifies(reference, item.name(), item.narrative()))
          .limit(ARTIFACT_PIN_MAX_PER_REFERENCE)
          .toList();
    } catch (RuntimeException e) {
      log.debug("Artifact pin lookup failed for {}: {}", reference.token(), e.toString());
      return List.of();
    }
  }

  /** Verified artifacts lead; ranked retrieval follows, de-duplicated against them. */
  static List<InvestigationEvidence> mergeArtifactPins(
      List<InvestigationEvidence> pinned, List<InvestigationEvidence> retrieved) {
    if (pinned == null || pinned.isEmpty()) {
      return retrieved == null ? List.of() : retrieved;
    }
    List<InvestigationEvidence> merged = new ArrayList<>(pinned);
    Set<String> seen = new LinkedHashSet<>();
    pinned.forEach(item -> seen.add(evidenceKey(item)));
    if (retrieved != null) {
      for (InvestigationEvidence item : retrieved) {
        if (seen.add(evidenceKey(item))) {
          merged.add(item);
        }
      }
    }
    return List.copyOf(merged);
  }

  private static String evidenceKey(InvestigationEvidence item) {
    if (item.chunkId() != null && !item.chunkId().isBlank()) {
      return "chunk:" + item.chunkId();
    }
    return "name:" + item.source() + "|" + item.name();
  }

  /**
   * True when the classifier's extracted entity is one of the artifacts we just verified — i.e.
   * "SOF-4" was read as a CRM entity name. Compares against the verified references only, so a
   * genuine deal name never takes this branch.
   */
  static boolean entityIsPinnedArtifact(
      QueryClassification classification, ArtifactPinOutcome pins) {
    if (classification == null
        || classification.entity() == null
        || classification.entity().isBlank()
        || pins == null
        || pins.resolved().isEmpty()) {
      return false;
    }
    String entity = classification.entity();
    return pins.references().stream()
        .filter(reference -> pins.resolved().contains(reference.label()))
        .anyMatch(reference -> ArtifactReferenceDetector.verifies(reference, entity, entity));
  }

  private NamedEntityPinOutcome pinNamedEntityEvidence(
      String query,
      QueryClassification classification,
      Map<String, Object> priorBag,
      List<InvestigationEvidence> exploreEvidence,
      Path crmIndexPath,
      InvestigationProgressSink progress) {
    boolean explicitEntity =
        (classification.entity() != null && !classification.entity().isBlank())
            || (priorBag != null
                && stringFromBag(priorBag, "entity") != null
                && !stringFromBag(priorBag, "entity").isBlank());
    String entityHint = firstNonBlank(classification.entity(), stringFromBag(priorBag, "entity"));
    if (entityHint == null || entityHint.isBlank()) {
      entityHint = query;
    }
    List<DealLedgerEntry> ledger =
        zohoQualificationService.listDealLedgerForEntityLookup(crmIndexPath);
    Optional<DealLedgerEntry> resolved = resolveNamedEntity(entityHint, ledger);
    // Classifier often extracts a person ("Fatima") while the deal name is only in the full
    // query ("Bright Horizon … Fatima"). Retry with the raw question before giving up.
    if (resolved.isEmpty()
        && query != null
        && !query.isBlank()
        && !query.equalsIgnoreCase(entityHint)) {
      Optional<DealLedgerEntry> fromQuery = resolveNamedEntity(query, ledger);
      if (fromQuery.isPresent()) {
        resolved = fromQuery;
        entityHint = query;
      }
    }
    // Only fall back to the prior turn's pinned entity when THIS turn named none of its own —
    // a genuine follow-up ("what about it?"). If the classifier extracted a fresh entity for
    // this turn and it simply isn't a CRM deal/account (e.g. "mybuzai", a Google Chat space),
    // retrying with the old entity would silently swap it back in — every later refusal or
    // answer then cites the STALE entity instead of the one actually just asked about.
    boolean currentTurnNamedNoEntity = classification.entity() == null || classification.entity().isBlank();
    if (resolved.isEmpty() && currentTurnNamedNoEntity && priorBag != null) {
      String priorEntity = stringFromBag(priorBag, "entity");
      if (priorEntity != null && !priorEntity.isBlank()) {
        resolved = resolveNamedEntity(priorEntity, ledger);
        entityHint = priorEntity;
      }
    }
    if (resolved.isEmpty()) {
      // Hard not-found only for explicitly named entities (classifier entity or prior bag) —
      // but only when there is ALSO no matching non-CRM evidence (Slack channel, Notion page,
      // Google Chat space, ...) for that same entity. Without this check, any entity that isn't
      // a CRM deal/account (e.g. a Google Chat space name) was refused outright even when hybrid
      // search had already found exactly the right evidence for it — the refusal is meant to
      // guard against hallucinating a CRM match, not to gate every other connected source too.
      String entityHintForMatch = entityHint;
      List<InvestigationEvidence> matchedNonCrmItems =
          exploreEvidence.stream()
              .filter(
                  item ->
                      item.source() != null
                          && !item.source().isBlank()
                          && !IndexConstants.SOURCE_TYPE_ZOHO.equals(item.source())
                          && CanonicalEntityKey.matchesEvidenceName(entityHintForMatch, item.name()))
              .toList();
      boolean matchedNonCrmEvidence = !matchedNonCrmItems.isEmpty();
      if (explicitEntity
          && ledger != null
          && !ledger.isEmpty()
          && !CanonicalEntityKey.key(entityHint).isBlank()
          && !matchedNonCrmEvidence) {
        String label =
            classification.entity() != null && !classification.entity().isBlank()
                ? classification.entity()
                : CanonicalEntityKey.key(entityHint);
        // Weaker "near miss" signal: evidence whose name or narrative text shares a word with the
        // entity hint, even though it didn't clear the matchedNonCrmEvidence bar above. When such
        // evidence exists, report it honestly instead of the blanket "nothing matching" refusal.
        List<InvestigationEvidence> nearMiss =
            exploreEvidence.stream()
                .filter(
                    item ->
                        item.source() != null
                            && !item.source().isBlank()
                            && !IndexConstants.SOURCE_TYPE_ZOHO.equals(item.source())
                            && evidenceMentionsEntityToken(entityHintForMatch, item))
                .toList();
        progress.emit(
            InvestigationProgressEvent.progress(
                "entity_pin",
                "done",
                "No CRM deal/account matched \"" + label + "\"."));
        InvestigationAnswer notFound;
        if (!nearMiss.isEmpty()) {
          String sources =
              nearMiss.stream()
                  .map(InvestigationEvidence::source)
                  .distinct()
                  .collect(Collectors.joining("/"));
          String message =
              "The only related mentions of \""
                  + label
                  + "\" are "
                  + nearMiss.size()
                  + " "
                  + sources
                  + " message(s)/page(s); none of them defines it as a CRM record. I will not"
                  + " fabricate a CRM answer.";
          notFound =
              new InvestigationAnswer(
                  "Named entity near miss",
                  null,
                  Map.of(),
                  List.of(message),
                  Map.of(),
                  "zoho-grounded-v1",
                  "insufficient_data",
                  AnswerIntent.GENERAL.name(),
                  message);
        } else {
          notFound =
              new InvestigationAnswer(
                  "Named entity not found",
                  null,
                  Map.of(),
                  List.of(
                      "No indexed CRM deal, account, Slack channel, Notion page, or Google Chat space matched \""
                          + label
                          + "\"."),
                  Map.of(),
                  "zoho-grounded-v1",
                  "insufficient_data",
                  AnswerIntent.GENERAL.name(),
                  "No source I have indexed — CRM, Slack, Notion, Jira, Linear, or Google Chat — has anything matching \""
                      + label
                      + "\". I will not answer about a different entity.");
        }
        return new NamedEntityPinOutcome(
            List.of(), null, Optional.of(notFound), false, null, label, null);
      }
      return new NamedEntityPinOutcome(
          exploreEvidence, null, Optional.empty(), false, null, null, null);
    }

    DealLedgerEntry entry = resolved.get();
    if (entry.demo()
        || (entry.flags() != null && entry.flags().contains(DEMO_FLAG))) {
      InvestigationAnswer demoBlocked =
          new InvestigationAnswer(
              "Demo entity excluded",
              null,
              Map.of(),
              List.of("Matched CRM record is demo/sample and excluded from primary answers."),
              Map.of(),
              "zoho-grounded-v1",
              "insufficient_data",
              AnswerIntent.GENERAL.name(),
              "A demo/sample CRM record matched \""
                  + entry.name()
                  + "\", but demo records are excluded from primary answers.");
      return new NamedEntityPinOutcome(
          List.of(),
          null,
          Optional.of(demoBlocked),
          true,
          CanonicalEntityKey.key(entry.name()),
          entry.name(),
          null);
    }

    String canonical = CanonicalEntityKey.key(entry.accountName());
    if (canonical.isBlank()) {
      canonical = CanonicalEntityKey.key(entry.name());
    }
    // Phase 1.2B: scoped second search (ZOHO/SLACK/NOTION only) so Google Chat noise in the
    // hybrid top-k cannot starve Slack/Notion sides of the conflict story.
    List<InvestigationEvidence> evidencePool =
        mergeScopedCrmEvidence(entry, exploreEvidence, progress);
    InvestigationEvidence pinnedZoho = evidenceFromLedgerEntry(entry, evidencePool);
    List<InvestigationEvidence> related =
        filterEvidenceForCanonicalEntity(evidencePool, entry, ledger);
    List<InvestigationEvidence> merged = new ArrayList<>();
    merged.add(pinnedZoho);
    for (InvestigationEvidence item : related) {
      if (IndexConstants.SOURCE_TYPE_ZOHO.equals(item.source())
          && CanonicalEntityKey.matches(item.name(), entry.name())) {
        continue;
      }
      merged.add(item);
    }
    // Merge prior-turn amounts into conflict detection when present (Track B: retrieve + merge).
    String conflictMarker = synthesisConflictMarker(merged, priorBag, canonical);
    progress.emit(
        InvestigationProgressEvent.progress(
            "entity_pin",
            "done",
            "Pinned CRM entity "
                + entry.name()
                + " (canonical="
                + canonical
                + ") with "
                + merged.size()
                + " evidence row(s).",
            IndexConstants.SOURCE_TYPE_ZOHO));
    String detailsOverride =
        "Pinned entity: "
            + entry.name()
            + " / "
            + entry.accountName()
            + "\n"
            + formatEvidence(merged);
    return new NamedEntityPinOutcome(
        List.copyOf(merged),
        detailsOverride,
        Optional.empty(),
        true,
        canonical,
        entry.name(),
        conflictMarker);
  }

  /**
   * Phase 1.2B — after a CRM pin fires, re-search with ZOHO/SLACK/NOTION only and merge ahead of
   * the hybrid top-k so Google Chat hospital/job spam cannot drown the conflict sides.
   */
  private List<InvestigationEvidence> mergeScopedCrmEvidence(
      DealLedgerEntry entry,
      List<InvestigationEvidence> exploreEvidence,
      InvestigationProgressSink progress) {
    List<InvestigationEvidence> scoped = List.of();
    String pinQuery =
        firstNonBlank(entry.name(), entry.accountName())
            + (entry.accountName() != null
                    && !entry.accountName().isBlank()
                    && !entry.accountName().equalsIgnoreCase(entry.name())
                ? " " + entry.accountName()
                : "");
    try {
      ExploreService.ExploreResult targeted =
          applySourceTypeFilter(
              exploreService.explore(pinQuery, null), CRM_CONFLICT_SOURCE_TYPES);
      scoped = excludeDemoEvidence(buildEvidence(targeted));
      if (!scoped.isEmpty()) {
        progress.emit(
            InvestigationProgressEvent.progress(
                "scoped_crm_search",
                "done",
                "Scoped ZOHO/SLACK/NOTION search returned " + scoped.size() + " hit(s)."));
      }
    } catch (RuntimeException e) {
      log.debug("Scoped CRM conflict search failed for {}: {}", pinQuery, e.toString());
    }
    if (scoped.isEmpty()) {
      return exploreEvidence == null ? List.of() : exploreEvidence;
    }
    return mergeArtifactPins(scoped, exploreEvidence);
  }

  /**
   * Weak near-miss signal for the entity-pin refusal message (F3): true when the entity hint
   * shares a significant word with the evidence item's name OR narrative text, even if it did not
   * clear {@link CanonicalEntityKey#matchesEvidenceName}. Used only to choose honest wording for a
   * refusal that is already happening — never to skip the refusal or fabricate a CRM match.
   */
  private static boolean evidenceMentionsEntityToken(String entityHint, InvestigationEvidence item) {
    if (entityHint == null || entityHint.isBlank() || item == null) {
      return false;
    }
    String haystack =
        ((item.name() == null ? "" : item.name())
                + " "
                + (item.narrative() == null ? "" : item.narrative()))
            .toLowerCase(Locale.ROOT);
    if (haystack.isBlank()) {
      return false;
    }
    for (String token : CanonicalEntityKey.significantTokens(entityHint)) {
      if (token.length() >= 4 && haystack.contains(token)) {
        return true;
      }
    }
    return false;
  }

  private static InvestigationEvidence evidenceFromLedgerEntry(
      DealLedgerEntry entry, List<InvestigationEvidence> exploreEvidence) {
    InvestigationEvidence explore = null;
    if (exploreEvidence != null) {
      for (InvestigationEvidence item : exploreEvidence) {
        if (IndexConstants.SOURCE_TYPE_ZOHO.equals(item.source())
            && (CanonicalEntityKey.matches(item.name(), entry.name())
                || (entry.chunkId() != null
                    && entry.chunkId().equals(item.chunkId())))) {
          explore = item;
          break;
        }
      }
    }
    Map<String, String> structured = new LinkedHashMap<>(entry.primaryFields());
    if (structured.containsKey("Close Date") && !structured.containsKey("Close")) {
      structured.put("Close", structured.get("Close Date"));
    }
    String narrative =
        entry.narrative() != null && !entry.narrative().isBlank()
            ? entry.narrative()
            : explore == null ? "" : explore.narrative();
    return new InvestigationEvidence(
        IndexConstants.SOURCE_TYPE_ZOHO,
        entry.name(),
        explore == null ? 1.0f : Math.max(1.0f, explore.score()),
        structured,
        answerFacingFlags(entry.flags()),
        explore == null ? List.of() : explore.contacts(),
        explore == null ? List.of() : explore.notes(),
        narrative,
        entry.chunkId(),
        entry.path());
  }

  static List<InvestigationEvidence> filterEvidenceForCanonicalEntity(
      List<InvestigationEvidence> exploreEvidence,
      DealLedgerEntry pinned,
      List<DealLedgerEntry> ledger) {
    if (exploreEvidence == null || exploreEvidence.isEmpty() || pinned == null) {
      return List.of();
    }
    String accountKey = CanonicalEntityKey.key(pinned.accountName());
    String dealKey = CanonicalEntityKey.key(pinned.name());
    String primaryKey = !accountKey.isBlank() ? accountKey : dealKey;
    if (primaryKey == null || primaryKey.isBlank()) {
      return List.of();
    }
    boolean requireDealDisambiguation = countActiveDealsForAccount(ledger, pinned) > 1;
    Set<String> discriminators =
        CanonicalEntityKey.discriminatorTokens(pinned.name(), pinned.accountName());
    List<InvestigationEvidence> out = new ArrayList<>();
    for (InvestigationEvidence item : exploreEvidence) {
      if (item == null) {
        continue;
      }
      if (IndexConstants.SOURCE_TYPE_ZOHO.equals(item.source())) {
        if (CanonicalEntityKey.matches(item.name(), pinned.name())
            || CanonicalEntityKey.matches(item.name(), pinned.accountName())) {
          out.add(item);
        }
        continue;
      }
      String hay =
          CanonicalEntityKey.key(
              (item.name() == null ? "" : item.name())
                  + " "
                  + (item.narrative() == null ? "" : item.narrative()));
      boolean accountMatch =
          (!hay.isBlank() && hay.contains(primaryKey))
              || CanonicalEntityKey.matches(item.name(), pinned.accountName())
              || CanonicalEntityKey.matches(item.name(), pinned.name());
      if (!accountMatch) {
        continue;
      }
      if (!requireDealDisambiguation) {
        out.add(item);
        continue;
      }
      boolean dealMatch = hayContainsDealDiscriminators(item, dealKey, accountKey, discriminators);
      if (dealMatch) {
        out.add(item);
      } else {
        out.add(withFlag(item, ACCOUNT_MENTION_UNCONFIRMED_DEAL_FLAG));
      }
    }
    return out;
  }

  static int countActiveDealsForAccount(List<DealLedgerEntry> ledger, DealLedgerEntry pinned) {
    if (ledger == null || ledger.isEmpty() || pinned == null) {
      return pinned == null ? 0 : 1;
    }
    String accountKey = CanonicalEntityKey.key(pinned.accountName());
    if (accountKey.isBlank()) {
      return 1;
    }
    int count = 0;
    for (DealLedgerEntry entry : ledger) {
      if (entry == null || entry.demo()) {
        continue;
      }
      if (entry.flags() != null && entry.flags().contains(DEMO_FLAG)) {
        continue;
      }
      if (isClosedLedgerStage(entry)) {
        continue;
      }
      if (accountKey.equals(CanonicalEntityKey.key(entry.accountName()))) {
        count++;
      }
    }
    return Math.max(count, 1);
  }

  static boolean isClosedLedgerStage(DealLedgerEntry entry) {
    if (entry == null || entry.primaryFields() == null) {
      return false;
    }
    String stage = entry.primaryFields().get("Stage");
    return stage != null && stage.toLowerCase(Locale.ROOT).contains("closed");
  }

  private static boolean hayContainsDealDiscriminators(
      InvestigationEvidence item,
      String dealKey,
      String accountKey,
      Set<String> discriminators) {
    if (discriminators == null || discriminators.isEmpty()) {
      return true;
    }
    String raw =
        (item.name() == null ? "" : item.name())
            + " "
            + (item.narrative() == null ? "" : item.narrative());
    Set<String> hayTokens = CanonicalEntityKey.contentTokensKeepingDealType(raw);
    for (String token : discriminators) {
      if (hayTokens.contains(token)) {
        return true;
      }
    }
    String hay = CanonicalEntityKey.key(raw);
    return !dealKey.isBlank() && !dealKey.equals(accountKey) && hay.contains(dealKey);
  }

  private static InvestigationEvidence withFlag(InvestigationEvidence item, String flag) {
    if (item == null || flag == null || flag.isBlank()) {
      return item;
    }
    if (item.flags() != null && item.flags().contains(flag)) {
      return item;
    }
    List<String> flags = new ArrayList<>(item.flags() == null ? List.of() : item.flags());
    flags.add(flag);
    return new InvestigationEvidence(
        item.source(),
        item.name(),
        item.score(),
        item.structured(),
        flags,
        item.contacts(),
        item.notes(),
        item.narrative(),
        item.chunkId(),
        item.path());
  }

  static String synthesisConflictMarker(
      List<InvestigationEvidence> evidence,
      Map<String, Object> priorBag,
      String canonicalEntityKey) {
    String amountMarker = amountConflictMarker(evidence, priorBag, canonicalEntityKey);
    if (amountMarker != null) {
      return amountMarker;
    }
    return statusConflictMarker(evidence, canonicalEntityKey);
  }

  static String amountConflictMarker(
      List<InvestigationEvidence> evidence,
      Map<String, Object> priorBag,
      String canonicalEntityKey) {
    Map<String, String> amountsBySource = new LinkedHashMap<>();
    if (evidence != null) {
      for (InvestigationEvidence item : evidence) {
        String amount = amountFromEvidence(item);
        if (amount == null) {
          continue;
        }
        String source = item.source() == null ? "UNKNOWN" : item.source();
        amountsBySource.putIfAbsent(source, amount);
      }
    }
    if (priorBag != null && priorBag.get("amounts") instanceof List<?> priorAmounts) {
      for (Object row : priorAmounts) {
        if (!(row instanceof Map<?, ?> map)) {
          continue;
        }
        Object source = map.get("source");
        Object value = map.get("value");
        if (source == null || value == null) {
          continue;
        }
        amountsBySource.putIfAbsent(String.valueOf(source), String.valueOf(value));
      }
    }
    if (amountsBySource.size() < 2) {
      return null;
    }
    Set<String> distinct = new LinkedHashSet<>(amountsBySource.values());
    if (distinct.size() < 2) {
      return null;
    }
    String values =
        amountsBySource.entrySet().stream()
            .map(e -> "($" + e.getValue() + ", " + e.getKey() + ")")
            .collect(Collectors.joining(", "));
    String entity =
        canonicalEntityKey == null || canonicalEntityKey.isBlank() ? "entity" : canonicalEntityKey;
    return "CONFLICT DETECTED: field=amount, entity=" + entity + ", values=[" + values + "]";
  }

  /**
   * Slack/Chat risk language vs an open Zoho stage for the same pinned entity (Bright Horizon /
   * Fatima story). Amount conflicts take precedence via {@link #synthesisConflictMarker}.
   */
  static String statusConflictMarker(
      List<InvestigationEvidence> evidence, String canonicalEntityKey) {
    if (evidence == null || evidence.isEmpty()) {
      return null;
    }
    InvestigationEvidence riskSide = null;
    String riskPhrase = null;
    for (InvestigationEvidence item : evidence) {
      if (item == null || IndexConstants.SOURCE_TYPE_ZOHO.equals(item.source())) {
        continue;
      }
      String hay =
          ((item.narrative() == null ? "" : item.narrative())
                  + " "
                  + (item.notes() == null ? "" : String.join(" ", item.notes())))
              .trim();
      if (hay.isBlank()) {
        continue;
      }
      Matcher matcher = RISK_STATUS_LANGUAGE.matcher(hay);
      if (matcher.find()) {
        riskSide = item;
        riskPhrase = matcher.group().toLowerCase(Locale.ROOT);
        break;
      }
    }
    if (riskSide == null) {
      return null;
    }
    InvestigationEvidence zohoSide = null;
    for (InvestigationEvidence item : evidence) {
      if (item != null && IndexConstants.SOURCE_TYPE_ZOHO.equals(item.source())) {
        zohoSide = item;
        break;
      }
    }
    if (zohoSide == null) {
      return null;
    }
    String stage =
        zohoSide.structured() == null
            ? null
            : firstNonBlank(
                zohoSide.structured().get("Stage"), zohoSide.structured().get("stage"));
    if (stage == null || stage.isBlank()) {
      stage = "open CRM stage";
    }
    if (stage.toLowerCase(Locale.ROOT).contains("closed")) {
      return null;
    }
    String probability =
        zohoSide.structured() == null
            ? null
            : firstNonBlank(
                zohoSide.structured().get("Probability"),
                zohoSide.structured().get("probability"));
    String crmValue =
        probability == null || probability.isBlank() ? stage : stage + " " + probability;
    String entity =
        canonicalEntityKey == null || canonicalEntityKey.isBlank()
            ? (zohoSide.name() == null ? "entity" : zohoSide.name())
            : canonicalEntityKey;
    String riskSource = riskSide.source() == null ? "UNKNOWN" : riskSide.source();
    return "CONFLICT DETECTED: field=status, entity="
        + entity
        + ", values=[("
        + riskPhrase
        + ", "
        + riskSource
        + "), ("
        + crmValue
        + ", ZOHO)]";
  }

  private static String amountFromEvidence(InvestigationEvidence item) {
    if (item == null || item.structured() == null) {
      return moneyFromText(item == null ? null : item.narrative());
    }
    String amount = item.structured().get("Amount");
    if (amount != null && amount.matches(".*\\d.*")) {
      return amount.replace(",", "").replaceAll("[^0-9.]", "").trim();
    }
    return moneyFromText(item.narrative());
  }

  static String moneyFromText(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    String stripped = ISO_DATE_OR_TIMESTAMP.matcher(text).replaceAll(" ");
    Matcher matcher = MONEY_PATTERN.matcher(stripped);
    while (matcher.find()) {
      String num = matcher.group("num");
      if (num == null || num.isBlank()) {
        continue;
      }
      String code = matcher.group("code");
      String sym = matcher.group("sym");
      String suffix = matcher.group("suffix");
      boolean currencyHint =
          (code != null && !code.isBlank())
              || (sym != null && !sym.isBlank())
              || num.contains(",")
              || (suffix != null && !suffix.isBlank());
      if (!currencyHint) {
        continue;
      }
      String normalized = num.replace(",", "").trim();
      if (isCalendarYear(normalized) && (suffix == null || suffix.isBlank())) {
        continue;
      }
      return normalized;
    }
    return null;
  }

  private static boolean isCalendarYear(String digits) {
    if (digits == null || digits.length() != 4) {
      return false;
    }
    try {
      int year = Integer.parseInt(digits);
      return year >= 1900 && year <= 2100;
    } catch (NumberFormatException ignored) {
      return false;
    }
  }

  static Map<String, Object> buildConversationBag(
      String entityHint,
      InvestigationAnswer answer,
      List<InvestigationEvidence> evidence,
      Map<String, Object> priorBag) {
    Map<String, Object> bag = new LinkedHashMap<>();
    String entity =
        firstNonBlank(
            entityHint,
            answer == null ? null : answer.recordName(),
            stringFromBag(priorBag, "entity"));
    if (entity != null && !entity.isBlank()) {
      bag.put("entity", entity);
      bag.put("canonicalEntityKey", CanonicalEntityKey.key(entity));
    }
    List<Map<String, String>> amounts = new ArrayList<>();
    if (evidence != null) {
      for (InvestigationEvidence item : evidence) {
        String amount = amountFromEvidence(item);
        if (amount == null) {
          continue;
        }
        Map<String, String> row = new LinkedHashMap<>();
        row.put("source", item.source() == null ? "" : item.source());
        row.put("value", amount);
        row.put("name", item.name() == null ? "" : item.name());
        amounts.add(row);
      }
    }
    if (!amounts.isEmpty()) {
      bag.put("amounts", amounts);
    }
    String conflict =
        synthesisConflictMarker(
            evidence,
            priorBag,
            entity == null ? null : CanonicalEntityKey.key(entity));
    if (conflict != null) {
      bag.put("conflictSummary", conflict);
    }
    if (answer != null && answer.summaryText() != null && !answer.summaryText().isBlank()) {
      String summary = answer.summaryText();
      bag.put("summary", summary.length() > 500 ? summary.substring(0, 500) : summary);
    }
    if (answer != null && answer.recordName() != null) {
      bag.put("selectedRecord", answer.recordName());
    }
    if (answer != null && answer.primaryFields() != null && !answer.primaryFields().isEmpty()) {
      bag.put("primaryFields", answer.primaryFields());
    }
    // Track B v1: always re-retrieve + merge; never advertise skip-retrieval.
    bag.put("retrievalMode", "retrieve_and_merge");
    return bag;
  }

  private static String stringFromBag(Map<String, Object> bag, String key) {
    if (bag == null || key == null) {
      return null;
    }
    Object value = bag.get(key);
    if (value == null) {
      return null;
    }
    String text = String.valueOf(value).trim();
    return text.isBlank() || "null".equalsIgnoreCase(text) ? null : text;
  }

  private static String firstNonBlank(String... values) {
    if (values == null) {
      return null;
    }
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value.trim();
      }
    }
    return null;
  }

  private record NamedEntityPinOutcome(
      List<InvestigationEvidence> evidence,
      String detailsOverride,
      Optional<InvestigationAnswer> notFoundAnswer,
      boolean pinned,
      String canonicalEntityKey,
      String entityLabel,
      String conflictMarker) {}

  static String normalizeEntityToken(String value) {
    return CanonicalEntityKey.normalizeToken(value);
  }

  static Set<String> significantTokens(String normalized) {
    return CanonicalEntityKey.significantTokens(normalized);
  }

  private static Optional<InvestigationEvidence> findEvidenceByName(
      List<InvestigationEvidence> evidence, String name) {
    if (name == null || name.isBlank() || evidence == null) {
      return Optional.empty();
    }
    String normalized = normalizeEntityToken(name);
    Optional<InvestigationEvidence> exact =
        evidence.stream()
            .filter(item -> normalizeEntityToken(item.name()).equals(normalized))
            .findFirst();
    if (exact.isPresent()) {
      return exact;
    }
    Optional<InvestigationEvidence> contains =
        evidence.stream()
            .filter(
                item -> {
                  String itemName = normalizeEntityToken(item.name());
                  return !itemName.isBlank()
                      && itemName.contains(normalized)
                      && normalized.length() >= 3;
                })
            .findFirst();
    if (contains.isPresent()) {
      return contains;
    }
    Set<String> queryTokens = significantTokens(normalized);
    if (queryTokens.isEmpty()) {
      return Optional.empty();
    }
    return evidence.stream()
        .filter(
            item -> {
              Set<String> entityTokens =
                  significantTokens(normalizeEntityToken(item.name()));
              return !entityTokens.isEmpty() && entityTokens.containsAll(queryTokens);
            })
        .findFirst();
  }

  private static Map<String, String> primaryFieldsFromEvidence(
      InvestigationEvidence evidence) {
    Map<String, String> primary = new LinkedHashMap<>();
    putPrimary(primary, "Probability", evidence.structured().get("Probability"));
    putPrimary(primary, "Stage", evidence.structured().get("Stage"));
    putPrimary(primary, "Amount", evidence.structured().get("Amount"));
    putPrimary(primary, "Close Date", evidence.structured().get("Close"));
    putPrimary(primary, "Modified", evidence.structured().get("Modified"));
    putPrimary(primary, "Account", evidence.structured().get("Account"));
    putPrimary(primary, "Owner", evidence.structured().get("Owner"));
    putPrimary(primary, "Next Step", evidence.structured().get("Next Step"));
    return primary;
  }

  private static List<InvestigationEvidence> evidenceFromLedger(
      List<DealLedgerEntry> ledger, List<InvestigationEvidence> exploreEvidence) {
    if (ledger == null || ledger.isEmpty()) {
      return exploreEvidence;
    }
    Map<String, InvestigationEvidence> exploreByChunkId = new LinkedHashMap<>();
    Map<String, InvestigationEvidence> exploreByPath = new LinkedHashMap<>();
    Map<String, InvestigationEvidence> exploreByNameAccount = new LinkedHashMap<>();
    for (InvestigationEvidence item : exploreEvidence == null ? List.<InvestigationEvidence>of() : exploreEvidence) {
      if (item.chunkId() != null && !item.chunkId().isBlank()) {
        exploreByChunkId.putIfAbsent(item.chunkId().trim(), item);
      }
      if (item.path() != null && !item.path().isBlank()) {
        exploreByPath.putIfAbsent(item.path().trim(), item);
      }
      exploreByNameAccount.putIfAbsent(evidenceJoinKey(item.name(), accountFromEvidence(item)), item);
    }
    List<InvestigationEvidence> evidence = new ArrayList<>();
    for (DealLedgerEntry entry : ledger) {
      if (entry.demo()
          || (entry.flags() != null && entry.flags().contains("test_demo_pattern"))) {
        continue;
      }
      InvestigationEvidence explore = null;
      if (entry.chunkId() != null && !entry.chunkId().isBlank()) {
        explore = exploreByChunkId.get(entry.chunkId());
      }
      if (explore == null && entry.path() != null && !entry.path().isBlank()) {
        explore = exploreByPath.get(entry.path());
      }
      if (explore == null) {
        explore = exploreByNameAccount.get(evidenceJoinKey(entry.name(), entry.accountName()));
      }
      Map<String, String> structured = new LinkedHashMap<>(entry.primaryFields());
      if (structured.containsKey("Close Date") && !structured.containsKey("Close")) {
        structured.put("Close", structured.get("Close Date"));
      }
      String narrative =
          entry.narrative() != null && !entry.narrative().isBlank()
              ? entry.narrative()
              : explore == null ? "" : explore.narrative();
      evidence.add(
          new InvestigationEvidence(
              IndexConstants.SOURCE_TYPE_ZOHO,
              entry.name(),
              explore == null ? 0f : explore.score(),
              structured,
              answerFacingFlags(entry.flags()),
              explore == null ? List.of() : explore.contacts(),
              explore == null ? List.of() : explore.notes(),
              narrative,
              entry.chunkId(),
              entry.path()));
    }
    // Keep Slack/Notion/etc explore hits so structured CRM paths still surface cross-source
    // conflict evidence in the response payload (deterministic answers remain ledger-based).
    if (exploreEvidence != null) {
      for (InvestigationEvidence item : exploreEvidence) {
        if (item.source() != null
            && !item.source().isBlank()
            && !IndexConstants.SOURCE_TYPE_ZOHO.equals(item.source())) {
          evidence.add(item);
        }
      }
    }
    return List.copyOf(evidence);
  }

  private static String accountFromEvidence(InvestigationEvidence item) {
    if (item.structured() == null) {
      return "";
    }
    String account = item.structured().get("Account");
    return account == null ? "" : account;
  }

  private static String evidenceJoinKey(String name, String account) {
    String n = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    String a = account == null ? "" : account.trim().toLowerCase(Locale.ROOT);
    return n + "\0" + a;
  }

  static Set<String> normalizeSourceTypeFilter(List<String> sourceTypes) {
    if (sourceTypes == null || sourceTypes.isEmpty()) {
      return Set.of();
    }
    return sourceTypes.stream()
        .filter(type -> type != null && !type.isBlank())
        .map(type -> type.trim().toUpperCase(Locale.ROOT))
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  static ExploreService.ExploreResult applySourceTypeFilter(
      ExploreService.ExploreResult explore, Set<String> sourceFilter) {
    if (explore == null || sourceFilter == null || sourceFilter.isEmpty()) {
      return explore;
    }
    SearchResult original = explore.searchResult();
    if (original == null || original.hits() == null || original.hits().isEmpty()) {
      return explore;
    }
    List<SearchHit> filtered =
        original.hits().stream()
            .filter(
                hit ->
                    hit.sourceType()
                        .map(type -> sourceFilter.contains(type.trim().toUpperCase(Locale.ROOT)))
                        .orElse(false))
            .toList();
    if (filtered.size() == original.hits().size()) {
      return explore;
    }
    StringBuilder section = new StringBuilder("Hybrid search hits:\n");
    for (SearchHit hit : filtered) {
      section
          .append("- [")
          .append(hit.sourceType().orElse("?"))
          .append("] ")
          .append(hit.fileName() == null ? hit.chunkId() : hit.fileName())
          .append(" score=")
          .append(String.format(Locale.ROOT, "%.3f", hit.score()))
          .append('\n');
      hit.content()
          .ifPresent(
              content -> {
                String trimmed = content.length() > 400 ? content.substring(0, 400) + "…" : content;
                section.append("  ").append(trimmed.replace("\n", "\n  ")).append('\n');
              });
    }
    SearchResult filteredResult =
        new SearchResult(
            filtered,
            filtered.size(),
            original.elapsed(),
            original.diagnostics());
    return new ExploreService.ExploreResult(
        section.toString().trim(), explore.references(), filteredResult);
  }

  private static List<InvestigationEvidence> buildEvidence(ExploreService.ExploreResult explore) {
    List<InvestigationEvidence> evidence = new ArrayList<>();
    for (SearchHit hit : explore.searchResult().hits()) {
      String sourceType = hit.sourceType().orElse("");
      if (sourceType.isBlank()) {
        continue;
      }
      if (IndexConstants.SOURCE_TYPE_ZOHO.equals(sourceType)) {
        ZohoHitMetadata zoho = hit.zoho().orElse(ZohoHitMetadata.EMPTY);
        Map<String, String> structured = new LinkedHashMap<>();
        putIfPresent(structured, "Amount", zoho.amount().orElse(""));
        putIfPresent(structured, "Expected_Revenue", zoho.expectedRevenue().orElse(""));
        putIfPresent(structured, "Probability", zoho.probability().orElse(""));
        putIfPresent(structured, "Stage", zoho.stage().orElse(""));
        putIfPresent(structured, "Close", zoho.closingDate().orElse(""));
        hit.timestamp().ifPresent(value -> putIfPresent(structured, "Modified", value));
        putIfPresent(structured, "Account", zoho.accountName().orElse(""));
        putIfPresent(structured, "Owner", zoho.owner().orElse(""));
        putIfPresent(structured, "Lead_Source", zoho.leadSource().orElse(""));
        putIfPresent(structured, "Next Step", zoho.nextStep().orElse(""));
        evidence.add(
            new InvestigationEvidence(
                sourceType,
                hitDisplayName(hit),
                hit.score(),
                structured,
                answerFacingFlags(zoho.qualitySignals()),
                zoho.contacts(),
                zoho.noteSnippets(),
                hit.content().orElse(""),
                hit.chunkId() == null ? "" : hit.chunkId(),
                hit.path() == null ? "" : hit.path()));
      } else {
        evidence.add(
            new InvestigationEvidence(
                sourceType,
                hitDisplayName(hit),
                hit.score(),
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                hit.content().orElse(""),
                hit.chunkId() == null ? "" : hit.chunkId(),
                hit.path() == null ? "" : hit.path()));
      }
    }
    return List.copyOf(evidence);
  }

  private static String formatEvidence(List<InvestigationEvidence> evidence) {
    StringBuilder formatted = new StringBuilder();
    for (InvestigationEvidence item : evidence) {
      formatted
          .append("[")
          .append(item.source())
          .append("] ")
          .append(item.name())
          .append(" score=")
          .append(String.format("%.3f", item.score()))
          .append('\n');
      formatted.append("Structured: ").append(item.structured()).append('\n');
      if (!item.flags().isEmpty()) {
        formatted.append("Flags: ").append(item.flags()).append('\n');
      }
      if (!item.contacts().isEmpty()) {
        formatted.append("Contacts: ").append(item.contacts()).append('\n');
      }
      for (String note : item.notes()) {
        formatted.append("Notes (unverified free-text): ").append(note).append('\n');
      }
      formatted.append("Narrative: ").append(item.narrative()).append("\n\n");
    }
    return formatted.toString().trim();
  }

  private static void putIfPresent(Map<String, String> values, String key, String value) {
    if (value != null && !value.isBlank()) {
      values.put(key, value);
    }
  }

  private static void putPrimary(Map<String, String> values, String key, String value) {
    putIfPresent(values, key, value);
  }

  private static List<String> answerFacingFlags(List<String> signals) {
    return signals.stream()
        .filter(
            signal ->
                switch (signal) {
                  case "test_demo_pattern",
                      "stale_record",
                      "close_date_passed",
                      "missing_account_description",
                      "no_contacts",
                      "no_notes",
                      "no_activities",
                      "unknown_stage" ->
                      true;
                  default -> false;
                })
        .toList();
  }

  public enum AnswerIntent {
    QUALIFICATION,
    RANK,
    REASON,
    /** @deprecated Prefer {@link #RANK}; kept for older metadata consumers. */
    @Deprecated
    HIGHEST_PROBABILITY,
    GENERAL
  }

  record QueryClassification(
      boolean parsed,
      String intent,
      String field,
      String direction,
      double confidence,
      String ambiguityReason,
      String entity,
      List<String> fieldValues) {

    QueryClassification {
      fieldValues = fieldValues == null ? List.of() : List.copyOf(fieldValues);
    }

    /** Back-compat helper for tests/call sites that omit fieldValues. */
    QueryClassification(
        boolean parsed,
        String intent,
        String field,
        String direction,
        double confidence,
        String ambiguityReason,
        String entity) {
      this(parsed, intent, field, direction, confidence, ambiguityReason, entity, List.of());
    }

    /** Back-compat helper for tests that omit entity and fieldValues. */
    QueryClassification(
        boolean parsed,
        String intent,
        String field,
        String direction,
        double confidence,
        String ambiguityReason) {
      this(parsed, intent, field, direction, confidence, ambiguityReason, null, List.of());
    }

    boolean confident() {
      return parsed && confidence >= CLASSIFICATION_CONFIDENCE_THRESHOLD;
    }

    static QueryClassification unparsed() {
      return new QueryClassification(false, "general", null, null, 0.0, null, null, List.of());
    }
  }

  record LedgerSplit(List<DealLedgerEntry> eligible, List<DealLedgerEntry> demoOnly) {}

  record PipelineHealthFacts(
      int eligibleDealCount,
      int demoDealCount,
      int highProbabilityDealCount,
      double maxEligibleProbability,
      boolean demoHeavy,
      boolean thinPipeline,
      Map<String, Integer> stageDistribution,
      int missingContacts,
      int missingAccountDescription,
      int missingNotes,
      int missingActivities,
      double eligibleOpenPipelineAmount,
      int eligibleAccountCount) {

    public PipelineHealthFacts {
      stageDistribution =
          stageDistribution == null ? Map.of() : Map.copyOf(stageDistribution);
    }
  }

  record IntelligenceAnswer(
      String understoodIntent,
      List<String> primaryRecords,
      String summary,
      List<String> reasons,
      List<String> peers) {}

  record NextStepRepair(boolean repaired, String summary, List<String> reasons) {
    static NextStepRepair unchanged(String summary, List<String> reasons) {
      return new NextStepRepair(
          false,
          summary == null ? "" : summary,
          reasons == null ? List.of() : List.copyOf(reasons));
    }

    NextStepRepair {
      summary = summary == null ? "" : summary;
      reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
  }

  record GuardedIntelligence(
      boolean success,
      String status,
      List<DealLedgerEntry> primaryEntries,
      String summary,
      List<String> reasons,
      List<String> peers,
      boolean crossCheckApplied,
      String message) {

    static GuardedIntelligence fail(String message) {
      return new GuardedIntelligence(
          false, "rejected", List.of(), "", List.of(), List.of(), false, message);
    }

    static GuardedIntelligence ok(
        List<DealLedgerEntry> primaryEntries,
        String summary,
        List<String> reasons,
        List<String> peers,
        boolean crossCheckApplied) {
      return new GuardedIntelligence(
          true,
          crossCheckApplied ? "ok_cross_checked" : "ok",
          List.copyOf(primaryEntries),
          summary,
          List.copyOf(reasons),
          List.copyOf(peers),
          crossCheckApplied,
          null);
    }
  }

  private record IntelligenceOutcome(
      InvestigationAnswer answer, Map<String, Object> metadata) {}

  record StructuredGeneralAnswer(
      String selectedRecord, String summary, List<String> reasons) {}

  public record InvestigationEvidence(
      String source,
      String name,
      float score,
      Map<String, String> structured,
      List<String> flags,
      List<String> contacts,
      List<String> notes,
      String narrative,
      String chunkId,
      String path) {

    public InvestigationEvidence {
      structured = structured == null ? Map.of() : Map.copyOf(structured);
      flags = flags == null ? List.of() : List.copyOf(flags);
      contacts = contacts == null ? List.of() : List.copyOf(contacts);
      notes = notes == null ? List.of() : List.copyOf(notes);
      narrative = narrative == null ? "" : narrative;
      chunkId = chunkId == null ? "" : chunkId.trim();
      path = path == null ? "" : path.trim();
    }

    /** Compatibility constructor used by tests that omit chunk identity. */
    public InvestigationEvidence(
        String source,
        String name,
        float score,
        Map<String, String> structured,
        List<String> flags,
        List<String> contacts,
        List<String> notes,
        String narrative) {
      this(source, name, score, structured, flags, contacts, notes, narrative, "", "");
    }
  }

  public record InvestigationAnswer(
      String title,
      String recordName,
      Map<String, String> primaryFields,
      List<String> reasons,
      Map<String, Double> scoreBreakdown,
      String modelId,
      String trustState,
      String intent,
      String summaryText) {

    public InvestigationAnswer {
      primaryFields = primaryFields == null ? Map.of() : Map.copyOf(primaryFields);
      reasons = reasons == null ? List.of() : List.copyOf(reasons);
      scoreBreakdown = scoreBreakdown == null ? Map.of() : Map.copyOf(scoreBreakdown);
      trustState = trustState == null ? "insufficient_data" : trustState;
      intent = intent == null ? AnswerIntent.GENERAL.name() : intent;
    }
  }

  public record InvestigateResponse(
      String summary,
      String details,
      Map<String, Object> metadata,
      List<InvestigationEvidence> evidence,
      InvestigationAnswer answer) {}
}
