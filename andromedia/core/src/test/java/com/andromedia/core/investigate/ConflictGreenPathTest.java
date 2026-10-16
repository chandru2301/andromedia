package com.andromedia.core.investigate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.andromedia.common.IndexConstants;
import com.andromedia.core.investigate.InvestigationService.InvestigationAnswer;
import com.andromedia.core.investigate.InvestigationService.InvestigationEvidence;
import com.andromedia.core.investigate.InvestigationService.LedgerSplit;
import com.andromedia.core.investigate.InvestigationService.PipelineHealthFacts;
import com.andromedia.search.CanonicalEntityKey;
import com.andromedia.search.ZohoQualificationService.DealLedgerEntry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Red/green fixtures for the cross-source conflict green path (C1–C4 contract at unit level).
 * Live API assertions live in {@code scripts/probe-conflict.sh} against the frozen corpus.
 */
class ConflictGreenPathTest {

  private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

  private static DealLedgerEntry deal(
      String name, String account, String stage, double probability, double amount) {
    return new DealLedgerEntry(
        name,
        account,
        Map.of(
            "Probability", ((long) probability) + "%",
            "Stage", stage,
            "Amount", String.valueOf((long) amount),
            "Account", account,
            "Close Date", "2026-08-31"),
        List.of(),
        false,
        probability,
        amount,
        NOW);
  }

  @Test
  void c1ResolvePinsBrightHorizonDespiteFatimaAndRiskTokens() {
    List<DealLedgerEntry> ledger =
        List.of(
            deal(
                "Bright Horizon - Enterprise License",
                "Bright Horizon Realty",
                "Negotiation/Review",
                90,
                85_000),
            deal("King ERP", "King", "Qualification", 40, 10_000));

    assertTrue(
        InvestigationService.resolveNamedEntity("Bright Horizon Fatima stalled", ledger)
            .isPresent());
    assertEquals(
        "Bright Horizon - Enterprise License",
        InvestigationService.resolveNamedEntity(
                "What's going on with Bright Horizon and Fatima?", ledger)
            .orElseThrow()
            .name());
    assertTrue(CanonicalEntityKey.queryMentionsRecord(
        "Bright Horizon Fatima stalled", "Bright Horizon - Enterprise License"));
    assertFalse(
        CanonicalEntityKey.queryMentionsRecord("King Chapman Mega Deal", "King"));
  }

  @Test
  void c1StatusConflictMarkerForSlackStalledVsZohoNegotiation() {
    InvestigationEvidence zoho =
        new InvestigationEvidence(
            IndexConstants.SOURCE_TYPE_ZOHO,
            "Bright Horizon - Enterprise License",
            1f,
            Map.of("Stage", "Negotiation/Review", "Probability", "90%", "Amount", "85000"),
            List.of(),
            List.of(),
            List.of(),
            "CRM row");
    InvestigationEvidence slack =
        new InvestigationEvidence(
            IndexConstants.SOURCE_TYPE_SLACK,
            "#all-causr",
            0.9f,
            Map.of(),
            List.of(),
            List.of(),
            List.of(),
            "Bright Horizon deal is actually stalled, Fatima went quiet");
    String marker =
        InvestigationService.synthesisConflictMarker(
            List.of(zoho, slack), Map.of(), "bright horizon");
    assertNotNull(marker);
    assertTrue(marker.contains("field=status"));
    assertTrue(marker.toLowerCase().contains("stalled"));
    assertTrue(marker.contains("ZOHO"));
    assertTrue(marker.contains("SLACK"));
  }

