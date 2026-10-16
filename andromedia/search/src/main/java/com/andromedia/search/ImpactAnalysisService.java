package com.andromedia.search;

import com.andromedia.common.SymbolEntry;
import com.andromedia.common.SymbolGraph;
import com.andromedia.common.SymbolGraphStore;
import com.andromedia.common.SymbolType;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ImpactAnalysisService {

  private final String configuredIndexPath;

  @Autowired
  public ImpactAnalysisService(
      @Value("${" + com.andromedia.common.IndexConstants.INDEX_PATH_PROPERTY + ":}")
          String configuredIndexPath) {
    this.configuredIndexPath = configuredIndexPath;
  }

  public static ImpactAnalysisService forTest(String configuredIndexPath) {
    return new ImpactAnalysisService(configuredIndexPath);
  }

  public ImpactLookupResult analyze(ImpactLookupRequest request) {
    Instant startedAt = Instant.now();
    Path projectIndexPath = IndexAccess.resolveProjectIndexPath(configuredIndexPath);
    IndexAccess.validateIndexExists(projectIndexPath);

    LuceneSymbolIndex symbolIndex = LuceneSymbolIndex.load(projectIndexPath);
    List<String> seedSymbolIds =
        SymbolQueryParser.resolveSymbolIdsFromIndex(request.symbolQuery(), symbolIndex);
    if (seedSymbolIds.isEmpty()) {
      List<MethodHint> methodHints =
          SymbolQueryParser.methodHintsForMissedClassMethodQuery(request.symbolQuery(), symbolIndex);
      return new ImpactLookupResult(
          List.of(),
          request.direction(),
          List.of(),
          Map.of(),
          elapsed(startedAt),
          methodHints);
    }

    SymbolGraph graph;
    try {
      graph = SymbolGraphStore.load(projectIndexPath);
    } catch (IOException ex) {
      throw new SearchException("Failed to load symbol graph at: " + projectIndexPath, ex);
    }
    if (graph.forwardEdges().isEmpty() && graph.reverseEdges().isEmpty()) {
      throw new SearchException(
          "Symbol graph not found. Re-index with the latest Andromedia: andro index --recreate .");
    }

    TraversalDirection direction =
        request.direction() == null ? TraversalDirection.REVERSE : request.direction();
    int maxDepth = Math.max(1, request.maxDepth());
    int maxResults = Math.max(1, request.maxResults());
    Set<ImpactCategory> categoryFilters =
        request.categoryFilters() == null ? Set.of() : request.categoryFilters();

    int traversalLimit =
        categoryFilters.isEmpty() ? maxResults : Math.max(maxResults, maxResults * 10);

    List<TraversalHit> hits =
        SymbolGraphTraversal.traverse(seedSymbolIds, graph, direction, maxDepth, traversalLimit);
    List<ImpactSite> affectedSymbols = toImpactSites(hits, symbolIndex);
    affectedSymbols = applyCategoryFilters(affectedSymbols, categoryFilters, maxResults);

    Map<ImpactCategory, List<ImpactSite>> grouped = Map.of();
    if (request.grouped()) {
      grouped = groupByCategory(affectedSymbols, maxResults);
    }

    return new ImpactLookupResult(
        seedSymbolIds, direction, affectedSymbols, grouped, elapsed(startedAt));
  }

  private static List<ImpactSite> toImpactSites(
      List<TraversalHit> hits, LuceneSymbolIndex symbolIndex) {
    List<ImpactSite> sites = new ArrayList<>();
    for (TraversalHit hit : hits) {
      symbolIndex.findBySymbolId(hit.symbolId()).stream()
          .findFirst()
          .ifPresent(
              entry ->
                  sites.add(ImpactSite.from(entry, hit.hop(), categorize(entry))));
    }
    return sites;
  }

  static List<ImpactSite> applyCategoryFilters(
      List<ImpactSite> sites, Set<ImpactCategory> categoryFilters, int maxResults) {
    if (categoryFilters == null || categoryFilters.isEmpty()) {
      return sites.size() <= maxResults ? sites : sites.subList(0, maxResults);
    }
    List<ImpactSite> filtered = new ArrayList<>();
    for (ImpactSite site : sites) {
      if (categoryFilters.contains(site.category())) {
        filtered.add(site);
        if (filtered.size() >= maxResults) {
          break;
        }
      }
    }
    return filtered;
  }

  private static Map<ImpactCategory, List<ImpactSite>> groupByCategory(
      List<ImpactSite> affectedSymbols, int perCategoryLimit) {
    Map<ImpactCategory, List<ImpactSite>> grouped = new EnumMap<>(ImpactCategory.class);
    for (ImpactCategory category : ImpactCategory.values()) {
      grouped.put(category, new ArrayList<>());
    }
    Map<ImpactCategory, Integer> counts = new EnumMap<>(ImpactCategory.class);
    for (ImpactCategory category : ImpactCategory.values()) {
      counts.put(category, 0);
    }
    for (ImpactSite site : affectedSymbols) {
      ImpactCategory category = site.category();
      if (counts.get(category) >= perCategoryLimit) {
        continue;
      }
      grouped.get(category).add(site);
      counts.put(category, counts.get(category) + 1);
    }
    return grouped;
  }

  static ImpactCategory categorize(SymbolEntry entry) {
    String path = entry.filePath() == null ? "" : entry.filePath().toLowerCase();
    String name = entry.symbolName() == null ? "" : entry.symbolName();
    String symbolId = entry.symbolId() == null ? "" : entry.symbolId();

    if (path.contains("/test/")
        || path.contains("\\test\\")
        || path.endsWith("test.java")
        || path.contains("test/")
        || name.endsWith("Test")
        || name.endsWith("IT")
        || name.contains("_test")) {
      return ImpactCategory.TEST;
    }
    if (name.endsWith("Repository")
        || path.contains("repository")
        || symbolId.contains("Repository")) {
      return ImpactCategory.REPOSITORY;
    }
    if (name.endsWith("Listener")
        || name.contains("Kafka")
        || path.contains("listener")
        || path.contains("messaging")) {
      return ImpactCategory.KAFKA_MESSAGING;
    }
    if (path.contains("controller")
        || name.endsWith("Controller")
        || symbolId.contains("Controller")) {
      return ImpactCategory.API;
    }
    if (name.endsWith("Service")
        || name.endsWith("Handler")
        || name.endsWith("Consumer")
        || entry.symbolType() == SymbolType.METHOD && symbolId.contains("Service#")) {
      return ImpactCategory.SERVICE;
    }
    return ImpactCategory.OTHER;
  }

  private static Duration elapsed(Instant startedAt) {
    return Duration.between(startedAt, Instant.now());
  }
}
