package com.andromedia.core.investigate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.andromedia.common.ChunkUnitType;
import com.andromedia.common.IndexConstants;
import com.andromedia.common.IndexPathHolder;
import com.andromedia.common.ServiceIndexPathResolver;
import com.andromedia.llm.ChatCompletionService;
import com.andromedia.llm.RagPrompt;
import com.andromedia.search.ExploreService;
import com.andromedia.search.SearchHit;
import com.andromedia.search.SearchResult;
import com.andromedia.search.ZohoHitMetadata;
import com.andromedia.search.ZohoQualificationService;
import com.andromedia.search.ZohoQualificationService.DealLedgerEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InvestigationServiceTest {

  private static final String SERVICE = "hybrid-crm-test";

  @Mock private ExploreService exploreService;
  @Mock private ChatCompletionService chatCompletionService;
  @Mock private ZohoQualificationService zohoQualificationService;

  private Path serviceIndexPath;

  @BeforeEach
  void setUp() throws Exception {
    IndexPathHolder.clear();
    serviceIndexPath = ServiceIndexPathResolver.resolveIndexPath(SERVICE);
    Files.createDirectories(serviceIndexPath);
    writePlaceholderDoc(serviceIndexPath);
  }

  @AfterEach
  void tearDown() throws Exception {
    IndexPathHolder.clear();
    if (serviceIndexPath != null && Files.exists(serviceIndexPath)) {
      try (Stream<Path> walk = Files.walk(serviceIndexPath)) {
        walk.sorted(Comparator.reverseOrder())
            .forEach(
                path -> {
                  try {
                    Files.deleteIfExists(path);
                  } catch (Exception ignored) {
                    // best-effort cleanup for the disposable test index
                  }
                });
      }
    }
  }

  @Test
  void splitLedgerSeparatesDemoFromEligible() {
    DealLedgerEntry chapman = demoDeal("Chapman", 90, "70000");
    DealLedgerEntry king = realDeal("King ERP", 60, "60000", "2026-07-19");
    InvestigationService.LedgerSplit split =
        InvestigationService.splitLedger(List.of(chapman, king));
    assertEquals(1, split.eligible().size());
    assertEquals("King ERP", split.eligible().getFirst().name());
    assertEquals(1, split.demoOnly().size());
    assertEquals("Chapman", split.demoOnly().getFirst().name());
  }

  @Test
  void guardRejectsDemoPrimaryAndFabricatedNames() {
    DealLedgerEntry chapman = demoDeal("Chapman", 90, "70000");
    DealLedgerEntry king = realDeal("King ERP", 60, "60000", "2026-07-19");
    InvestigationService.LedgerSplit split =
        InvestigationService.splitLedger(List.of(chapman, king));
    InvestigationService.QueryClassification reason =
        new InvestigationService.QueryClassification(true, "reason", null, null, 0.95, null);

    InvestigationService.GuardedIntelligence demoRejected =
        InvestigationService.applyInvariantGuard(
            "most important deal",
            reason,
            split,
            new InvestigationService.IntelligenceAnswer(
                "most important",
                List.of("Chapman"),
                "Chapman is most important",
                List.of("90%"),
                List.of()));
    assertFalse(demoRejected.success());
    assertTrue(demoRejected.message().toLowerCase().contains("demo"));

    InvestigationService.GuardedIntelligence fabricated =
        InvestigationService.applyInvariantGuard(
            "most important deal",
            reason,
            split,
            new InvestigationService.IntelligenceAnswer(
                "most important",
                List.of("Not A Real Deal"),
                "Invented",
                List.of(),
                List.of()));
    assertFalse(fabricated.success());
    assertTrue(fabricated.message().toLowerCase().contains("hallucinated")
        || fabricated.message().toLowerCase().contains("not in the indexed"));

    InvestigationService.GuardedIntelligence blended =
        InvestigationService.applyInvariantGuard(
            "most important deal",
            reason,
            split,
            new InvestigationService.IntelligenceAnswer(
                "most important",
                List.of("King Chapman Mega Deal"),
                "Blended fictional hybrid",
                List.of(),
                List.of()));
    assertFalse(blended.success());
  }

  @Test
  void investigateGracefulFallbackWhenModelHallucinatesNonExistentDeal() {
    when(zohoQualificationService.listActiveDealLedger(any(Path.class)))
        .thenReturn(
            List.of(
                realDeal("King ERP", 60, "60000", "2026-07-19"),
                realDeal("CRM License Renewal", 10, "250000", "2026-08-01")));
    when(exploreService.explore(anyString(), eq(null))).thenReturn(exploreResult(kingHit()));
    stubChatSequence(
        classificationJson("reason", null, null, 0.94),
        """
        {
          "understoodIntent": "status of Acme Expansion deal",
          "primaryRecords": ["Acme Expansion"],
          "summary": "Acme Expansion is progressing well at 75% probability.",
          "reasons": ["Invented from training data"],
          "peers": []
        }
        """);

    InvestigationService service =
        new InvestigationService(
            exploreService,
            chatCompletionService,
            zohoQualificationService,
            new com.andromedia.core.index.ActiveZohoIndexStore());

    InvestigationService.InvestigateResponse response =
        service.investigate(SERVICE, "What's going on with the Acme Expansion deal?", Map.of());

    // C4: guard rejection becomes a usable eligible-deal list, not a sole dead-end.
    assertEquals("graceful_fallback", response.metadata().get("intelligenceGuard"));
    assertEquals("structured", response.answer().trustState());
    assertNotNull(response.answer().recordName());
    assertTrue(
        response.summary().toLowerCase().contains("eligible")
            || response.summary().toLowerCase().contains("ask about"));
    assertFalse(response.summary().toLowerCase().contains("75%"));
    assertFalse(response.summary().toLowerCase().contains("acme expansion is progressing"));
  }

  @Test
  void guardCrossChecksHighestProbabilityOverNonDemo() {
    DealLedgerEntry low = realDeal("CRM License Renewal", 10, "250000", "2026-08-01");
    DealLedgerEntry high = realDeal("King ERP", 60, "60000", "2026-07-19");
    DealLedgerEntry demo = demoDeal("Chapman", 90, "70000");
    InvestigationService.LedgerSplit split =
        InvestigationService.splitLedger(List.of(low, high, demo));
    InvestigationService.QueryClassification rank =
        new InvestigationService.QueryClassification(
            true, "rank", "Probability", "desc", 0.95, null);

    InvestigationService.GuardedIntelligence guarded =
        InvestigationService.applyInvariantGuard(
            "Which deal has the highest probability?",
            rank,
            split,
            new InvestigationService.IntelligenceAnswer(
                "highest probability",
                List.of("CRM License Renewal"),
                "CRM License Renewal wins",
                List.of("Largest amount"),
                List.of()));

    assertTrue(guarded.success());
    assertTrue(guarded.crossCheckApplied());
    assertEquals("King ERP", guarded.primaryEntries().getFirst().name());
  }

  @Test
  void followUpGuardPrefersTopScoredDealLikeRelianceOverDistantLowProb() {
    DealLedgerEntry reliance =
        scoredDeal(
            "Reliance Data Center Expansion",
            "Reliance",
            80,
            "850000",
            "2026-07-28",
            "Confirm PO with procurement",
            List.of(),
            0.82);
    DealLedgerEntry distant =
        scoredDeal(
            "Long Tail Pilot",
            "Pilot Co",
            15,
            "12000",
            "2026-12-15",
            "",
            List.of("no_notes", "stale_record"),
            0.18);
    InvestigationService.LedgerSplit split =
        InvestigationService.splitLedger(List.of(reliance, distant));
    InvestigationService.QueryClassification qualify =
        new InvestigationService.QueryClassification(true, "qualify", null, null, 0.92, null);

    InvestigationService.GuardedIntelligence guarded =
        InvestigationService.applyInvariantGuard(
            "Which deal should I follow up this week?",
            qualify,
            split,
            new InvestigationService.IntelligenceAnswer(
                "follow up this week",
                List.of("Long Tail Pilot"),
                "Long Tail Pilot needs attention",
                List.of("Mentioned in notes"),
                List.of()));

    assertTrue(guarded.success());
    assertTrue(guarded.crossCheckApplied());
    assertEquals("Reliance Data Center Expansion", guarded.primaryEntries().getFirst().name());
    assertTrue(guarded.summary().toLowerCase().contains("reliance"));
  }

  @Test
  void intelligenceLedgerTableIncludesQualificationScore() {
    String table =
        InvestigationService.formatLedgerTable(
            List.of(
                scoredDeal(
                    "Reliance Data Center Expansion",
                    "Reliance",
                    80,
                    "850000",
                    "2026-07-28",
                    "Confirm PO",
                    List.of(),
                    0.82)));
    assertTrue(table.contains("Score: 0.820"));
    assertTrue(table.contains("Reliance Data Center Expansion"));
  }

  @Test
  void mostImportantUsesIntelligenceLayerAndNeverPicksDemo() {
    when(zohoQualificationService.listActiveDealLedger(any(Path.class)))
        .thenReturn(
            List.of(
                demoDeal("Chapman", 90, "70000"),
                realDeal("King ERP", 60, "60000", "2026-07-19"),
                realDeal("CRM License Renewal", 10, "250000", "2026-08-01")));
    when(exploreService.explore(anyString(), eq(null)))
        .thenReturn(exploreResult(kingHit(), zohoHit("Chapman", "Negotiation/Review", "90", "70000")));
    stubChatSequence(
        classificationJson("reason", null, null, 0.94),
        """
        {
          "understoodIntent": "most important active deal",
          "primaryRecords": ["King ERP"],
          "summary": "King ERP is the most important non-demo deal at 60% probability in Id. Decision Makers.",
          "reasons": ["Highest close likelihood among non-demo deals", "Later pipeline stage than CRM License Renewal"],
          "peers": []
        }
        """);

    InvestigationService service =
        new InvestigationService(
            exploreService,
            chatCompletionService,
            zohoQualificationService,
            new com.andromedia.core.index.ActiveZohoIndexStore());

    InvestigationService.InvestigateResponse response =
        service.investigate(SERVICE, "What's going on with our most important deal?", Map.of());

    verify(zohoQualificationService, never()).qualifyActiveDeals(any());
    verify(zohoQualificationService, never()).rankActiveDeals(any(), any(), any());
    assertEquals("King ERP", response.answer().recordName());
    assertEquals("REASON", response.answer().intent());
    assertEquals(InvestigationService.INTELLIGENCE_MODEL_ID, response.answer().modelId());
    assertFalse(response.answer().recordName().toLowerCase().contains("chapman"));
    assertTrue(response.evidence().stream().noneMatch(item -> "Chapman".equals(item.name())));
    assertTrue(
        response.evidence().stream()
            .noneMatch(item -> item.flags().contains("test_demo_pattern")));
    assertTrue(response.evidence().stream().anyMatch(item -> "King ERP".equals(item.name())));

    ArgumentCaptor<RagPrompt> promptCaptor = ArgumentCaptor.forClass(RagPrompt.class);
    verify(chatCompletionService, org.mockito.Mockito.atLeastOnce())
        .streamAnswer(promptCaptor.capture(), any(), any());
    assertTrue(
        promptCaptor.getAllValues().stream()
            .anyMatch(
                prompt ->
                    prompt.userMessage().contains("Ordered eligible non-demo ledger")
                        && prompt.userMessage().contains("King ERP")
                        && !prompt.userMessage().contains("[DEAL] Chapman")));
    assertTrue(
        promptCaptor.getAllValues().stream()
            .anyMatch(
                prompt ->
                    prompt.systemMessage() != null
                        && prompt.systemMessage().contains("INVESTIGATE INTEGRITY RULES")));
  }

  @Test
  void staleAtRiskReturnsMultipleNonDemoPeers() {
    when(zohoQualificationService.listActiveDealLedger(any(Path.class)))
        .thenReturn(
            List.of(
                demoDeal("Benton", 10, "35000"),
                realDeal("Truhlar And Truhlar Attys", 20, "45000", "2026-07-17"),
                realDeal("Chanay", 20, "55000", "2026-07-18")));
    when(exploreService.explore(anyString(), eq(null))).thenReturn(exploreResult(kingHit()));
    stubChatSequence(
        classificationJson("reason", null, null, 0.93),
        """
        {
          "understoodIntent": "deals at risk of going stale",
          "primaryRecords": ["Truhlar And Truhlar Attys", "Chanay"],
          "summary": "Truhlar And Truhlar Attys and Chanay are the non-demo deals with the nearest close dates.",
          "reasons": ["Close dates 2026-07-17 and 2026-07-18", "Low probability"],
          "peers": ["Truhlar And Truhlar Attys", "Chanay"]
        }
        """);

    InvestigationService service =
        new InvestigationService(
            exploreService,
            chatCompletionService,
            zohoQualificationService,
            new com.andromedia.core.index.ActiveZohoIndexStore());

    InvestigationService.InvestigateResponse response =
        service.investigate(SERVICE, "Which deals are at risk of going stale?", Map.of());

    assertTrue(response.answer().recordName().contains("Truhlar"));
    assertTrue(response.answer().recordName().contains("Chanay"));
    assertFalse(response.answer().recordName().contains("Benton"));
    assertTrue(response.evidence().stream().noneMatch(item -> "Benton".equals(item.name())));
    assertTrue(
        response.evidence().stream()
            .anyMatch(item -> "Truhlar And Truhlar Attys".equals(item.name())));
  }

  @Test
  void followUpThisWeekUsesNonDemoIntelligenceAnswer() {
    when(zohoQualificationService.listActiveDealLedger(any(Path.class)))
        .thenReturn(
            List.of(
                demoDeal("Chapman", 90, "70000"),
                realDeal("King ERP", 60, "60000", "2026-07-19"),
                realDeal("CRM License Renewal", 10, "250000", "2026-08-01")));
    when(exploreService.explore(anyString(), eq(null))).thenReturn(exploreResult(kingHit()));
    stubChatSequence(
        classificationJson("qualify", null, null, 0.95),
        """
        {
          "understoodIntent": "follow up this week",
          "primaryRecords": ["King ERP"],
          "summary": "Follow up on King ERP this week — 60% probability and close date 2026-07-19.",
          "reasons": ["Closest meaningful close among non-demo", "Higher probability than CRM License Renewal"],
          "peers": []
        }
        """);

    InvestigationService service =
        new InvestigationService(
            exploreService,
            chatCompletionService,
            zohoQualificationService,
            new com.andromedia.core.index.ActiveZohoIndexStore());

    InvestigationService.InvestigateResponse response =
        service.investigate(SERVICE, "Who should we follow up with this week?", Map.of());

    assertEquals("King ERP", response.answer().recordName());
    assertEquals("60%", response.answer().primaryFields().get("Probability"));
    assertEquals(true, response.metadata().get("qualificationIntent"));
    assertEquals("qualify", response.metadata().get("classificationIntent"));
    assertEquals("QUALIFICATION", response.answer().intent());
  }

  @Test
  void followUpNextMonthHeuristicClassifiesAsQualify() {
    InvestigationService.QueryClassification classification =
        InvestigationService.heuristicClassification(
            "Which is the best deal to follow up next month?");
    assertEquals("qualify", classification.intent());
    assertEquals(
        InvestigationService.AnswerIntent.QUALIFICATION,
        InvestigationService.detectIntent("Which is the best deal to follow up next month?"));
  }

  @Test
  void evidenceKeepsDistinctNarrativesForDuplicateDealNames() {
    DealLedgerEntry manu =
        new DealLedgerEntry(
            "Chandru V",
            "Manu Connection",
            Map.of(
                "Probability", "10%",
                "Stage", "Qualification",
                "Amount", "35000",
                "Account", "Manu Connection"),
            List.of(),
            false,
            10.0,
            35000.0,
            Instant.parse("2026-07-16T00:00:00Z"),
            "chunk-manu",
            "zoho://deal/manu",
            "[ZOHO] Deal · Chandru V · Qualification · Manu Connection · updated 2026-07-16\n\nStructured:\nAccount: Manu Connection\n");
    DealLedgerEntry hifi =
        new DealLedgerEntry(
            "Chandru V",
            "Tech Hifi",
            Map.of(
                "Probability", "10%",
                "Stage", "Qualification",
                "Amount", "42000",
                "Account", "Tech Hifi"),
            List.of(),
            false,
            10.0,
            42000.0,
            Instant.parse("2026-07-16T00:00:00Z"),
            "chunk-hifi",
            "zoho://deal/hifi",
            "[ZOHO] Deal · Chandru V · Qualification · Tech Hifi · updated 2026-07-16\n\nStructured:\nAccount: Tech Hifi\n");
    when(zohoQualificationService.listActiveDealLedger(any(Path.class)))
        .thenReturn(List.of(manu, hifi));
    // Explore returns a single colliding name with the wrong account narrative.
    ZohoHitMetadata zoho =
        new ZohoHitMetadata(
            Optional.of("Qualification"),
            Optional.of("99999"),
            Optional.empty(),
            Optional.of("10"),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of("J. Brannam"),
            Optional.empty(),
            Optional.of("0.5"),
            List.of(),
            List.of(),
            List.of());
    SearchHit colliding =
        new SearchHit(
            "zoho://deal/brannam",
            "Chandru V",
            0.8f,
            "chunk-brannam",
            null,
            null,
            ChunkUnitType.ZOHO,
            0,
            0,
            null,
            null,
            List.of(),
            List.of(),
            List.of(),
            Optional.of(IndexConstants.SOURCE_TYPE_ZOHO),
            Optional.empty(),
            Optional.of("2026-07-16T00:00:00Z"),
            Optional.of(
                "[ZOHO] Deal · Chandru V · Qualification · J. Brannam · updated 2026-07-16"),
            Optional.of(zoho));
    when(exploreService.explore(anyString(), eq(null))).thenReturn(exploreResult(colliding));
    stubChatSequence(
        classificationJson("qualify", null, null, 0.95),
        """
        {
          "understoodIntent": "best deal to follow up",
          "primaryRecords": ["Chandru V"],
          "summary": "Follow up on Chandru V at Manu Connection.",
          "reasons": ["Active deal"],
          "peers": []
        }
        """);

    InvestigationService service =
        new InvestigationService(
            exploreService,
            chatCompletionService,
            zohoQualificationService,
            new com.andromedia.core.index.ActiveZohoIndexStore());
    InvestigationService.InvestigateResponse response =
        service.investigate(SERVICE, "Which is the best deal to follow up next month?", Map.of());

    assertEquals(2, response.evidence().size());
    InvestigationService.InvestigationEvidence manuEvidence =
        response.evidence().stream()
            .filter(e -> "Manu Connection".equals(e.structured().get("Account")))
            .findFirst()
            .orElseThrow();
    InvestigationService.InvestigationEvidence hifiEvidence =
        response.evidence().stream()
            .filter(e -> "Tech Hifi".equals(e.structured().get("Account")))
            .findFirst()
            .orElseThrow();
    assertTrue(manuEvidence.narrative().contains("Manu Connection"));
    assertTrue(hifiEvidence.narrative().contains("Tech Hifi"));
    assertFalse(manuEvidence.narrative().contains("J. Brannam"));
    assertFalse(hifiEvidence.narrative().contains("J. Brannam"));
  }

  @Test
  void gapQuestionReturnsMissingFieldReasoning() {
    DealLedgerEntry king =
        new DealLedgerEntry(
            "ERP Implementation",
            "King",
            Map.of(
                "Probability", "55%",
                "Stage", "Needs Analysis",
                "Amount", "120000",
                "Account", "King"),
            List.of("missing_account_description", "no_notes"),
            false,
            55.0,
            120000.0,
            Instant.parse("2026-07-10T00:00:00Z"));
    when(zohoQualificationService.listActiveDealLedger(any(Path.class))).thenReturn(List.of(king));
    when(exploreService.explore(anyString(), eq(null))).thenReturn(exploreResult(kingHit()));
    stubChatSequence(
        classificationJson("reason", null, null, 0.92),
        """
        {
          "understoodIntent": "gaps on top account",
          "primaryRecords": ["ERP Implementation"],
          "summary": "For King we are missing an account description and deal notes, so follow-up context is thin.",
          "reasons": ["Flag missing_account_description", "Flag no_notes", "Only structured deal fields are present"],
          "peers": []
        }
        """);

    InvestigationService service =
        new InvestigationService(
            exploreService,
            chatCompletionService,
            zohoQualificationService,
            new com.andromedia.core.index.ActiveZohoIndexStore());

    InvestigationService.InvestigateResponse response =
        service.investigate(SERVICE, "What do we not know about our top account?", Map.of());

    assertEquals("ERP Implementation", response.answer().recordName());
    assertTrue(
        response.answer().reasons().stream()
            .anyMatch(reason -> reason.toLowerCase().contains("missing") || reason.toLowerCase().contains("notes")));
    assertTrue(response.summary().toLowerCase().contains("missing") || response.summary().toLowerCase().contains("notes"));
  }

  @Test
  void highestProbabilityUsesIntelligenceWithNumericCrossCheck() {
    when(zohoQualificationService.listActiveDealLedger(any(Path.class)))
        .thenReturn(
            List.of(
                demoDeal("Chapman", 90, "70000"),
                realDeal("King ERP", 60, "60000", "2026-07-19"),
                realDeal("CRM License Renewal", 10, "250000", "2026-08-01")));
    when(exploreService.explore(anyString(), eq(null))).thenReturn(exploreResult(kingHit()));
    stubChatSequence(
        classificationJson("rank", "Probability", "desc", 0.94),
        """
        {
          "understoodIntent": "highest probability",
          "primaryRecords": ["CRM License Renewal"],
          "summary": "CRM License Renewal has the highest probability.",
          "reasons": ["Wrong pick"],
          "peers": []
        }
        """);

    InvestigationService service =
        new InvestigationService(
            exploreService,
            chatCompletionService,
            zohoQualificationService,
            new com.andromedia.core.index.ActiveZohoIndexStore());

    InvestigationService.InvestigateResponse response =
        service.investigate(SERVICE, "Which deal has the highest probability rate?", Map.of());

    // F1: an explicit max-by-field question is a pure Java comparison over the eligible deals —
    // it never reaches the LLM intelligence layer, so it cannot be talked out of the right answer
    // (the stubbed model above picks the wrong deal on purpose).
    assertEquals("King ERP", response.answer().recordName());
    assertEquals(true, response.metadata().get("deterministicAnswer"));
    assertEquals("deterministic", response.answer().trustState());
  }

  @Test
  void normalCrmLookupDoesNotUseIntelligenceLayer() {
    when(exploreService.explore(anyString(), eq(null))).thenReturn(exploreResult(kingHit()));
    stubChatSequence(
        classificationJson("lookup", null, null, 0.9),
        """
        {
          "selectedRecord": "ERP Implementation",
          "summary": "King has an ERP Implementation deal at Needs Analysis.",
          "reasons": ["Selected from structured King account evidence."]
        }
        """);

    InvestigationService service =
        new InvestigationService(
            exploreService,
            chatCompletionService,
            zohoQualificationService,
            new com.andromedia.core.index.ActiveZohoIndexStore());

    InvestigationService.InvestigateResponse response =
        service.investigate(SERVICE, "What's going on with the King account?", Map.of());

    verify(zohoQualificationService, never()).listActiveDealLedger(any());
    assertEquals("ERP Implementation", response.answer().recordName());
    assertEquals("GENERAL", response.answer().intent());
    assertNull(response.metadata().get("intelligenceModel"));
  }

  @Test
  void sparseDemoHeavyPipelineSurfacesHealthAndNextStep() {
    List<DealLedgerEntry> ledger = new java.util.ArrayList<>();
    for (String name :
        List.of(
            "Chapman",
            "King",
            "Truhlar",
            "Benton",
            "Printing Dimensions",
            "Feltz",
            "Chemel",
            "Chanay",
            "Morlong")) {
      ledger.add(demoDeal(name, 80, "50000"));
    }
    DealLedgerEntry renewal =
        new DealLedgerEntry(
            "CRM License Renewal",
            "TechNova Solutions",
            Map.of(
                "Probability", "10%",
                "Stage", "Qualification",
                "Amount", "250000",
                "Close Date", "2026-07-24",
                "Account", "TechNova Solutions",
                "Next Step", "Schedule product demo"),
            List.of("no_contacts", "missing_account_description", "no_notes", "no_activities"),
            false,
            10.0,
            250000.0,
            Instant.parse("2026-07-16T00:00:00Z"));
    ledger.add(renewal);

    when(zohoQualificationService.listActiveDealLedger(any(Path.class))).thenReturn(ledger);
    when(exploreService.explore(anyString(), eq(null))).thenReturn(exploreResult(kingHit()));
    stubChatSequence(
        classificationJson("reason", null, null, 0.94),
        """
        {
          "understoodIntent": "most important deal in a sparse pipeline",
          "primaryRecords": ["CRM License Renewal"],
          "summary": "The only real deal in your pipeline is CRM License Renewal — TechNova Solutions, $250,000, Qualification, 10% probability. Your pipeline currently consists of 9 sample/demo deals and 1 real deal. No deals are currently in a high-probability stage — this could indicate a pipeline that needs more focus. Next Step: Schedule product demo.",
          "reasons": ["Only non-demo deal", "thinPipeline=true", "Next Step: Schedule product demo", "Recommendation: add real deals before relying on pipeline intelligence"],
          "peers": []
        }
        """);

    InvestigationService service =
        new InvestigationService(
            exploreService,
            chatCompletionService,
            zohoQualificationService,
            new com.andromedia.core.index.ActiveZohoIndexStore());

    InvestigationService.InvestigateResponse response =
        service.investigate(SERVICE, "What's going on with our most important deal?", Map.of());

    assertEquals("CRM License Renewal", response.answer().recordName());
    assertEquals(true, response.metadata().get("demoHeavy"));
    assertEquals(true, response.metadata().get("thinPipeline"));
    assertEquals(0, response.metadata().get("highProbabilityDealCount"));
    assertEquals("Schedule product demo", response.answer().primaryFields().get("Next Step"));
    assertTrue(response.summary().toLowerCase().contains("9"));
    assertTrue(response.summary().toLowerCase().contains("schedule product demo"));
    assertTrue(
        response.summary().toLowerCase().contains("high-probability")
            || response.summary().toLowerCase().contains("high probability"));

    ArgumentCaptor<RagPrompt> promptCaptor = ArgumentCaptor.forClass(RagPrompt.class);
    verify(chatCompletionService, org.mockito.Mockito.atLeastOnce())
        .streamAnswer(promptCaptor.capture(), any(), any());
    assertTrue(
        promptCaptor.getAllValues().stream()
            .anyMatch(
                prompt ->
                    prompt.userMessage().contains("PipelineHealth")
                        && prompt.userMessage().contains("demoHeavy=true")
                        && prompt.userMessage().contains("thinPipeline=true")
                        && prompt.userMessage().contains("Next Step: Schedule product demo")));
  }

  @Test
  void guardRepairsFabricatedNextStepAndRejectsFalseHighProbabilityClaim() {
    DealLedgerEntry renewal =
        new DealLedgerEntry(
            "CRM License Renewal",
            "TechNova Solutions",
            Map.of(
                "Probability", "10%",
                "Stage", "Qualification",
                "Amount", "250000",
                "Next Step", "Schedule product demo"),
            List.of("no_contacts"),
            false,
            10.0,
            250000.0,
            Instant.parse("2026-07-16T00:00:00Z"));
    InvestigationService.LedgerSplit split =
        InvestigationService.splitLedger(List.of(renewal, demoDeal("Chapman", 90, "70000")));
    InvestigationService.PipelineHealthFacts health =
        InvestigationService.computePipelineHealth(split);
    InvestigationService.QueryClassification reason =
        new InvestigationService.QueryClassification(true, "reason", null, null, 0.95, null);

    InvestigationService.GuardedIntelligence fabricatedNext =
        InvestigationService.applyInvariantGuard(
            "most important deal",
            reason,
            split,
            health,
            new InvestigationService.IntelligenceAnswer(
                "most important",
                List.of("CRM License Renewal"),
                "CRM License Renewal. Next Step: Close the contract tomorrow.",
                List.of(),
                List.of()));
    assertTrue(fabricatedNext.success());
    assertTrue(fabricatedNext.summary().toLowerCase().contains("recommendation"));
    assertFalse(fabricatedNext.summary().toLowerCase().contains("next step: close"));
    assertTrue(
        fabricatedNext.reasons().stream()
            .anyMatch(r -> r.toLowerCase().contains("recommendation")));

    InvestigationService.GuardedIntelligence falseHealth =
        InvestigationService.applyInvariantGuard(
            "pipeline health",
            reason,
            split,
            health,
            new InvestigationService.IntelligenceAnswer(
                "pipeline health",
                List.of("CRM License Renewal"),
                "You have a healthy high-probability pipeline with several strong deals.",
                List.of(),
                List.of()));
    assertFalse(falseHealth.success());
    assertTrue(falseHealth.message().toLowerCase().contains("high-probability"));
  }

  @Test
  void integrityRulesArePrependedToSystemPrompt() {
    String combined =
        InvestigationService.withIntegrityRules("You are a CRM intelligence layer.");
    assertTrue(combined.startsWith("INVESTIGATE INTEGRITY RULES"));
    assertTrue(combined.contains("Never invent an entity") || combined.contains("never invent"));
    assertTrue(combined.contains("You are a CRM intelligence layer."));
  }

  @Test
  void integrityRulesResourceLoadsFromClasspath() {
    InvestigationIntegrityRules.clearCacheForTests();
    String rules = InvestigationIntegrityRules.load();
    assertTrue(rules.contains("Rule 1"));
    assertTrue(rules.contains("Rule 9"));
    assertTrue(rules.toLowerCase().contains("never invent"));
  }

  @Test
  void sortEligibleHealthiestFirstAndRepairsShuffledPrimaryRecords() {
    DealLedgerEntry low =
        scoredDeal(
            "Low Prob Deal",
            "Acme",
            20,
            "10000",
            "2026-08-01",
            "Call",
            List.of("stale_record"),
            0.2);
    DealLedgerEntry high =
        scoredDeal(
            "High Prob Deal",
            "Beta",
            80,
            "50000",
            "2026-08-15",
            "Demo",
            List.of(),
            0.9);
    InvestigationService.LedgerSplit split =
        InvestigationService.splitLedger(List.of(low, high));
    InvestigationService.QueryClassification reason =
        new InvestigationService.QueryClassification(true, "reason", null, null, 0.95, null);

    List<DealLedgerEntry> ordered =
        InvestigationService.sortEligibleForQuestion(
            "Rank our accounts from healthiest to most at risk", reason, split.eligible());
    assertEquals("High Prob Deal", ordered.get(0).name());
    assertEquals("Low Prob Deal", ordered.get(1).name());

    InvestigationService.GuardedIntelligence guarded =
        InvestigationService.applyInvariantGuard(
            "Rank our accounts from healthiest to most at risk",
            reason,
            split,
            InvestigationService.computePipelineHealth(split),
            new InvestigationService.IntelligenceAnswer(
                "rank health",
                List.of("Low Prob Deal", "High Prob Deal"),
                "Ranked Low Prob Deal then High Prob Deal by health.",
                List.of("model shuffled order"),
                List.of()));
    assertTrue(guarded.success());
    assertEquals("High Prob Deal", guarded.primaryEntries().get(0).name());
    assertEquals("Low Prob Deal", guarded.primaryEntries().get(1).name());
    // The repair must be reported to the user in plain language — no "primaryRecords"/"ledger".
    assertTrue(
        guarded.reasons().stream()
            .anyMatch(r -> r.toLowerCase().contains("reordered") || r.toLowerCase().contains("corrected")));
    assertTrue(
        guarded.reasons().stream().noneMatch(r -> r.contains("primaryRecords") || r.contains("ledger")));
  }

  @Test
  void fuzzyResolveMatchesDamacShortForm() {
    DealLedgerEntry damac =
        scoredDeal(
            "Damac Properties Renewal",
            "Damac Properties",
            40,
            "120000",
            "2026-09-01",
            "Follow up",
            List.of("stale_record"),
            0.4);
    List<DealLedgerEntry> ledger = List.of(damac, demoDeal("Chapman", 90, "70000"));
    assertTrue(InvestigationService.resolveNamedEntity("Damac", ledger).isPresent());
    assertTrue(InvestigationService.resolveNamedEntity("the Damac deal", ledger).isPresent());
    assertTrue(
        InvestigationService.resolveNamedEntity("Damac Properties", ledger).isPresent());
    assertEquals(
        "Damac Properties Renewal",
        InvestigationService.resolveNamedEntity("Damac", ledger).orElseThrow().name());
  }

  @Test
  void generalCompletenessClaimIsRewrittenAsPartial() {
    InvestigationService.InvestigationAnswer answer =
        new InvestigationService.InvestigationAnswer(
            "LLM final answer",
            null,
            Map.of(),
            List.of("Retrieved top hits"),
            Map.of(),
            "zoho-grounded-v1",
            "structured",
            "GENERAL",
            "Here are all accounts in the CRM: Acme, Beta.");
    InvestigationService.InvestigationAnswer guarded =
        InvestigationService.guardGeneralCompletenessClaims(answer);
    assertTrue(guarded.summaryText().startsWith("Partial result from retrieved evidence"));
    assertTrue(
        guarded.reasons().stream().anyMatch(r -> r.toLowerCase().contains("completeness")));
  }

  @Test
  void parsesReasonClassification() {
    Optional<InvestigationService.QueryClassification> reason =
        InvestigationService.parseClassification(
            """
            {"intent":"reason","field":null,"direction":null,"confidence":0.93,"ambiguityReason":null}
            """);
    assertTrue(reason.isPresent());
    assertTrue(InvestigationService.shouldUseIntelligenceLayer(reason.get()));
  }

  @Test
  void intelligenceAndGeneralPromptsRefuseOutsideKnowledge() {
    assertTrue(
        InvestigationService.INTELLIGENCE_SYSTEM_PROMPT
            .toLowerCase()
            .contains("world knowledge"));
    assertTrue(
        InvestigationService.INTELLIGENCE_SYSTEM_PROMPT.contains("no_notes")
            || InvestigationService.INTELLIGENCE_SYSTEM_PROMPT.contains("no_activities"));
    assertTrue(
        InvestigationService.INTELLIGENCE_SYSTEM_PROMPT
            .toLowerCase()
            .contains("insufficient"));

    String general =
        InvestigationService.structuredGeneralPrompt(
            "Are Infosys and INFY the same?",
            List.of(),
            Map.of());
    assertTrue(general.toLowerCase().contains("world knowledge"));
    assertTrue(general.contains("no_notes") || general.contains("no_activities"));
    assertTrue(general.toLowerCase().contains("insufficient"));
  }

  @Test
  void formatLedgerTableIncludesNotesOrNone() {
    DealLedgerEntry withNotes =
        new DealLedgerEntry(
            "Reliance Data Center Expansion",
            "Reliance Digital Ventures",
            Map.of(
                "Probability",
                "80%",
                "Stage",
                "Negotiation/Review",
                "Amount",
                "680000",
                "Close Date",
                "2026-07-25",
                "Account",
                "Reliance Digital Ventures"),
            List.of("no_activities"),
            false,
            80,
            680000,
            Instant.parse("2026-07-20T00:00:00Z"),
            "chunk-1",
            "zoho/reliance",
            """
            Deal: Reliance Data Center Expansion
            Notes:
            - Call - discovery: capacity planning and rack requirements
            - Email - commercial terms: waiting on redlines
            Activities:
            """);
    String table = InvestigationService.formatLedgerTable(List.of(withNotes));
    assertTrue(table.contains("Notes (unverified):"));
    assertTrue(table.toLowerCase().contains("capacity planning"));
    assertTrue(table.contains("Activities: none"));

    DealLedgerEntry sparse =
        new DealLedgerEntry(
            "Tata Consulting Pilot Program",
            "Tata Consulting Services",
            Map.of(
                "Probability",
                "15%",
                "Stage",
                "Qualification",
                "Amount",
                "350000",
                "Close Date",
                "2026-09-01",
                "Account",
                "Tata Consulting Services"),
            List.of("no_notes", "no_contacts", "no_activities"),
            false,
            15,
            350000,
            Instant.parse("2026-07-19T00:00:00Z"),
            "chunk-2",
            "zoho/tata",
            "Deal: Tata Consulting Pilot Program");
    String sparseTable = InvestigationService.formatLedgerTable(List.of(sparse));
    assertTrue(sparseTable.contains("Notes: none"));
    assertTrue(sparseTable.contains("Contacts: none"));
    assertTrue(sparseTable.contains("Activities: none"));
  }

  @Test
  void generalPathNullSelectedRecordIsInsufficientData() {
    when(exploreService.explore(anyString(), eq(null))).thenReturn(exploreResult(kingHit()));
    stubChatSequence(
        classificationJson("lookup", null, null, 0.9),
        """
        {
          "selectedRecord": null,
          "summary": "Infosys Limited and INFY Technologies are the same company based on the INFY ticker.",
          "reasons": ["General market knowledge"]
        }
        """);

    InvestigationService service =
        new InvestigationService(
            exploreService,
            chatCompletionService,
            zohoQualificationService,
            new com.andromedia.core.index.ActiveZohoIndexStore());

    InvestigationService.InvestigateResponse response =
        service.investigate(
            SERVICE, "Are Infosys Limited and INFY Technologies the same entity?", Map.of());

    assertNull(response.answer().recordName());
    assertEquals("insufficient_data", response.answer().trustState());
    assertEquals("GENERAL", response.answer().intent());
  }

  @Test
  void intelligenceEmptyPrimaryRecordsYieldsInsufficientData() {
    when(zohoQualificationService.listActiveDealLedger(any(Path.class)))
        .thenReturn(
            List.of(
                realDeal("King ERP", 60, "60000", "2026-07-19"),
                realDeal("CRM License Renewal", 10, "250000", "2026-08-01")));
    when(exploreService.explore(anyString(), eq(null))).thenReturn(exploreResult(kingHit()));
    stubChatSequence(
        classificationJson("reason", null, null, 0.94),
        """
        {
          "understoodIntent": "entity identity of INFY vs Infosys",
          "primaryRecords": [],
          "summary": "The indexed CRM evidence does not contain enough information to confirm whether INFY Technologies and Infosys Limited are the same entity.",
          "reasons": ["INFY Technologies does not appear with linking fields in the ledger"],
          "peers": []
        }
        """);

    InvestigationService service =
        new InvestigationService(
            exploreService,
            chatCompletionService,
            zohoQualificationService,
            new com.andromedia.core.index.ActiveZohoIndexStore());

    InvestigationService.InvestigateResponse response =
        service.investigate(
            SERVICE, "Are Infosys Limited and INFY Technologies the same company?", Map.of());

    assertNull(response.answer().recordName());
    assertEquals("insufficient_data", response.answer().trustState());
    assertEquals("insufficient_evidence", response.metadata().get("intelligenceGuard"));
    assertTrue(response.summary().toLowerCase().contains("insufficient")
        || response.summary().toLowerCase().contains("does not contain"));
  }

  @Test
  void parsesEnumerateAndAggregateClassification() {
    Optional<InvestigationService.QueryClassification> enumerate =
        InvestigationService.parseClassification(
            """
            {"intent":"enumerate","field":null,"direction":null,"entity":null,"confidence":0.94,"ambiguityReason":null}
            """);
    assertTrue(enumerate.isPresent());
    assertTrue(InvestigationService.shouldUseStructuredCrmPath(enumerate.get()));
    assertTrue(InvestigationService.isDeterministicStructuredIntent(enumerate.get()));

    Optional<InvestigationService.QueryClassification> aggregate =
        InvestigationService.parseClassification(
            """
            {"intent":"aggregate","field":null,"direction":null,"entity":null,"confidence":0.91,"ambiguityReason":null}
            """);
    assertTrue(aggregate.isPresent());
    assertTrue(InvestigationService.shouldUseStructuredCrmPath(aggregate.get()));
  }

  @Test
  void enumerateDeterministicExcludesDemoAccounts() {
    DealLedgerEntry real =
        accountDeal("Al Habtoor Phase 2", "Al Habtoor Group", 55, "310000", List.of());
    DealLedgerEntry demoCorp =
        accountDeal("Demo Deal", "Demo Corp", 100, "500000", List.of("test_demo_pattern"), true);
    DealLedgerEntry testAcct =
        accountDeal(
            "Test Deal Ignore", "Test Account 1", 100, "999000", List.of("test_demo_pattern"), true);
    DealLedgerEntry chapman = demoDeal("Chapman", 90, "70000");
    InvestigationService.LedgerSplit split =
        InvestigationService.splitLedger(List.of(real, demoCorp, testAcct, chapman));
    InvestigationService.QueryClassification classification =
        new InvestigationService.QueryClassification(
            true, "enumerate", null, null, 0.95, null, null);

    InvestigationService.InvestigationAnswer answer =
        InvestigationService.tryDeterministicStructuredAnswer(classification, split).orElseThrow();

    assertTrue(answer.summaryText().contains("Al Habtoor Group"));
    assertFalse(answer.summaryText().contains("Demo Corp"));
    assertFalse(answer.summaryText().contains("Test Account 1"));
    assertFalse(answer.summaryText().contains("Chapman"));
    assertTrue(answer.summaryText().toLowerCase().contains("excluded"));
    assertEquals("deterministic", answer.trustState());
  }

  @Test
  void aggregateDeterministicIgnoresDemoAmounts() {
    DealLedgerEntry real =
        accountDeal("Emaar Pilot", "Emaar Retail", 50, "220000", List.of());
    DealLedgerEntry demo =
        accountDeal("Demo Deal", "Demo Corp", 100, "500000", List.of("test_demo_pattern"), true);
    InvestigationService.LedgerSplit split =
        InvestigationService.splitLedger(List.of(real, demo));
    InvestigationService.QueryClassification classification =
        new InvestigationService.QueryClassification(
            true, "aggregate", null, null, 0.93, null, null);

    InvestigationService.InvestigationAnswer answer =
        InvestigationService.tryDeterministicStructuredAnswer(classification, split).orElseThrow();
    InvestigationService.PipelineHealthFacts health =
        InvestigationService.computePipelineHealth(split);

    assertEquals(220000.0, health.eligibleOpenPipelineAmount(), 0.01);
    assertTrue(answer.summaryText().contains("220000"));
    assertFalse(answer.summaryText().contains("500000"));
    assertTrue(answer.summaryText().toLowerCase().contains("excluded"));
  }

  @Test
  void namedEntityStalenessUsesSharedPredicateForAnyEntity() {
    DealLedgerEntry staleA =
        accountDeal(
            "Alpha Quiet Deal",
            "Alpha Holdings",
            20,
            "95000",
            List.of("close_date_passed", "no_activities"),
            false,
            Instant.parse("2025-01-01T00:00:00Z"));
    DealLedgerEntry staleB =
        accountDeal(
            "Beta Quiet Deal",
            "Beta Industries",
            15,
            "60000",
            List.of("close_date_passed"),
            false,
            Instant.parse("2025-02-01T00:00:00Z"));
    DealLedgerEntry fresh =
        accountDeal(
            "Gamma Hot Deal",
            "Gamma Corp",
            55,
            "200000",
            List.of(),
            false,
            Instant.parse("2026-07-20T00:00:00Z"));
    InvestigationService.LedgerSplit split =
        InvestigationService.splitLedger(List.of(staleA, staleB, fresh));

    assertTrue(InvestigationService.isStale(staleA));
    assertTrue(InvestigationService.isStale(staleB));
    assertFalse(InvestigationService.isStale(fresh));

    InvestigationService.InvestigationAnswer forAlpha =
        InvestigationService.tryDeterministicStructuredAnswer(
                new InvestigationService.QueryClassification(
                    true, "named_entity_staleness", null, null, 0.94, null, "Alpha Holdings"),
                split)
            .orElseThrow();
    InvestigationService.InvestigationAnswer forBeta =
        InvestigationService.tryDeterministicStructuredAnswer(
                new InvestigationService.QueryClassification(
                    true, "named_entity_staleness", null, null, 0.94, null, "Beta Quiet Deal"),
                split)
            .orElseThrow();
    InvestigationService.InvestigationAnswer forGamma =
        InvestigationService.tryDeterministicStructuredAnswer(
                new InvestigationService.QueryClassification(
                    true, "named_entity_staleness", null, null, 0.94, null, "Gamma Corp"),
                split)
            .orElseThrow();

    assertTrue(forAlpha.summaryText().toLowerCase().contains("stale"));
    assertTrue(forBeta.summaryText().toLowerCase().contains("stale"));
    assertTrue(forGamma.summaryText().toLowerCase().contains("does not meet"));
  }

  @Test
  void enumerateInvestigatePathDoesNotCallSecondLlm() {
    when(zohoQualificationService.listActiveDealLedger(any(Path.class)))
        .thenReturn(
            List.of(
                accountDeal("Sobha Renewal", "Sobha Realty", 20, "60000", List.of()),
                demoDeal("Chapman", 90, "70000")));
    when(exploreService.explore(anyString(), eq(null)))
        .thenReturn(
            exploreResult(
                kingHit(), zohoHit("Chapman", "Negotiation/Review", "90", "70000")));
    stubChatSequence(classificationJson("enumerate", null, null, 0.95));

    InvestigationService service =
        new InvestigationService(
            exploreService,
            chatCompletionService,
            zohoQualificationService,
            new com.andromedia.core.index.ActiveZohoIndexStore());

    InvestigationService.InvestigateResponse response =
        service.investigate(SERVICE, "which companies are we tracking?", Map.of());

    assertEquals(Boolean.TRUE, response.metadata().get("deterministicAnswer"));
    assertTrue(response.summary().contains("Sobha Realty"));
    assertFalse(response.summary().contains("Chapman"));
    verify(chatCompletionService, org.mockito.Mockito.times(1))
        .streamAnswer(any(RagPrompt.class), any(), any());
  }

  @Test
  void rateLimitDuringClassifySurfacesAsInvestigationRateLimitException() {
    doAnswer(
            invocation -> {
              throw new com.andromedia.llm.ChatException(
                  "Groq chat request failed after 3 attempts (last: status 429)");
            })
        .when(chatCompletionService)
        .streamAnswer(any(RagPrompt.class), any(), any());

    InvestigationService service =
        new InvestigationService(
            exploreService,
            chatCompletionService,
            zohoQualificationService,
            new com.andromedia.core.index.ActiveZohoIndexStore());

    assertThrows(
        InvestigationRateLimitException.class,
        () -> service.investigate(SERVICE, "total pipeline value", Map.of()));
  }

  @Test
  void investigateEmitsClassifySearchDemosAndComposeProgressInOrder() {
    when(zohoQualificationService.listActiveDealLedger(any(Path.class)))
        .thenReturn(
            List.of(
                accountDeal("Sobha Renewal", "Sobha Realty", 20, "60000", List.of()),
                demoDeal("Chapman", 90, "70000")));
    when(exploreService.explore(anyString(), eq(null)))
        .thenReturn(
            exploreResult(
                kingHit(), zohoHit("Chapman", "Negotiation/Review", "90", "70000")));
    stubChatSequence(classificationJson("enumerate", null, null, 0.95));

    InvestigationService service =
        new InvestigationService(
            exploreService,
            chatCompletionService,
            zohoQualificationService,
            new com.andromedia.core.index.ActiveZohoIndexStore());

    List<InvestigationProgressEvent> events = new java.util.ArrayList<>();
    service.investigate(
        SERVICE, "which companies are we tracking?", Map.of(), events::add);

    List<String> steps = events.stream().map(InvestigationProgressEvent::step).toList();
    assertTrue(steps.indexOf("classify") < steps.indexOf("search_hybrid"));
    assertTrue(steps.indexOf("search_hybrid") < steps.indexOf("search_zoho"));
    assertTrue(steps.indexOf("search_zoho") < steps.indexOf("demos"));
    assertTrue(steps.indexOf("demos") < steps.indexOf("compose"));
    assertTrue(steps.contains("search_zoho"));
    assertTrue(steps.contains("ledger"));
    assertFalse(steps.contains("search_slack"), "no Slack hits were returned, so no Slack step should fire");
    assertFalse(steps.contains("search_notion"), "no Notion hits were returned, so no Notion step should fire");
    assertTrue(
        events.stream()
            .anyMatch(
                e ->
                    "search_zoho".equals(e.step())
                        && "done".equals(e.status())
                        && e.detail() != null
                        && e.detail().contains("we found")));
    assertTrue(
        events.stream()
            .anyMatch(
                e -> "demos".equals(e.step()) && e.detail() != null && e.detail().contains("1")));
    assertTrue(
        events.stream()
            .anyMatch(e -> "classify".equals(e.step()) && "running".equals(e.status())));
    assertTrue(
        events.stream()
            .anyMatch(e -> "compose".equals(e.step()) && "done".equals(e.status())));
  }

  @Test
  void multiSourceExploreIncludesSlackEvidenceAndNamedProgress() {
    when(exploreService.explore(anyString(), eq(null)))
        .thenReturn(
            exploreResult(
                kingHit(),
                slackHit(
                    "#deals Al Habtoor",
                    "Agreed proposal amount with Al Habtoor is $450K — waiting on CRM update."),
                notionHit(
                    "Al Habtoor proposal",
                    "Proposal amount: $450,000 for Phase 2 Expansion.")));
    stubChatSequence(
        classificationJson("lookup", null, null, 0.9),
        """
        {
          "selectedRecord": null,
          "summary": "CRM shows $120000 while Slack and Notion mention a $450K proposal.",
          "reasons": ["Zoho CRM deal amount", "Slack #deals proposal amount", "Notion proposal page"]
        }
        """);

    InvestigationService service =
        new InvestigationService(
            exploreService,
            chatCompletionService,
            zohoQualificationService,
            new com.andromedia.core.index.ActiveZohoIndexStore());

    List<InvestigationProgressEvent> events = new java.util.ArrayList<>();
    InvestigationService.InvestigateResponse response =
        service.investigate(
            SERVICE,
            "What did we agree with Al Habtoor — the number in the proposal or the CRM record?",
            Map.of(),
            events::add);

    assertTrue(
        response.evidence().stream()
            .anyMatch(e -> IndexConstants.SOURCE_TYPE_SLACK.equals(e.source())));
    assertTrue(
        response.evidence().stream()
            .anyMatch(e -> IndexConstants.SOURCE_TYPE_NOTION.equals(e.source())));
    assertTrue(
        response.evidence().stream()
            .anyMatch(e -> IndexConstants.SOURCE_TYPE_ZOHO.equals(e.source())));
    assertTrue(events.stream().anyMatch(e -> "search_slack".equals(e.step())));
    assertTrue(events.stream().anyMatch(e -> "search_notion".equals(e.step())));
    assertTrue(events.stream().anyMatch(e -> "conflicts".equals(e.step())));
    assertTrue(
        events.stream()
            .anyMatch(
                e ->
                    "search_slack".equals(e.step())
                        && "done".equals(e.status())
                        && e.detail() != null
                        && e.detail().contains("Al Habtoor")));
  }

  @Test
  void zohoOnlyExploreOmitsSlackEvidenceAndProgress() {
    when(exploreService.explore(anyString(), eq(null))).thenReturn(exploreResult(kingHit()));
    stubChatSequence(
        classificationJson("lookup", null, null, 0.9),
        """
        {
          "selectedRecord": "ERP Implementation",
          "summary": "King has an ERP Implementation deal at Needs Analysis.",
          "reasons": ["Selected from structured King account evidence."]
        }
        """);

    InvestigationService service =
        new InvestigationService(
            exploreService,
            chatCompletionService,
            zohoQualificationService,
            new com.andromedia.core.index.ActiveZohoIndexStore());

    List<InvestigationProgressEvent> events = new java.util.ArrayList<>();
    InvestigationService.InvestigateResponse response =
        service.investigate(SERVICE, "What's going on with the King account?", Map.of(), events::add);

    assertTrue(
        response.evidence().stream()
            .allMatch(e -> IndexConstants.SOURCE_TYPE_ZOHO.equals(e.source())));
    assertFalse(events.stream().anyMatch(e -> "search_slack".equals(e.step())));
    assertFalse(events.stream().anyMatch(e -> "search_notion".equals(e.step())));
    assertFalse(events.stream().anyMatch(e -> "conflicts".equals(e.step())));
  }

  @Test
  void retryDuringClassifyEmitsRetryLlmProgressEvent() {
    AtomicInteger index = new AtomicInteger();
    String[] answers = {
      classificationJson("lookup", null, null, 0.9),
      """
      {
        "selectedRecord": "ERP Implementation",
        "summary": "King has an ERP Implementation deal at Needs Analysis.",
        "reasons": ["Selected from structured King account evidence."]
      }
      """
    };
    doAnswer(
            invocation -> {
              Consumer<String> consumer = invocation.getArgument(1);
              com.andromedia.llm.ChatProgressListener listener = invocation.getArgument(2);
              int i = index.getAndIncrement();
              if (i == 0) {
                listener.onRetry(1, 3, 50, 429);
              }
              consumer.accept(answers[Math.min(i, answers.length - 1)]);
              return null;
            })
        .when(chatCompletionService)
        .streamAnswer(any(RagPrompt.class), any(), any());
    when(exploreService.explore(anyString(), eq(null))).thenReturn(exploreResult(kingHit()));

    InvestigationService service =
        new InvestigationService(
            exploreService,
            chatCompletionService,
            zohoQualificationService,
            new com.andromedia.core.index.ActiveZohoIndexStore());

    List<InvestigationProgressEvent> events = new java.util.ArrayList<>();
    service.investigate(SERVICE, "What's going on with the King account?", Map.of(), events::add);

    assertTrue(
        events.stream()
            .anyMatch(
                e ->
                    "retry_llm".equals(e.step())
                        && e.detail() != null
                        && e.detail().contains("1/3")));
  }

  private void stubChatSequence(String... answers) {
    AtomicInteger index = new AtomicInteger();
    doAnswer(
            invocation -> {
              Consumer<String> consumer = invocation.getArgument(1);
              int i = Math.min(index.getAndIncrement(), answers.length - 1);
              consumer.accept(answers[i]);
              return null;
            })
        .when(chatCompletionService)
        .streamAnswer(any(RagPrompt.class), any(), any());
  }

  private static String classificationJson(
      String intent, String field, String direction, double confidence) {
    return classificationJson(intent, field, direction, confidence, null);
  }

  private static String classificationJson(
      String intent, String field, String direction, double confidence, String entity) {
    String fieldJson = field == null ? "null" : "\"" + field + "\"";
    String directionJson = direction == null ? "null" : "\"" + direction + "\"";
    String entityJson = entity == null ? "null" : "\"" + entity + "\"";
    return """
        {"intent":"%s","field":%s,"direction":%s,"entity":%s,"confidence":%s,"ambiguityReason":null}
        """
        .formatted(intent, fieldJson, directionJson, entityJson, confidence);
  }

  private static DealLedgerEntry accountDeal(
      String name, String account, double probability, String amount, List<String> flags) {
    return accountDeal(
        name, account, probability, amount, flags, flags.contains("test_demo_pattern"), Instant.now());
  }

  private static DealLedgerEntry accountDeal(
      String name,
      String account,
      double probability,
      String amount,
      List<String> flags,
      boolean demo) {
    return accountDeal(name, account, probability, amount, flags, demo, Instant.now());
  }

  private static DealLedgerEntry accountDeal(
      String name,
      String account,
      double probability,
      String amount,
      List<String> flags,
      boolean demo,
      Instant modified) {
    return new DealLedgerEntry(
        name,
        account,
        Map.of(
            "Probability",
            ((long) probability) + "%",
            "Stage",
            "Negotiation/Review",
            "Amount",
            amount,
            "Close Date",
            "2026-08-15",
            "Account",
            account,
            "Modified",
            modified.toString()),
        flags,
        demo,
        probability,
        Double.parseDouble(amount),
        modified);
  }

  private static DealLedgerEntry demoDeal(String name, double probability, String amount) {
    return new DealLedgerEntry(
        name,
        name + " (Sample)",
        Map.of(
            "Probability",
            ((long) probability) + "%",
            "Stage",
            "Negotiation/Review",
            "Amount",
            amount,
            "Close Date",
            "2026-07-17",
            "Account",
            name + " (Sample)"),
        List.of("test_demo_pattern"),
        true,
        probability,
        Double.parseDouble(amount),
        Instant.parse("2026-07-16T00:00:00Z"));
  }

  private static DealLedgerEntry realDeal(
      String name, double probability, String amount, String closeDate) {
    return new DealLedgerEntry(
        name,
        name.contains("King") ? "King" : name,
        Map.of(
            "Probability",
            ((long) probability) + "%",
            "Stage",
            probability >= 50 ? "Id. Decision Makers" : "Qualification",
            "Amount",
            amount,
            "Close Date",
            closeDate,
            "Account",
            name.contains("King") ? "King" : name),
        List.of(),
        false,
        probability,
        Double.parseDouble(amount),
        Instant.parse("2026-07-16T00:00:00Z"));
  }

  private static DealLedgerEntry scoredDeal(
      String name,
      String account,
      double probability,
      String amount,
      String closeDate,
      String nextStep,
      List<String> flags,
      double qualificationScore) {
    Map<String, String> fields = new java.util.LinkedHashMap<>();
    fields.put("Probability", ((long) probability) + "%");
    fields.put("Stage", probability >= 50 ? "Id. Decision Makers" : "Qualification");
    fields.put("Amount", amount);
    fields.put("Close Date", closeDate);
    fields.put("Account", account);
    fields.put("Next Step", nextStep == null || nextStep.isBlank() ? "—" : nextStep);
    fields.put("Modified", "2026-07-16");
    return new DealLedgerEntry(
        name,
        account,
        fields,
        flags,
        false,
        probability,
        Double.parseDouble(amount),
        Instant.parse("2026-07-16T00:00:00Z"),
        "chunk-" + name,
        "zoho/" + name,
        "",
        qualificationScore);
  }

  private static ExploreService.ExploreResult exploreResult(SearchHit... hits) {
    return new ExploreService.ExploreResult(
        "details", List.of(), new SearchResult(List.of(hits), hits.length, Duration.ZERO));
  }

  private static SearchHit kingHit() {
    ZohoHitMetadata zoho =
        new ZohoHitMetadata(
            Optional.of("Needs Analysis"),
            Optional.of("120000"),
            Optional.of("96000"),
            Optional.of("55"),
            Optional.of("2026-11-01"),
            Optional.of("Alex"),
            Optional.of("Inbound"),
            Optional.of("Schedule discovery call"),
            Optional.of("King"),
            Optional.of("Enterprise account"),
            Optional.of("0.8"),
            List.of(),
            List.of("Alex Rivera"),
            List.of("Follow up next week"));
    return new SearchHit(
        "zoho/king-erp",
        "ERP Implementation",
        0.91f,
        "zoho-king-erp",
        null,
        null,
        ChunkUnitType.ZOHO,
        0,
        0,
        null,
        null,
        List.of(),
        List.of(),
        List.of(),
        Optional.of(IndexConstants.SOURCE_TYPE_ZOHO),
        Optional.empty(),
        Optional.of("2026-07-10T00:00:00Z"),
        Optional.of("Deal narrative"),
        Optional.of(zoho));
  }

  private static SearchHit slackHit(String name, String content) {
    return slackHit(name, content, 0.82f);
  }

  private static SearchHit slackHit(String name, String content, float score) {
    return new SearchHit(
        "slack/" + name.toLowerCase().replace(' ', '-'),
        name,
        score,
        "slack-" + name.toLowerCase().replace(' ', '-'),
        null,
        null,
        ChunkUnitType.SLACK,
        0,
        0,
        null,
        null,
        List.of(),
        List.of(),
        List.of(),
        Optional.of(IndexConstants.SOURCE_TYPE_SLACK),
        Optional.empty(),
        Optional.of("2026-07-20T00:00:00Z"),
        Optional.of(content),
        Optional.empty());
  }

  private static SearchHit notionHit(String name, String content) {
    return new SearchHit(
        "notion/" + name.toLowerCase().replace(' ', '-'),
        name,
        0.8f,
        "notion-" + name.toLowerCase().replace(' ', '-'),
        null,
        null,
        ChunkUnitType.NOTION,
        0,
        0,
        null,
        null,
        List.of(),
        List.of(),
        List.of(),
        Optional.of(IndexConstants.SOURCE_TYPE_NOTION),
        Optional.empty(),
        Optional.of("2026-07-18T00:00:00Z"),
        Optional.of(content),
        Optional.empty());
  }

  private static SearchHit zohoHit(
      String name, String stage, String probability, String amount) {
    ZohoHitMetadata zoho =
        new ZohoHitMetadata(
            Optional.of(stage),
            Optional.of(amount),
            Optional.empty(),
            Optional.of(probability),
            Optional.of("2026-08-31"),
            Optional.of("Owner"),
            Optional.empty(),
            Optional.empty(),
            Optional.of(name + " (Sample)"),
            Optional.empty(),
            Optional.of("0.8"),
            List.of("test_demo_pattern"),
            List.of(),
            List.of());
    return new SearchHit(
        "zoho/" + name.toLowerCase().replace(' ', '-'),
        name,
        0.9f,
        "zoho-" + name.toLowerCase().replace(' ', '-'),
        null,
        null,
        ChunkUnitType.ZOHO,
        0,
        0,
        null,
        null,
        List.of(),
        List.of(),
        List.of(),
        Optional.of(IndexConstants.SOURCE_TYPE_ZOHO),
        Optional.empty(),
        Optional.of("2026-07-16T00:00:00Z"),
        Optional.of("Deal narrative"),
        Optional.of(zoho));
  }

  private static void writePlaceholderDoc(Path indexPath) throws Exception {
    try (FSDirectory directory = FSDirectory.open(indexPath);
        IndexWriter writer =
            new IndexWriter(directory, new IndexWriterConfig(new StandardAnalyzer()))) {
      Document document = new Document();
      document.add(new StringField(IndexConstants.FIELD_CHUNK_ID, "placeholder", Field.Store.YES));
      writer.addDocument(document);
      writer.commit();
    }
  }

  @Test
  void fuzzyResolveMatchesHabtoorViaCanonicalKey() {
    DealLedgerEntry habtoor =
        scoredDeal(
            "Al Habtoor – Phase 2 Expansion",
            "Al Habtoor Group",
            55,
            "310000",
            "2026-08-15",
            "",
            List.of(),
            0.55);
    List<DealLedgerEntry> ledger = List.of(habtoor);
    assertTrue(
        InvestigationService.resolveNamedEntity(
                "What's the current deal value for Al Habtoor?", ledger)
            .isPresent());
    assertEquals(
        "Al Habtoor – Phase 2 Expansion",
        InvestigationService.resolveNamedEntity("Al Habtoor", ledger).orElseThrow().name());
  }

  @Test
  void namedEntityStalenessIsNotCorpusWideSoPinCanRun() {
    assertFalse(
        InvestigationService.isCorpusWideStructuredIntent(
            new InvestigationService.QueryClassification(
                true, "named_entity_staleness", null, null, 0.94, null, "Acme Expansion")));
    assertTrue(
        InvestigationService.isCorpusWideStructuredIntent(
            new InvestigationService.QueryClassification(
                true, "corpus_staleness", null, null, 0.94, null, null)));
  }

  @Test
  void crmOnlyNamedEntityStalenessSkippedWhenSlackEvidencePresent() {
    InvestigationService.InvestigationEvidence zoho =
        new InvestigationService.InvestigationEvidence(
            IndexConstants.SOURCE_TYPE_ZOHO,
            "Acme Expansion",
            1f,
            Map.of("Amount", "120000"),
            List.of(),
            List.of(),
            List.of(),
            "CRM deal");
    InvestigationService.InvestigationEvidence slack =
        new InvestigationService.InvestigationEvidence(
            IndexConstants.SOURCE_TYPE_SLACK,
            "#all-causr",
            0.01f,
            Map.of(),
            List.of(),
            List.of(),
            List.of(),
            "Acme Expansion deal is actually stalled, Priya went quiet, we should mark it at risk");
    InvestigationService.QueryClassification staleness =
        new InvestigationService.QueryClassification(
            true, "named_entity_staleness", null, null, 0.94, null, "Acme Expansion");
    assertTrue(InvestigationService.useCrmOnlyNamedEntityStaleness(staleness, List.of(zoho)));
    assertFalse(
        InvestigationService.useCrmOnlyNamedEntityStaleness(staleness, List.of(zoho, slack)));
  }

  @Test
  void namedEntityAtRiskUsesSlackEvenWhenClassifiedAsStaleness() {
    DealLedgerEntry acme =
        accountDeal(
            "Acme Expansion",
            "Acme Corp",
            55,
            "120000",
            List.of("has_notes", "has_activities", "recently_modified"),
            false,
            Instant.parse("2026-08-17T17:31:54Z"));
    when(zohoQualificationService.listDealLedgerForEntityLookup(any(Path.class)))
        .thenReturn(List.of(acme));
    when(exploreService.explore(anyString(), eq(null)))
        .thenReturn(
            exploreResult(
                slackHit(
                    "#all-causr",
                    """
                    [SLACK] #all-causr · 2026-08-17 · Chandru
                    thread: 1 messages · reactions: 0

                    Chandru: Acme Expansion deal is actually stalled, Priya went quiet, we should mark it at risk
                    """,
                    0.010f)));
    stubChatSequence(
        classificationJson("named_entity_staleness", null, null, 0.94, "Acme Expansion"),
        """
        {
          "selectedRecord": "Acme Expansion",
          "summary": "CRM is recently modified, but Slack says Acme is stalled and Priya went quiet so the team should mark it at risk.",
          "reasons": ["Slack stall and quiet signal", "CRM recently modified"]
        }
        """);

    InvestigationService service =
        new InvestigationService(
            exploreService,
            chatCompletionService,
            zohoQualificationService,
            new com.andromedia.core.index.ActiveZohoIndexStore());

    InvestigationService.InvestigateResponse response =
        service.investigate(SERVICE, "Is the Acme deal at risk and what Priya did?", Map.of());

    assertFalse(response.summary().toLowerCase().contains("does not meet the staleness rule"));
    assertTrue(
        response.evidence().stream()
            .anyMatch(e -> IndexConstants.SOURCE_TYPE_SLACK.equals(e.source())));
    assertTrue(
        response.evidence().stream()
            .anyMatch(
                e ->
                    e.narrative() != null
                        && e.narrative().toLowerCase().contains("priya went quiet")));
    String summary = response.summary().toLowerCase();
    assertTrue(summary.contains("stalled") || summary.contains("quiet") || summary.contains("at risk"));
  }

  @Test
  void namedEntityAccountOnlySlackAttachesWithoutDealTitle() {
    DealLedgerEntry brightHorizon =
        accountDeal(
            "Bright Horizon - Enterprise License",
            "Bright Horizon Realty",
            90,
            "85000",
            List.of("has_notes", "has_activities", "recently_modified"),
            false,
            Instant.parse("2026-08-17T17:31:54Z"));
    when(zohoQualificationService.listDealLedgerForEntityLookup(any(Path.class)))
        .thenReturn(List.of(brightHorizon));
    when(exploreService.explore(anyString(), eq(null)))
        .thenReturn(
            exploreResult(
                slackHit(
                    "#all-causr",
                    """
                    [SLACK] #all-causr · 2026-08-17 · Chandru
                    thread: 1 messages · reactions: 0

                    Chandru: Bright Horizon deal is actually stalled, Fatima went quiet, we should mark it at risk
                    """,
                    0.010f)));
    stubChatSequence(
        classificationJson("lookup", null, null, 0.92, "Bright Horizon"),
        """
        {
          "selectedRecord": "#all-causr",
          "summary": "Slack says the Bright Horizon deal is stalled and Fatima went quiet, so the team should mark it at risk.",
          "reasons": ["Slack stall and quiet signal"]
        }
        """);

    InvestigationService service =
        new InvestigationService(
            exploreService,
            chatCompletionService,
            zohoQualificationService,
            new com.andromedia.core.index.ActiveZohoIndexStore());

    InvestigationService.InvestigateResponse response =
        service.investigate(
            SERVICE, "What has been said in Slack about Bright Horizon?", Map.of());

    assertFalse(response.summary().toLowerCase().contains("does not meet the staleness rule"));
    assertFalse(response.summary().toLowerCase().contains("no information about bright horizon in slack"));
    assertTrue(
        response.evidence().stream()
            .anyMatch(e -> IndexConstants.SOURCE_TYPE_SLACK.equals(e.source())));
    assertTrue(
        response.evidence().stream()
            .anyMatch(
                e ->
                    e.narrative() != null
                        && e.narrative().toLowerCase().contains("stalled")
                        && e.narrative().toLowerCase().contains("fatima went quiet")));
    assertTrue(
        response.evidence().stream()
            .filter(e -> IndexConstants.SOURCE_TYPE_SLACK.equals(e.source()))
            .noneMatch(
                e ->
                    e.flags()
                        .contains(InvestigationService.ACCOUNT_MENTION_UNCONFIRMED_DEAL_FLAG)));
    String summary = response.summary().toLowerCase();
    assertTrue(summary.contains("stalled") || summary.contains("quiet") || summary.contains("at risk"));
  }

  @Test
  void multiDealAccountOnlySlackKeepsUnconfirmedFlag() {
    DealLedgerEntry expansion =
        accountDeal("Acme Expansion", "Acme Corp", 55, "120000", List.of());
    DealLedgerEntry renewal =
        accountDeal("Acme Renewal", "Acme Corp", 40, "80000", List.of());
    InvestigationService.InvestigationEvidence slack =
        new InvestigationService.InvestigationEvidence(
            IndexConstants.SOURCE_TYPE_SLACK,
            "#all-causr",
            0.01f,
            Map.of(),
            List.of(),
            List.of(),
            List.of(),
            "Acme is stalled, we should mark it at risk");
    List<InvestigationService.InvestigationEvidence> related =
        InvestigationService.filterEvidenceForCanonicalEntity(
            List.of(slack), expansion, List.of(expansion, renewal));
    assertEquals(1, related.size());
    assertTrue(related.get(0).flags().contains(InvestigationService.ACCOUNT_MENTION_UNCONFIRMED_DEAL_FLAG));
    assertTrue(related.get(0).narrative().toLowerCase().contains("stalled"));

    when(zohoQualificationService.listDealLedgerForEntityLookup(any(Path.class)))
        .thenReturn(List.of(expansion, renewal));
    when(exploreService.explore(anyString(), eq(null)))
        .thenReturn(
            exploreResult(
                slackHit(
                    "#all-causr",
                    "Acme is stalled, we should mark it at risk",
                    0.010f)));
    stubChatSequence(
        classificationJson("lookup", null, null, 0.92, "Acme Expansion"),
        """
        {
          "selectedRecord": null,
          "summary": "Slack mentions Acme as stalled at the account level, but it is not confirmed for the Expansion deal specifically.",
          "reasons": ["Account-level Slack mention"]
        }
        """);

    InvestigationService service =
        new InvestigationService(
            exploreService,
            chatCompletionService,
            zohoQualificationService,
            new com.andromedia.core.index.ActiveZohoIndexStore());

    InvestigationService.InvestigateResponse response =
        service.investigate(SERVICE, "Is the Acme Expansion deal at risk?", Map.of());

    assertTrue(
        response.evidence().stream()
            .anyMatch(
                e ->
                    IndexConstants.SOURCE_TYPE_SLACK.equals(e.source())
                        && e.flags()
                            .contains(
                                InvestigationService.ACCOUNT_MENTION_UNCONFIRMED_DEAL_FLAG)));
  }

  @Test
  void synthesisConflictMarkerFlagsDivergentAmounts() {
    InvestigationService.InvestigationEvidence zoho =
        new InvestigationService.InvestigationEvidence(
            IndexConstants.SOURCE_TYPE_ZOHO,
            "Al Habtoor – Phase 2 Expansion",
            1f,
            Map.of("Amount", "310000"),
            List.of(),
            List.of(),
            List.of(),
            "CRM amount 310000");
    InvestigationService.InvestigationEvidence notion =
        new InvestigationService.InvestigationEvidence(
            IndexConstants.SOURCE_TYPE_NOTION,
            "Al Habtoor – Phase 2 Expansion",
            0.9f,
            Map.of(),
            List.of(),
            List.of(),
            List.of(),
            "Draft proposal sent to client: $450,000");
    String marker =
        InvestigationService.synthesisConflictMarker(
            List.of(zoho, notion), Map.of(), "al habtoor");
    assertTrue(marker != null && marker.startsWith("CONFLICT DETECTED"));
    assertTrue(marker.contains("450"));
  }

  @Test
  void synthesisConflictMarkerIgnoresSlackHeaderYear() {
    InvestigationService.InvestigationEvidence zoho =
        new InvestigationService.InvestigationEvidence(
            IndexConstants.SOURCE_TYPE_ZOHO,
            "Coastal Ventures LLC",
            1f,
            Map.of("Amount", "22000"),
            List.of(),
            List.of(),
            List.of(),
            "Pilot customer");
    InvestigationService.InvestigationEvidence slack =
        new InvestigationService.InvestigationEvidence(
            IndexConstants.SOURCE_TYPE_SLACK,
            "#sales",
            0.01f,
            Map.of(),
            List.of(),
            List.of(),
            List.of(),
            "[SLACK] #sales · 2026-08-17 · alice\nthread: 1 messages · reactions: 0\n\nalice: kickoff next week");
    assertNull(
        InvestigationService.synthesisConflictMarker(
            List.of(zoho, slack), Map.of(), "coastal ventures"));
    assertNull(
        InvestigationService.moneyFromText(
            "[SLACK] #sales · 2026-08-17 · alice\n2026-08-17T23:01:54+05:30 kickoff"));
  }

  @Test
  void conflictDetailDoesNotTreatSlackDateAsMoney() {
    String detail =
        InvestigationService.conflictDetail(
            List.of(
                kingHit(),
                slackHit(
                    "#sales",
                    "[SLACK] #sales · 2026-08-17 · alice\nthread: 1 messages · reactions: 0")),
            List.of(IndexConstants.SOURCE_TYPE_ZOHO, IndexConstants.SOURCE_TYPE_SLACK));
    assertFalse(detail.contains("$2026"));
    assertFalse(detail.contains("2026 in"));
  }

  @Test
  void conversationBagMergesPriorEntityForFollowUpPin() {
    assertTrue(
        InvestigationService.shouldAttemptNamedEntityPin(
            new InvestigationService.QueryClassification(
                true, "general", null, null, 0.5, null, null),
            Map.of("entity", "Al Habtoor Group")));
    Map<String, Object> bag =
        InvestigationService.buildConversationBag(
            "Al Habtoor",
            new InvestigationService.InvestigationAnswer(
                "t",
                "Al Habtoor – Phase 2 Expansion",
                Map.of("Amount", "310000"),
                List.of(),
                Map.of(),
                "m",
                "structured",
                "GENERAL",
                "CRM is 310000"),
            List.of(),
            Map.of());
    assertEquals("Al Habtoor", bag.get("entity"));
    assertEquals("retrieve_and_merge", bag.get("retrievalMode"));
  }
}
