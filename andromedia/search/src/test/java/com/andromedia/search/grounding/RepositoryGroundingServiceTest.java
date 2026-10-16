package com.andromedia.search.grounding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.andromedia.common.ChunkUnitType;
import com.andromedia.search.SearchDiagnostics;
import com.andromedia.search.SearchHit;
import com.andromedia.search.SearchResult;
import com.andromedia.search.UsageLookupRequest;
import com.andromedia.search.UsageLookupResult;
import com.andromedia.search.UsageRole;
import com.andromedia.search.UsageSite;
import com.andromedia.search.UsagesLookupService;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RepositoryGroundingServiceTest {

  @Mock private UsagesLookupService usagesLookupService;

  @Test
  void buildsBundleFromSearchHitsAndConfigPaths() {
    SearchResult searchResult =
        new SearchResult(
            List.of(
                new SearchHit(
                    "/workspace/src/AnomalyKafkaListener.java",
                    "AnomalyKafkaListener.java",
                    1.0f,
                    "c1",
                    "AnomalyKafkaListener",
                    "consumeAnomaly",
                    null,
                    10,
                    20,
                    null,
                    null,
                    null,
                    List.of("java:com.foo.AnomalyKafkaListener#consumeAnomaly"),
                    List.of("consumeAnomaly")),
                new SearchHit(
                    "/workspace/src/main/resources/application.yml",
                    "application.yml",
                    0.5f,
                    "c2",
                    null,
                    null,
                    null,
                    1,
                    1)),
            2,
            Duration.ofMillis(5),
            new SearchDiagnostics("q", "/workspace", "/index"));

    RepositoryGroundingService service = RepositoryGroundingService.forTest(usagesLookupService, "");
    PlanGroundingContext context =
        new PlanGroundingContext("add retry", null, searchResult, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

    GroundingBundle bundle = service.build(context);

    assertTrue(bundle.verifiedFileNames().contains("AnomalyKafkaListener.java"));
    assertTrue(bundle.verifiedConfigPaths().contains("/workspace/src/main/resources/application.yml"));
    assertTrue(bundle.verifiedSymbolNames().contains("AnomalyKafkaListener#consumeAnomaly"));
    assertEquals(2, bundle.fileCount());
  }

  @Test
  void includesUsageFilePathsWhenSymbolQueryResolves() {
    SearchResult searchResult = new SearchResult(List.of(), 0, Duration.ZERO);
    UsageSite definition =
        new UsageSite(
            "java:com.andromedia.cli.progress.CliPlanProgressAdapter",
            "CliPlanProgressAdapter",
            "/workspace/cli/src/main/java/com/andromedia/cli/progress/CliPlanProgressAdapter.java",
            "CliPlanProgressAdapter.java",
            10,
            50,
            ChunkUnitType.CLASS,
            "c1",
            UsageRole.DEFINITION);
    when(usagesLookupService.lookup(any(UsageLookupRequest.class)))
        .thenReturn(
            new UsageLookupResult(
                List.of(definition),
                List.of(),
                List.of("java:com.andromedia.cli.progress.CliPlanProgressAdapter"),
                Duration.ZERO));

    RepositoryGroundingService service = RepositoryGroundingService.forTest(usagesLookupService, "");
    PlanGroundingContext context =
        new PlanGroundingContext(
            "document plan phases",
            "CliPlanProgressAdapter",
            searchResult,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

    GroundingBundle bundle = service.build(context);

    assertTrue(bundle.unresolvedQueries().isEmpty());
    assertTrue(
        bundle.verifiedFilePaths()
            .contains(
                "/workspace/cli/src/main/java/com/andromedia/cli/progress/CliPlanProgressAdapter.java"));
    assertTrue(bundle.verifiedFileNames().contains("CliPlanProgressAdapter.java"));
    assertTrue(bundle.verifiedSymbolNames().contains("CliPlanProgressAdapter"));
  }

  @Test
  void marksUnresolvedSymbolQuery() {
    SearchResult searchResult = new SearchResult(List.of(), 0, Duration.ZERO);
    when(usagesLookupService.lookup(any(UsageLookupRequest.class)))
        .thenReturn(new UsageLookupResult(List.of(), List.of(), List.of(), Duration.ZERO, List.of()));

    RepositoryGroundingService service = RepositoryGroundingService.forTest(usagesLookupService, "");
    PlanGroundingContext context =
        new PlanGroundingContext(
            "change Foo#bar",
            "Foo#bar",
            searchResult,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

    GroundingBundle bundle = service.build(context);

    assertFalse(bundle.unresolvedQueries().isEmpty());
    assertTrue(bundle.unresolvedQueries().getFirst().contains("Foo#bar"));
  }

  @Test
  void formatForPromptIncludesExistingSections() {
    GroundingBundle bundle =
        new GroundingBundle(
            Set.of("/workspace/Foo.java"),
            Set.of("Foo.java"),
            Set.of("java:com.foo.Foo"),
            Set.of("Foo"),
            Set.of("com.foo"),
            Set.of(),
            List.of());
    RepositoryGroundingService service = RepositoryGroundingService.forTest(usagesLookupService, "");
    String prompt = service.formatForPrompt(bundle);
    assertTrue(prompt.contains("EXISTING FILES (verified)"));
    assertTrue(prompt.contains("KNOWN SYMBOLS (verified)"));
    assertTrue(prompt.contains("Foo.java"));
  }
}
