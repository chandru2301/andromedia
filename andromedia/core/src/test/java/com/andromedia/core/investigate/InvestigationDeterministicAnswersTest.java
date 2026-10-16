package com.andromedia.core.investigate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.andromedia.core.investigate.InvestigationService.LedgerSplit;
import com.andromedia.core.investigate.InvestigationService.QueryClassification;
import com.andromedia.search.ArtifactReference;
import com.andromedia.search.CanonicalEntityKey;
import com.andromedia.search.ZohoQualificationService.DealLedgerEntry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for QA findings F1-F4 (2026-09-05): deterministic extreme/rank answers,
 * stage-filtered deal enumeration, honest entity-pin near-miss reporting (matcher level), and
 * jargon-free user copy. Exercises only the deterministic static paths — no mocks, no LLM.
 */
class InvestigationDeterministicAnswersTest {

  private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

  private static DealLedgerEntry deal(
      String name,
      String account,
      String stage,
      double probability,
      double amount,
      boolean demo) {
    return new DealLedgerEntry(
        name,
        account,
        Map.of(
            "Probability", ((long) probability) + "%",
            "Stage", stage,
            "Amount", String.valueOf((long) amount),
            "Account", account,
            "Close Date", "2026-09-30"),
        List.of(),
        demo,
        probability,
        amount,
        NOW);
  }

  private static QueryClassification classification(
      String intent, String field, String direction, List<String> fieldValues) {
    return new QueryClassification(true, intent, field, direction, 0.95, null, null, fieldValues);
  }

  private static Optional<InvestigationService.InvestigationAnswer> deterministic(
      QueryClassification classification, List<DealLedgerEntry> eligible, List<DealLedgerEntry> demo) {
    return InvestigationService.tryDeterministicStructuredAnswer(
        classification, new LedgerSplit(eligible, demo));
  }

  // ---------- F1: extreme-value questions are answered deterministically ----------

  @Test
  void rankExtremeFindsLargestAmountDealDeterministically() {
    List<DealLedgerEntry> eligible =
        List.of(
            deal("Wipro Annual License", "Wipro Enterprise Solutions", "Id. Decision Makers", 55, 1_500_000, false),
            deal("Pinnacle Grid Modernization", "Pinnacle Energy Holdings", "Value Proposition", 30, 4_200_000, false));
    var answer = deterministic(classification("rank", "amount", "desc", List.of()), eligible, List.of());

    assertTrue(answer.isPresent(), "rank+field must route to the deterministic path");
    InvestigationService.InvestigationAnswer a = answer.get();
    assertEquals("deterministic", a.trustState());
    assertEquals("Pinnacle Grid Modernization", a.recordName());
    assertTrue(a.summaryText().contains("Pinnacle Grid Modernization"));
    assertTrue(a.summaryText().contains("highest amount"));
  }

  @Test
  void rankExtremeExcludesDemoDealsAndReportsCountPlainly() {
    List<DealLedgerEntry> eligible =
        List.of(deal("Wipro Annual License", "Wipro", "Negotiation/Review", 55, 1_500_000, false));
    List<DealLedgerEntry> demo =
        List.of(deal("Demo Corp Mega Deal", "Demo Corp", "Negotiation/Review", 99, 9_000_000, true));

    var answer = deterministic(classification("rank", "amount", "desc", List.of()), eligible, demo);
    assertTrue(answer.isPresent());
    InvestigationService.InvestigationAnswer a = answer.get();
    assertEquals("Wipro Annual License", a.recordName(), "demo deal must never win the comparison");
    assertFalse(a.summaryText().contains("Demo Corp"), "demo deal must not appear in the answer");
    assertTrue(a.summaryText().contains("Excluded 1 demo/sample deal(s)"));
    assertFalse(a.summaryText().contains("test_demo_pattern"), "internal flag names must not leak");
  }

