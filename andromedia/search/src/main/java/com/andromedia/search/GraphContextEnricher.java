package com.andromedia.search;

import com.andromedia.common.SymbolGraph;
import com.andromedia.common.SymbolGraphStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class GraphContextEnricher {

  private static final Pattern CLASS_METHOD = Pattern.compile("\\b([A-Z][\\w]*)#([\\w]+)\\b");
  private static final Pattern PASCAL = Pattern.compile("\\b([A-Z][\\w]{2,})\\b");
  private static final Set<String> STOP_WORDS =
      Set.of("What", "Which", "Where", "When", "Show", "List", "Find", "Kafka", "Java", "Spring");

  private final String configuredIndexPath;

  @Autowired
  public GraphContextEnricher(
      @Value("${" + com.andromedia.common.IndexConstants.INDEX_PATH_PROPERTY + ":}")
          String configuredIndexPath) {
    this.configuredIndexPath = configuredIndexPath;
  }

  public static GraphContextEnricher forTest(String configuredIndexPath) {
    return new GraphContextEnricher(configuredIndexPath);
  }

  public Optional<GraphContextBlock> enrich(String question, int maxDepth, int maxResults) {
    if (question == null || question.isBlank() || !shouldIncludeGraphContext(question)) {
      return Optional.empty();
    }
    List<String> seedQueries = extractSeedQueries(question);
    if (seedQueries.isEmpty()) {
      return Optional.empty();
    }

    Path projectIndexPath = IndexAccess.resolveProjectIndexPath(configuredIndexPath);
    if (!Files.isDirectory(projectIndexPath)) {
      return Optional.empty();
    }

    LuceneSymbolIndex symbolIndex = LuceneSymbolIndex.load(projectIndexPath);
    LinkedHashSet<String> seedSymbolIds = new LinkedHashSet<>();
    for (String seedQuery : seedQueries) {
      seedSymbolIds.addAll(SymbolQueryParser.resolveSymbolIdsFromIndex(seedQuery, symbolIndex));
    }
    if (seedSymbolIds.isEmpty()) {
      return Optional.empty();
    }

    SymbolGraph graph;
    try {
      graph = SymbolGraphStore.load(projectIndexPath);
    } catch (IOException ex) {
      return Optional.empty();
    }
    if (graph.forwardEdges().isEmpty() && graph.reverseEdges().isEmpty()) {
      return Optional.empty();
    }

    List<TraversalHit> reverseHits =
        SymbolGraphTraversal.traverse(
            List.copyOf(seedSymbolIds),
            graph,
            TraversalDirection.REVERSE,
            maxDepth,
            maxResults);
    List<ImpactSite> reverseSites = toSites(reverseHits, symbolIndex);

    String formatted =
        formatBlock(List.copyOf(seedSymbolIds), TraversalDirection.REVERSE, reverseSites);
    return Optional.of(new GraphContextBlock(formatted, List.copyOf(seedSymbolIds), reverseSites.size()));
  }

  static boolean shouldIncludeGraphContext(String question) {
    String lower = question.toLowerCase(Locale.ROOT);
    return lower.contains("change ")
        || lower.contains("break")
        || lower.contains("affect")
        || lower.contains("impact")
        || lower.contains("blast radius")
        || lower.contains("downstream")
        || lower.contains("depend")
        || lower.contains("what happens if");
  }

  public static List<String> extractSeedQueries(String question) {
    List<String> seeds = new ArrayList<>();
    Matcher classMethod = CLASS_METHOD.matcher(question);
    while (classMethod.find()) {
      seeds.add(classMethod.group(1) + "#" + classMethod.group(2));
    }
    Matcher pascal = PASCAL.matcher(question);
    while (pascal.find()) {
      String candidate = pascal.group(1);
      if (!STOP_WORDS.contains(candidate)) {
        seeds.add(candidate);
      }
    }
    return seeds.stream().distinct().limit(3).toList();
  }

  private static List<ImpactSite> toSites(List<TraversalHit> hits, LuceneSymbolIndex symbolIndex) {
    List<ImpactSite> sites = new ArrayList<>();
    for (TraversalHit hit : hits) {
      symbolIndex
          .findBySymbolId(hit.symbolId())
          .stream()
          .findFirst()
          .ifPresent(
              entry ->
                  sites.add(
                      ImpactSite.from(entry, hit.hop(), ImpactAnalysisService.categorize(entry))));
    }
    return sites;
  }

  static String formatBlock(
      List<String> seedSymbolIds, TraversalDirection direction, List<ImpactSite> sites) {
    StringBuilder builder = new StringBuilder();
    builder.append("Seed symbols: ").append(String.join(", ", seedSymbolIds)).append('\n');
    builder.append("Direction: ").append(direction).append('\n');
    if (sites.isEmpty()) {
      builder.append("No graph neighbors found within depth limit.");
      return builder.toString();
    }
    builder.append("Affected symbols (deterministic graph traversal):\n");
    for (ImpactSite site : sites) {
      builder
          .append("- ")
          .append(site.symbolId())
          .append(" hop=")
          .append(site.hop())
          .append(" category=")
          .append(site.category())
          .append('\n');
    }
    return builder.toString();
  }

  public record GraphContextBlock(String text, List<String> seedSymbolIds, int affectedCount) {}
}