  @Test
  void c3AmountConflictStillPreferredOverStatus() {
    InvestigationEvidence zoho =
        new InvestigationEvidence(
            IndexConstants.SOURCE_TYPE_ZOHO,
            "Al Habtoor – Phase 2 Expansion",
            1f,
            Map.of("Amount", "310000", "Stage", "Negotiation/Review"),
            List.of(),
            List.of(),
            List.of(),
            "CRM");
    InvestigationEvidence notion =
        new InvestigationEvidence(
            IndexConstants.SOURCE_TYPE_NOTION,
            "Al Habtoor – Phase 2 Expansion",
            0.9f,
            Map.of(),
            List.of(),
            List.of(),
            List.of(),
            "Draft proposal: $450,000 — also stalled");
    String marker =
        InvestigationService.synthesisConflictMarker(
            List.of(zoho, notion), Map.of(), "al habtoor");
    assertNotNull(marker);
    assertTrue(marker.contains("field=amount"), marker);
  }

  @Test
  void phase22PostCheckReplacesAnswerThatOmitsConflictSides() {
    InvestigationEvidence zoho =
        new InvestigationEvidence(
            IndexConstants.SOURCE_TYPE_ZOHO,
            "Bright Horizon - Enterprise License",
            1f,
            Map.of("Stage", "Negotiation/Review", "Probability", "90%"),
            List.of(),
            List.of(),
            List.of(),
            "CRM");
    InvestigationEvidence slack =
        new InvestigationEvidence(
            IndexConstants.SOURCE_TYPE_SLACK,
            "#all-causr",
            0.9f,
            Map.of(),
            List.of(),
            List.of(),
            List.of(),
            "deal is actually stalled, Fatima went quiet");
    String marker =
        InvestigationService.synthesisConflictMarker(
            List.of(zoho, slack), Map.of(), "bright horizon");
    assertNotNull(marker);
    assertFalse(
        InvestigationService.answerCoversConflictSides(
            "Everything looks fine with the pipeline.", marker, List.of(zoho, slack)));
    String enforced =
        InvestigationService.enforceConflictPostCheck(
            "Everything looks fine with the pipeline.", marker, List.of(zoho, slack));
    assertTrue(enforced.toLowerCase().contains("conflict"));
    assertTrue(enforced.contains("SLACK") || enforced.toLowerCase().contains("stalled"));
  }

  @Test
  void c4GracefulLedgerFallbackListsEligibleDeals() {
    DealLedgerEntry bright =
        deal(
            "Bright Horizon - Enterprise License",
            "Bright Horizon Realty",
            "Negotiation/Review",
            90,
            85_000);
    DealLedgerEntry king = deal("King ERP", "King", "Qualification", 40, 10_000);
    LedgerSplit split = new LedgerSplit(List.of(bright, king), List.of());
    PipelineHealthFacts health =
        new PipelineHealthFacts(
            2, 0, 1, 90.0, false, false, Map.of(), 0, 0, 0, 0, 95_000.0, 2);
    InvestigationAnswer answer =
        InvestigationService.gracefulLedgerFallback(
            split, health, "Could not ground an answer against the eligible deals.");
    assertNotNull(answer.summaryText());
    assertFalse(answer.summaryText().trim().equalsIgnoreCase(
        "Could not ground an answer against the eligible deals. Please rephrase the question."));
    assertTrue(answer.summaryText().contains("Bright Horizon"));
    assertTrue(answer.summaryText().contains("Ask about one of these"));
    assertEquals("structured", answer.trustState());
  }

  @Test
  void statusConflictIgnoredWhenCrmAlreadyClosed() {
    InvestigationEvidence zoho =
        new InvestigationEvidence(
            IndexConstants.SOURCE_TYPE_ZOHO,
            "Old Deal",
            1f,
            Map.of("Stage", "Closed Lost", "Probability", "0%"),
            List.of(),
            List.of(),
            List.of(),
            "CRM");
    InvestigationEvidence slack =
        new InvestigationEvidence(
            IndexConstants.SOURCE_TYPE_SLACK,
            "#sales",
            0.5f,
            Map.of(),
            List.of(),
            List.of(),
            List.of(),
            "this one stalled last year");
    assertNull(
        InvestigationService.statusConflictMarker(List.of(zoho, slack), "old deal"));
  }
}