  @Test
  void rankExtremeOnDemoOnlyLedgerFailsPlainWithoutJargon() {
    List<DealLedgerEntry> demo =
        List.of(deal("Demo Corp Mega Deal", "Demo Corp", "Negotiation/Review", 99, 9_000_000, true));

    var answer = deterministic(classification("rank", "amount", "desc", List.of()), List.of(), demo);
    assertTrue(answer.isPresent());
    InvestigationService.InvestigationAnswer a = answer.get();
    assertEquals("insufficient_data", a.trustState());
    assertTrue(a.summaryText().contains("Only demo/sample deals are indexed"));
    assertFalse(a.summaryText().contains("test_demo_pattern"));
    assertFalse(a.summaryText().contains("non-demo"));
  }

  // ---------- F2: enumerate with a stage selector lists DEALS, not just accounts ----------

  @Test
  void enumerateDealsByStageListsDealRowsWithProbabilityAndExcludesDemo() {
    List<DealLedgerEntry> eligible =
        List.of(
            deal("Cascade Patient Portal", "Cascade Health Systems", "Proposal/Price Quote", 45, 210_000, false),
            deal("Meridian Renewal", "Meridian Property Group", "Proposal/Price Quote", 75, 40_000, false),
            deal("Sobha Renewal Discussion", "Sobha Realty", "Qualification", 20, 60_000, false));
    List<DealLedgerEntry> demo =
        List.of(deal("Demo Proposal Deal", "Demo Corp", "Proposal/Price Quote", 50, 1_000, true));

    var answer =
        deterministic(
            classification("enumerate", "stage", null, List.of("proposal")), eligible, demo);
    assertTrue(answer.isPresent());
    InvestigationService.InvestigationAnswer a = answer.get();
    assertEquals("Deals by stage", a.title());
    assertTrue(a.summaryText().contains("Cascade Patient Portal"));
    assertTrue(a.summaryText().contains("Meridian Renewal"));
    assertTrue(a.summaryText().contains("probability 45%"), "deal rows must carry probability");
    assertTrue(a.summaryText().contains("probability 75%"));
    assertFalse(a.summaryText().contains("Sobha"), "non-matching stage must be excluded");
    assertFalse(a.summaryText().contains("Demo Proposal Deal"), "demo deal must be excluded");
    assertTrue(a.summaryText().contains("Excluded 1 demo/sample deal(s)"));
    assertFalse(a.summaryText().contains("test_demo_pattern"));
  }

  @Test
  void enumerateWithoutStageSelectorKeepsAccountInventoryBehavior() {
    List<DealLedgerEntry> eligible =
        List.of(deal("Cascade Patient Portal", "Cascade Health Systems", "Proposal/Price Quote", 45, 210_000, false));

    var answer = deterministic(classification("enumerate", null, null, List.of()), eligible, List.of());
    assertTrue(answer.isPresent());
    InvestigationService.InvestigationAnswer a = answer.get();
    assertEquals("Account inventory", a.title());
    assertTrue(a.summaryText().contains("Active accounts (1)"));
    assertFalse(a.summaryText().contains("Deals in"));
  }

  // ---------- F3: entity matcher relates channel/space names to query entities ----------

  @Test
  void matchesEvidenceNameRelatesSlackChannelNameToWorkspaceQuery() {
    // The pin-escape path uses matchesEvidenceName (loose, cross-source): it strips structural
    // markers (#, all-, slack://, spaces/) and accepts substring/token overlap either direction.
    assertTrue(
        CanonicalEntityKey.matchesEvidenceName("causr workspace", "#all-causr"),
        "'causr workspace' must resolve to Slack channel #all-causr");
    assertTrue(
        CanonicalEntityKey.matchesEvidenceName("king", "#all-king-announcements"),
        "short-form entity must resolve to a prefixed channel name");
    // The strict matches() is deliberately stricter (blocks longer-query -> shorter-record)
    // to avoid blended-name CRM hallucination — a near-miss like "causr workspace" vs "causr"
    // must NOT hard-refuse via the loose path when a definitional match exists.
    assertTrue(CanonicalEntityKey.matchesEvidenceName("causr", "causr workspace"));
  }

  // ---------- P1: expected-value ranks by amount x probability, not raw amount ----------

