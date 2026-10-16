package com.andromedia.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.andromedia.common.IndexConstants;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZohoQualificationServiceTest {

  private final ZohoQualificationProperties properties = new ZohoQualificationProperties();
  private final ZohoQualificationService service = new ZohoQualificationService(properties);
  private final Instant now = Instant.parse("2026-07-17T12:00:00Z");

  @TempDir Path tempDir;

  @Test
  void ranksIdDecisionMakersAfterNeedsAnalysis() {
    Optional<ZohoQualificationService.ScoredDeal> earlier =
        service.scoreDocument(
            deal("Early", "Needs Analysis", "40", "2026-12-01", "2026-07-10T00:00:00Z"), now);
    Optional<ZohoQualificationService.ScoredDeal> later =
        service.scoreDocument(
            deal("Later", "Id. Decision Makers", "40", "2026-12-01", "2026-07-10T00:00:00Z"), now);

    assertTrue(earlier.isPresent());
    assertTrue(later.isPresent());
    assertTrue(later.get().totalScore() > earlier.get().totalScore());
    assertEquals(1, earlier.get().stageProgress().index());
    assertEquals(3, later.get().stageProgress().index());
  }

  @Test
  void weightsProbabilityStageAndFreshness() {
    Optional<ZohoQualificationService.ScoredDeal> scored =
        service.scoreDocument(
            deal("Weighted", "Negotiation/Review", "80", "2026-12-01", "2026-07-16T00:00:00Z"),
            now);

    assertTrue(scored.isPresent());
    assertEquals(0.40, scored.get().breakdown().get("probability"), 0.0001);
    assertEquals(0.30, scored.get().breakdown().get("stage"), 0.0001);
    assertTrue(scored.get().breakdown().get("freshness") > 0.15);
    assertEquals(0.0, scored.get().breakdown().get("stalePenalty"), 0.0001);
    assertEquals(0.0, scored.get().breakdown().get("overduePenalty"), 0.0001);
  }

  @Test
  void excludesClosedDeals() {
    Optional<ZohoQualificationService.ScoredDeal> closedWon =
        service.scoreDocument(
            deal("Won", "Closed Won", "100", "2026-01-01", "2026-07-01T00:00:00Z"), now);
    Optional<ZohoQualificationService.ScoredDeal> closedLost =
        service.scoreDocument(
            deal("Lost", "Closed Lost", "0", "2026-01-01", "2026-07-01T00:00:00Z"), now);

    assertTrue(closedWon.isEmpty());
    assertTrue(closedLost.isEmpty());
  }

  @Test
  void appliesStaleAndOverduePenalties() {
    Optional<ZohoQualificationService.ScoredDeal> scored =
        service.scoreDocument(
            deal("Aging", "Proposal/Price Quote", "70", "2025-01-01", "2025-01-01T00:00:00Z"),
            now);

    assertTrue(scored.isPresent());
    assertTrue(scored.get().stale());
    assertTrue(scored.get().overdue());
    assertEquals(0.15, scored.get().breakdown().get("stalePenalty"), 0.0001);
    assertEquals(0.10, scored.get().breakdown().get("overduePenalty"), 0.0001);
  }

  @Test
  void unknownStagesReceiveNoStageContribution() {
    Optional<ZohoQualificationService.ScoredDeal> scored =
        service.scoreDocument(
            deal("Custom", "Discovery Call", "60", "2026-12-01", "2026-07-10T00:00:00Z"), now);

    assertTrue(scored.isPresent());
    assertTrue(scored.get().unknownStage());
    assertEquals(0.0, scored.get().breakdown().get("stage"), 0.0001);
    assertTrue(scored.get().flags().contains("unknown_stage"));
  }

  @Test
  void prefersNonDemoOverHigherScoringDemo() throws IOException {
    Path indexPath = tempDir.resolve("index");
    writeIndex(
        indexPath,
        deal(
            "Demo Mega",
            "Negotiation/Review",
            "95",
            "2026-12-01",
            "2026-07-16T00:00:00Z",
            "test_demo_pattern"),
        deal("Real Deal", "Needs Analysis", "55", "2026-12-01", "2026-07-10T00:00:00Z"));

    ZohoQualificationService.QualificationResult result =
        service.qualifyActiveDeals(indexPath, now);

    assertTrue(result.success());
    assertEquals("structured", result.trustState());
    assertEquals("Real Deal", result.recordName());
  }

  @Test
  void demoOnlyDatasetReportsDemoTrustState() throws IOException {
    Path indexPath = tempDir.resolve("demo-index");
    writeIndex(
        indexPath,
        deal(
            "Demo Alpha",
            "Qualification",
            "40",
            "2026-12-01",
            "2026-07-01T00:00:00Z",
            "test_demo_pattern"),
        deal(
            "Demo Beta",
            "Negotiation/Review",
            "90",
            "2026-12-01",
            "2026-07-16T00:00:00Z",
            "test_demo_pattern"));

    ZohoQualificationService.QualificationResult result =
        service.qualifyActiveDeals(indexPath, now);

    assertTrue(result.success());
    assertEquals("demo_only", result.trustState());
    assertEquals("Demo Beta", result.recordName());
    assertTrue(
        result.reasons().stream()
            .anyMatch(reason -> reason.toLowerCase().contains("demo/test")));
  }

  @Test
  void qualifyActiveDealsExcludesClosedAndReturnsReasons() throws IOException {
    Path indexPath = tempDir.resolve("active-index");
    writeIndex(
        indexPath,
        deal("Closed", "Closed Won", "100", "2026-01-01", "2026-07-16T00:00:00Z"),
        deal("Winner", "Id. Decision Makers", "85", "2026-11-01", "2026-07-15T00:00:00Z"),
        deal("Runner", "Needs Analysis", "50", "2026-11-01", "2026-07-01T00:00:00Z"));

    ZohoQualificationService.QualificationResult result =
        service.qualifyActiveDeals(indexPath, now);

    assertTrue(result.success());
    assertEquals("Winner", result.recordName());
    assertFalse(result.reasons().isEmpty());
    assertTrue(result.primaryFields().containsKey("Probability"));
    assertTrue(result.primaryFields().containsKey("Stage"));
    assertEquals(ZohoQualificationProperties.MODEL_ID, result.modelId());
  }

  @Test
  void selectsHighestProbabilityAcrossAllActiveDeals() throws IOException {
    Path indexPath = tempDir.resolve("probability-index");
    writeIndex(
        indexPath,
        deal("Commercial Press", "Closed Lost", "100", "2026-08-01", "2026-07-16T00:00:00Z"),
        deal(
            "Chapman",
            "Negotiation/Review",
            "90",
            "2026-08-31",
            "2026-07-16T00:00:00Z",
            "test_demo_pattern"),
        deal("King", "Id. Decision Makers", "65", "2026-09-30", "2026-07-15T00:00:00Z"));

    ZohoQualificationService.QualificationResult result =
        service.selectHighestProbabilityDeal(indexPath, now);

    assertTrue(result.success());
    assertEquals("Chapman", result.recordName());
    assertEquals("90%", result.primaryFields().get("Probability"));
    assertEquals("Negotiation/Review", result.primaryFields().get("Stage"));
    assertEquals("demo_only", result.trustState());
    assertEquals(ZohoQualificationService.HIGHEST_PROBABILITY_MODEL_ID, result.modelId());
    assertEquals(
        "Highest probability among all active deals (90%).", result.reasons().getFirst());
  }

  @Test
  void listActiveDealLedgerExcludesClosedAndIncludesStructuredFields() throws IOException {
    Path indexPath = tempDir.resolve("ledger-index");
    writeIndex(
        indexPath,
        deal("Closed", "Closed Won", "100", "2026-01-01", "2026-07-16T00:00:00Z"),
        deal("Chapman", "Negotiation/Review", "90", "2026-08-31", "2026-07-16T00:00:00Z"),
        deal("King", "Needs Analysis", "65", "2026-09-30", "2026-07-15T00:00:00Z"));

    List<ZohoQualificationService.DealLedgerEntry> ledger =
        service.listActiveDealLedger(indexPath, now);

    assertEquals(2, ledger.size());
    assertTrue(ledger.stream().noneMatch(entry -> entry.name().equals("Closed")));
    assertTrue(
        ledger.stream()
            .anyMatch(
                entry ->
                    entry.name().equals("Chapman")
                        && entry.probability() == 90.0
                        && entry.primaryFields().containsKey("Probability")));
    assertTrue(
        ledger.get(0).qualificationScore() >= ledger.get(1).qualificationScore(),
        "ledger should be pre-sorted by qualification score descending");
  }

  @Test
  void rankActiveDealsByAmountUsesFullLedger() throws IOException {
    Path indexPath = tempDir.resolve("amount-index");
    Document small = deal("Small", "Qualification", "40", "2026-12-01", "2026-07-10T00:00:00Z");
    small.removeFields(IndexConstants.FIELD_DEAL_AMOUNT);
    small.add(new StringField(IndexConstants.FIELD_DEAL_AMOUNT, "25000", Field.Store.YES));
    Document large = deal("Large", "Needs Analysis", "30", "2026-12-01", "2026-07-10T00:00:00Z");
    large.removeFields(IndexConstants.FIELD_DEAL_AMOUNT);
    large.add(new StringField(IndexConstants.FIELD_DEAL_AMOUNT, "250000", Field.Store.YES));
    writeIndex(indexPath, small, large);

    ZohoQualificationService.QualificationResult result =
        service.rankActiveDeals(indexPath, now, "Amount", "desc");

    assertTrue(result.success());
    assertEquals("Large", result.recordName());
  }

  @Test
  void highestProbabilityUsesStableNameTieBreak() throws IOException {
    Path indexPath = tempDir.resolve("probability-tie-index");
    writeIndex(
        indexPath,
        deal("Alpha", "Qualification", "90", "2026-08-01", "2026-07-16T00:00:00Z"),
        deal("Chapman", "Negotiation/Review", "90", "2026-08-31", "2026-07-16T00:00:00Z"));

    ZohoQualificationService.QualificationResult result =
        service.selectHighestProbabilityDeal(indexPath, now);

    assertTrue(result.success());
    assertEquals("Chapman", result.recordName());
  }

  @Test
  void highestProbabilityRejectsDatasetWithoutStructuredProbability() throws IOException {
    Path indexPath = tempDir.resolve("missing-probability-index");
    writeIndex(
        indexPath,
        deal("Unknown", "Qualification", "", "2026-08-01", "2026-07-16T00:00:00Z"));

    ZohoQualificationService.QualificationResult result =
        service.selectHighestProbabilityDeal(indexPath, now);

    assertFalse(result.success());
    assertEquals("insufficient_data", result.trustState());
    assertTrue(result.message().contains("structured probability"));
  }

  @Test
  void highestProbabilityReportsDemoOnlyTrust() throws IOException {
    Path indexPath = tempDir.resolve("probability-demo-index");
    writeIndex(
        indexPath,
        deal(
            "Chapman",
            "Negotiation/Review",
            "90",
            "2026-08-31",
            "2026-07-16T00:00:00Z",
            "test_demo_pattern"));

    ZohoQualificationService.QualificationResult result =
        service.selectHighestProbabilityDeal(indexPath, now);

    assertTrue(result.success());
    assertEquals("Chapman", result.recordName());
    assertEquals("demo_only", result.trustState());
    assertTrue(result.reasons().stream().anyMatch(reason -> reason.contains("demo/test")));
  }

  private static Document deal(
      String name, String stage, String probability, String closeDate, String modified) {
    return deal(name, stage, probability, closeDate, modified, null);
  }

  private static Document deal(
      String name,
      String stage,
      String probability,
      String closeDate,
      String modified,
      String demoFlag) {
    Document document = new Document();
    document.add(
        new StringField(
            IndexConstants.FIELD_SOURCE_TYPE, IndexConstants.SOURCE_TYPE_ZOHO, Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_DEAL_NAME, name, Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_FILE_NAME, name, Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_DEAL_STAGE, stage, Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_PROBABILITY, probability, Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_CLOSING_DATE, closeDate, Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_TIMESTAMP, modified, Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_DEAL_AMOUNT, "50000", Field.Store.YES));
    document.add(new StringField(IndexConstants.FIELD_ACCOUNT_NAME, "Acme", Field.Store.YES));
    if (demoFlag != null) {
      document.add(
          new StringField(IndexConstants.FIELD_QUALITY_SIGNALS, demoFlag, Field.Store.YES));
    }
    return document;
  }

  private static void writeIndex(Path indexPath, Document... documents) throws IOException {
    try (FSDirectory directory = FSDirectory.open(indexPath);
        IndexWriter writer =
            new IndexWriter(directory, new IndexWriterConfig(new StandardAnalyzer()))) {
      for (Document document : documents) {
        writer.addDocument(document);
      }
      writer.commit();
    }
  }
}
