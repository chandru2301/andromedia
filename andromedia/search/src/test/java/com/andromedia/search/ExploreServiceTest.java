package com.andromedia.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.andromedia.common.ChunkUnitType;
import com.andromedia.common.IndexConstants;
import java.util.List;
import java.util.Optional;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExploreServiceTest {

  @Mock private SearchService searchService;
  @Mock private UsagesLookupService usagesLookupService;
  @Mock private ImpactAnalysisService impactAnalysisService;
  @Mock private GraphContextEnricher graphContextEnricher;

  @Test
  void includesGitCommitContentInSummaryText() {
    SearchHit gitHit =
        new SearchHit(
            "git/deadbeef",
            "deadbeef",
            0.9f,
            "git-deadbeef",
            null,
            null,
            ChunkUnitType.HISTORY,
            0,
            0,
            null,
            null,
            List.of(),
            List.of(),
            List.of(),
            Optional.of(IndexConstants.SOURCE_TYPE_GIT),
            Optional.of("deadbeef1234567890"),
            Optional.of("2024-03-01T12:00:00Z"),
            Optional.of(
                "[GIT_COMMIT] deadbeef · 2024-03-01T12:00:00Z · Alice\nimplemented impact and plan options\n"));

    when(searchService.search(any()))
        .thenReturn(new SearchResult(List.of(gitHit), 1, Duration.ZERO));
    when(graphContextEnricher.enrich(any(), any(Integer.class), any(Integer.class)))
        .thenReturn(Optional.empty());

    ExploreService service =
        new ExploreService(
            searchService, usagesLookupService, impactAnalysisService, graphContextEnricher);

    ExploreService.ExploreResult result = service.explore("why was impact implemented", null);

    assertTrue(result.summaryText().contains("[GIT]"));
    assertTrue(result.summaryText().contains("[GIT_COMMIT]"));
    assertTrue(result.summaryText().contains("implemented impact and plan options"));
    assertTrue(result.summaryText().contains("commit=deadbeef1234"));
  }

  @Test
  void includesSlackThreadContentInSummaryText() {
    SearchHit slackHit =
        new SearchHit(
            "slack://#backend-eng/1718366400.000100",
            "backend-eng",
            0.95f,
            "slack-thread-1",
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
            Optional.of("2024-06-14T12:00:00Z"),
            Optional.of(
                "[SLACK] #backend-eng · 2024-06-14 · chandru, ravi, priya\n"
                    + "we decided to switch to async Kafka publish\n"));

    when(searchService.search(any()))
        .thenReturn(new SearchResult(List.of(slackHit), 1, Duration.ZERO));
    when(graphContextEnricher.enrich(any(), any(Integer.class), any(Integer.class)))
        .thenReturn(Optional.empty());

    ExploreService service =
        new ExploreService(
            searchService, usagesLookupService, impactAnalysisService, graphContextEnricher);

    ExploreService.ExploreResult result =
        service.explore("what did the team decide about payment timeouts", null);

    assertTrue(result.summaryText().contains("[SLACK]"));
    assertTrue(result.summaryText().contains("we decided to switch to async Kafka"));
    assertTrue(result.summaryText().contains("#backend-eng"));
  }

  @Test
  void includesNotionPageContentInSummaryText() {
    SearchHit notionHit =
        new SearchHit(
            "notion://page-1/why-jwt",
            "Auth Architecture",
            0.95f,
            "notion-1",
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
            Optional.of("2026-05-18T12:00:00Z"),
            Optional.of(
                "[NOTION] Auth Architecture · updated 2026-05-18 · chandru\n"
                    + "section: Why JWT\n\nSession store OOM\n"));

    when(searchService.search(any()))
        .thenReturn(new SearchResult(List.of(notionHit), 1, Duration.ZERO));
    when(graphContextEnricher.enrich(any(), any(Integer.class), any(Integer.class)))
        .thenReturn(Optional.empty());

    ExploreService service =
        new ExploreService(
            searchService, usagesLookupService, impactAnalysisService, graphContextEnricher);

    ExploreService.ExploreResult result =
        service.explore("how does authentication architecture work", null);

    assertTrue(result.summaryText().contains("[NOTION]"));
    assertTrue(result.summaryText().contains("Auth Architecture"));
    assertTrue(result.summaryText().contains("Session store OOM"));
  }

  @Test
  void includesZohoStructuredFlagsAndNotesInSummaryText() {
    ZohoHitMetadata zoho =
        new ZohoHitMetadata(
            Optional.of("Negotiation"),
            Optional.of("850000"),
            Optional.empty(),
            Optional.empty(),
            Optional.of("2026-03-15"),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of("King"),
            Optional.empty(),
            Optional.of("0.72"),
            List.of("stale_record", "close_date_passed", "missing_account_description"),
            List.of("Rajesh Kumar"),
            List.of("king can be have expected revenue upto 90k"));
    SearchHit zohoHit =
        new SearchHit(
            "zoho://deal/erp-1",
            "ERP Implementation",
            0.91f,
            "zoho-erp-1",
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
            Optional.of("2026-07-16T10:00:00Z"),
            Optional.of(
                "[ZOHO] Deal · ERP Implementation · Negotiation · King · updated 2026-07-16\n"
                    + "\nNotes:\n- Note: king can be have expected revenue upto 90k\n"),
            Optional.of(zoho));

    when(searchService.search(any()))
        .thenReturn(new SearchResult(List.of(zohoHit), 1, Duration.ZERO));
    when(graphContextEnricher.enrich(any(), any(Integer.class), any(Integer.class)))
        .thenReturn(Optional.empty());

    ExploreService service =
        new ExploreService(
            searchService, usagesLookupService, impactAnalysisService, graphContextEnricher);

    ExploreService.ExploreResult result =
        service.explore("What's the deal size for the ERP Implementation at King?", null);

    assertTrue(result.summaryText().contains("[ZOHO]"));
    assertTrue(result.summaryText().contains("Structured: Amount=850000"));
    assertTrue(result.summaryText().contains("Expected_Revenue=—"));
    assertTrue(result.summaryText().contains("Flags:"));
    assertTrue(result.summaryText().contains("stale_record"));
    assertTrue(result.summaryText().contains("close_date_passed"));
    assertTrue(result.summaryText().contains("Contacts: Rajesh Kumar"));
    assertTrue(result.summaryText().contains("unverified free-text"));
    assertTrue(result.summaryText().contains("Narrative:"));
  }

  @Test
  void usesHigherSearchLimitForDecisionIntent() {
    when(searchService.search(any()))
        .thenReturn(new SearchResult(List.of(), 0, Duration.ZERO));
    when(graphContextEnricher.enrich(any(), any(Integer.class), any(Integer.class)))
        .thenReturn(Optional.empty());

    ExploreService service =
        new ExploreService(
            searchService, usagesLookupService, impactAnalysisService, graphContextEnricher);

    service.explore("what did the team decide about Kafka", null);

    ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
    verify(searchService).search(captor.capture());
    assertEquals(10, captor.getValue().maxResults());
  }
}