  @Test
  void rankExtremeExpectedRevenueComputesAmountTimesProbability() {
    // EV trap: the highest-AMOUNT deal must NOT win an expected-value question.
    List<DealLedgerEntry> eligible =
        List.of(
            deal("Big Amount Deal", "Alpha Corp", "Negotiation/Review", 30, 2_000_000, false), // EV 600K
            deal("High Prob Deal", "Beta Corp", "Negotiation/Review", 90, 1_000_000, false)); // EV 900K

    var answer =
        deterministic(
            classification("rank", "Expected_Revenue", "desc", List.of()), eligible, List.of());
    assertTrue(answer.isPresent(), "rank+expected_revenue must stay deterministic");
    InvestigationService.InvestigationAnswer a = answer.get();
    assertEquals("deterministic", a.trustState());
    assertEquals(
        "High Prob Deal",
        a.recordName(),
        "EV winner is 1.0M x 90% = 900K, NOT the 2.0M x 30% deal");
    assertTrue(a.summaryText().contains("expected value"));
    assertTrue(
        a.summaryText().contains("x probability"),
        "summary must expose the amount x probability math");
    assertTrue(
        a.summaryText().contains("expected value 900000"),
        "EV must be the absolute product: 1,000,000 x 90% = 900,000 (probability is stored as percent)");
    assertFalse(a.summaryText().contains("expected value 90000000"), "must not be 100x off");
  }

  // ---------- P3: account exposure rollup sums per account, never a single-deal max ----------

  @Test
  void aggregateAccountExposureRollsUpPerAccountNotSingleDealMax() {
    // Rollup trap: the single biggest deal (400K) belongs to an account whose TOTAL (400K)
    // is smaller than Multi Co's 300K + 200K = 500K. Correct answer: Multi Co.
    List<DealLedgerEntry> eligible =
        List.of(
            deal("Multi Deal A", "Multi Co", "Negotiation/Review", 50, 300_000, false),
            deal("Multi Deal B", "Multi Co", "Proposal/Price Quote", 40, 200_000, false),
            deal("Single Big Deal", "One Account", "Negotiation/Review", 80, 400_000, false));
    List<DealLedgerEntry> demo =
        List.of(deal("Demo Mega", "Demo Corp", "Negotiation/Review", 99, 9_000_000, true));

    var answer =
        deterministic(
            classification("aggregate", "Account_Exposure", null, List.of()), eligible, demo);
    assertTrue(answer.isPresent());
    InvestigationService.InvestigationAnswer a = answer.get();
    assertEquals("deterministic", a.trustState());
    assertEquals(
        "Multi Co",
        a.recordName(),
        "largest exposure is by ACCOUNT TOTAL (500K), not the single biggest deal (400K)");
    assertTrue(a.summaryText().contains("Multi Co"));
    assertTrue(a.summaryText().contains("across 2 active deal(s)"));
    assertFalse(
        a.summaryText().contains("Single Big Deal"),
        "summary must show the winning account's deals, not the single-deal max");
    assertFalse(a.summaryText().contains("Demo Corp"));
    assertTrue(a.summaryText().contains("Excluded 1 demo/sample deal(s)"));
  }

  @Test
  void aggregateWithoutExposureFieldKeepsPipelineTotalBehavior() {
    List<DealLedgerEntry> eligible =
        List.of(deal("Wipro Annual License", "Wipro", "Negotiation/Review", 55, 1_500_000, false));

    var answer = deterministic(classification("aggregate", "Amount", null, List.of()), eligible, List.of());
    assertTrue(answer.isPresent());
    InvestigationService.InvestigationAnswer a = answer.get();
    assertEquals("Pipeline aggregate", a.title());
    assertTrue(a.summaryText().contains("Open pipeline value"));
  }

  // ---------- F4: no internal jargon in deterministic summaries ----------

  @Test
  void deterministicSummariesNeverLeakInternalFlagNames() {
    var rank =
        deterministic(
            classification("rank", "amount", "desc", List.of()),
            List.of(deal("Wipro Annual License", "Wipro", "Negotiation/Review", 55, 1_500_000, false)),
            List.of(deal("Demo Corp", "Demo Corp", "Negotiation/Review", 99, 9_000_000, true)));
    var enumAns =
        deterministic(
            classification("enumerate", "stage", null, List.of("proposal")),
            List.of(deal("Meridian Renewal", "Meridian", "Proposal/Price Quote", 75, 40_000, false)),
            List.of(deal("Demo Corp", "Demo Corp", "Proposal/Price Quote", 50, 1_000, true)));

    for (var opt : List.of(rank, enumAns)) {
      assertTrue(opt.isPresent());
      String summary = opt.get().summaryText();
      assertNotNull(summary);
      assertFalse(summary.contains("test_demo_pattern"), "summary must not expose the demo flag regex");
      assertFalse(summary.contains("non-demo deal ledger"), "summary must not expose ledger jargon");
    }
  }

  // ---------- B1: trust state must recognize an adjudicated cross-source conflict ----------

  @Test
  void adjudicatedConflictIsAnAnsweredTrustState() {
    String marker =
        "CONFLICT DETECTED: field=amount, entity=al habtoor, values=[($310000, ZOHO), ($450000, NOTION)]";
    assertEquals(
        "conflict_adjudicated",
        InvestigationService.groundedTrustState(
            marker,
            false,
            "Zoho CRM has Al Habtoor Phase 2 at $310,000 (current) while the Notion deal page still"
                + " shows the original $450,000.",
            "insufficient_data"),
        "a grounded answer that reconciled two sources is answered, not insufficient_data");
  }

  @Test
  void conflictTrustStateSurvivesWhenARecordWasAlsoSelected() {
    assertEquals(
        "conflict_adjudicated",
        InvestigationService.groundedTrustState(
            "CONFLICT DETECTED: field=amount, entity=al habtoor, values=[($310000, ZOHO), ($450000, NOTION)]",
            true,
            "Both values are cited with their source.",
            "structured"));
  }

  @Test
  void verifiedArtifactEvidenceIsGrounded() {
    assertEquals(
        "grounded",
        InvestigationService.groundedTrustState(
            null, true, "SOF-4 records the decision to defer SSO to phase two.", "insufficient_data"));
  }

  @Test
  void honestyInvariantHolds_modelAdmittingNoEvidenceStaysInsufficient() {
    assertEquals(
        "insufficient_data",
        InvestigationService.groundedTrustState(
            "CONFLICT DETECTED: field=amount, entity=x, values=[($1, ZOHO), ($2, NOTION)]",
            true,
            "The indexed evidence does not contain enough information to answer this.",
            "insufficient_data"),
        "a model that admits it lacks evidence must never be upgraded to an answered state");
    assertEquals(
        "insufficient_data",
        InvestigationService.groundedTrustState(null, false, "A perfectly fine answer.", "insufficient_data"),
        "with no conflict and no verified artifact, the fallback stands");
  }

  // ---------- B3: classifier-seam stability ----------

  @Test
  void unstableIntentsAreRecheckedAndDeterministicRoutesWin() {
    QueryClassification general = classification("general", null, null, List.of());
    QueryClassification enumerate = classification("enumerate", "Stage", null, List.of("Proposal"));

    assertTrue(InvestigationService.needsStabilityCheck(general));
    assertEquals(
        "enumerate",
        InvestigationService.reconcileClassifications(general, enumerate).intent(),
        "a run that can answer deterministically beats a run that would free-form the same question");
    assertEquals(
        "enumerate",
        InvestigationService.reconcileClassifications(enumerate, general).intent(),
        "reconciliation must not depend on which run came first");
  }

  @Test
  void reasonVersusGeneralFlakePrefersTheStructuredCrmRoute() {
    QueryClassification general = classification("general", null, null, List.of());
    QueryClassification reason = classification("reason", null, null, List.of());
    assertEquals(
        "reason",
        InvestigationService.reconcileClassifications(general, reason).intent(),
        "F1 root cause: the ledger-backed route must win the general/reason coin flip");
  }

  @Test
  void stableStructuredRoutesAreNotRechecked() {
    assertFalse(
        InvestigationService.needsStabilityCheck(classification("enumerate", "Stage", null, List.of("Proposal"))));
    assertFalse(
        InvestigationService.needsStabilityCheck(classification("aggregate", "Account_Exposure", null, List.of())));
    assertTrue(
        InvestigationService.needsStabilityCheck(classification("rank", null, null, List.of())),
        "a confident rank with no field cannot execute — worth a second read");
  }

  @Test
  void agreeingRunsKeepTheOneCarryingExecutableDetail() {
    QueryClassification bare = classification("enumerate", null, null, List.of());
    QueryClassification detailed = classification("enumerate", "Stage", null, List.of("Proposal"));
    assertEquals(
        List.of("Proposal"),
        InvestigationService.reconcileClassifications(bare, detailed).fieldValues());
  }

  // ---------- B2: named artifacts lead the evidence list ----------

  @Test
  void verifiedArtifactEvidenceIsMergedAheadOfRankedRetrieval() {
    var pinned =
        new InvestigationService.InvestigationEvidence(
            "LINEAR", "SOF-4 Onboarding rework", 0.4f, Map.of(), List.of(), List.of(), List.of(),
            "decision: defer SSO", "chunk-sof4", "linear/SOF-4");
    var noise =
        new InvestigationService.InvestigationEvidence(
            "GOOGLE_CHAT", "onboarding banter", 0.9f, Map.of(), List.of(), List.of(), List.of(),
            "who is onboarding today", "chunk-chat", "chat/1");
    var duplicate =
        new InvestigationService.InvestigationEvidence(
            "LINEAR", "SOF-4 Onboarding rework", 0.1f, Map.of(), List.of(), List.of(), List.of(),
            "decision: defer SSO", "chunk-sof4", "linear/SOF-4");

    var merged = InvestigationService.mergeArtifactPins(List.of(pinned), List.of(noise, duplicate));
    assertEquals(2, merged.size(), "the duplicate chunk must not appear twice");
    assertEquals("SOF-4 Onboarding rework", merged.getFirst().name());
  }

  @Test
  void entityPinIsSkippedWhenTheClassifiedEntityIsTheArtifactItself() {
    var reference =
        new ArtifactReference(ArtifactReference.Kind.TICKET, "SOF-4", "SOF-4");
    var pins =
        new InvestigationService.ArtifactPinOutcome(
            List.of(reference), List.of(), List.of("SOF-4"), List.of());
    QueryClassification sofAsEntity =
        new QueryClassification(true, "lookup", null, null, 0.9, null, "SOF-4", List.of());
    QueryClassification realDeal =
        new QueryClassification(true, "lookup", null, null, 0.9, null, "Al Habtoor Phase 2", List.of());

    assertTrue(
        InvestigationService.entityIsPinnedArtifact(sofAsEntity, pins),
        "a ticket id read as a CRM entity must not trigger the CRM not-found refusal");
    assertFalse(
        InvestigationService.entityIsPinnedArtifact(realDeal, pins),
        "a genuine deal name must still take the CRM entity-pin path");
  }

  @Test
  void unresolvedArtifactReferencesAreNotTreatedAsFound() {
    var pins =
        new InvestigationService.ArtifactPinOutcome(
            List.of(new ArtifactReference(ArtifactReference.Kind.TICKET, "SOF-9", "SOF-9")),
            List.of(),
            List.of(),
            List.of("SOF-9"));
    QueryClassification asEntity =
        new QueryClassification(true, "lookup", null, null, 0.9, null, "SOF-9", List.of());
    assertFalse(
        InvestigationService.entityIsPinnedArtifact(asEntity, pins),
        "nothing was verified, so the honest not-found path must still run");
  }

  // ---------- B1b: a grounded cross-source answer with no single CRM record ----------

  private static InvestigationService.InvestigationEvidence row(String source, String name) {
    return new InvestigationService.InvestigationEvidence(
        source, name, 0.5f, Map.of(), List.of(), List.of(), List.of(), "", "chunk-" + name, "p");
  }

  @Test
  void evidenceBackedAnswerWithNoSelectedRecordIsGroundedNotInsufficient() {
    List<InvestigationService.InvestigationEvidence> evidence =
        List.of(row("ZOHO", "Bright Horizon - Enterprise License"), row("SLACK", "all-causr"));
    String summary =
        "CRM has Bright Horizon - Enterprise License in Negotiation at 90%; Slack says Fatima went"
            + " quiet and the deal is stalled. Both are cited because they disagree.";

    assertTrue(InvestigationService.answerCitesEvidence(summary, evidence));
    assertEquals(
        "grounded",
        InvestigationService.groundedTrustState(
            null, false, true, summary, "insufficient_data"),
        "an answer citing two real sources is answered even when no single CRM record is THE record");
  }

  @Test
  void worldKnowledgeAnswerOverUnrelatedEvidenceIsNotGrounded() {
    List<InvestigationService.InvestigationEvidence> evidence = List.of(row("ZOHO", "King ERP"));
    assertFalse(
        InvestigationService.answerCitesEvidence(
            "Infosys Limited and INFY Technologies are the same company based on the INFY ticker.",
            evidence),
        "an answer built from world knowledge must not be dressed up as grounded");
    assertEquals(
        "insufficient_data",
        InvestigationService.groundedTrustState(null, false, false, "Infosys and INFY are the same.", "insufficient_data"));
  }

  @Test
  void withoutEvidenceTheAnswerStaysInsufficient() {
    assertEquals(
        "insufficient_data",
        InvestigationService.groundedTrustState(null, false, false, "Some narrative.", "insufficient_data"));
    assertEquals(
        "insufficient_data",
        InvestigationService.groundedTrustState(null, false, true, "   ", "insufficient_data"),
        "a blank summary is not an answer");
  }

  // ---------- demo copy: never show the user a JSON envelope ----------

  @Test
  void truncatedJsonEnvelopeIsSalvagedIntoProse() {
    String truncated =
        "{\n  \"selectedRecord\": null,\n  \"summary\": \"Zoho shows 85,000 while Slack says the deal"
            + " stalled and Fatima Al";
    String salvaged = InvestigationService.salvageNarrative(truncated);
    assertTrue(salvaged.startsWith("Zoho shows 85,000"), salvaged);
    assertFalse(salvaged.contains("selectedRecord"), "JSON keys must never reach the user");
    assertFalse(salvaged.contains("{"), "no JSON scaffolding in user copy");
  }

  @Test
  void wellFormedJsonEnvelopeAlsoSalvagesTheSummaryOnly() {
    String fenced =
        "```json\n{\"selectedRecord\": \"King ERP\", \"summary\": \"King ERP is at 60%.\","
            + " \"reasons\": []}\n```";
    assertEquals("King ERP is at 60%.", InvestigationService.salvageNarrative(fenced));
  }

  @Test
  void plainProseIsReturnedUnchangedAndUnsalvageableJsonSaysSoPlainly() {
    assertEquals("A plain answer.", InvestigationService.salvageNarrative("A plain answer."));
    String noSummary = InvestigationService.salvageNarrative("{\"reasons\": [\"x\"]}");
    assertFalse(noSummary.contains("reasons"), noSummary);
    assertTrue(noSummary.toLowerCase().contains("ask again"), noSummary);
  }

  @Test
  void unsupportedSaaSMetricsAreDetectedAndRefusedWithoutPipelineTotal() {
    assertTrue(
        InvestigationService.asksUnsupportedIndexedMetric(
            "What is our Q3 churn rate and ARR?"));
    assertTrue(InvestigationService.asksUnsupportedIndexedMetric("what is our MRR?"));
    assertTrue(InvestigationService.asksUnsupportedIndexedMetric("net retention this quarter"));
    assertFalse(
        InvestigationService.asksUnsupportedIndexedMetric(
            "What is the largest deal amount in the pipeline and which account owns it?"));
    assertFalse(
        InvestigationService.asksUnsupportedIndexedMetric(
            "Which account has the largest total exposure across all its deals?"));

    var answer =
        InvestigationService.answerUnsupportedIndexedMetric(
            "What is our Q3 churn rate and ARR?");
    assertEquals("insufficient_data", answer.trustState());
    assertFalse(answer.summaryText().toLowerCase().contains("open pipeline"));
    assertTrue(answer.summaryText().toLowerCase().contains("arr"));
  }

}
