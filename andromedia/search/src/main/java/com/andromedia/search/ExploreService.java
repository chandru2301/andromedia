package com.andromedia.search;

import com.andromedia.common.IndexConstants;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class ExploreService {

  private static final int DEFAULT_SEARCH_LIMIT = 5;
  private static final int DECISION_SEARCH_LIMIT = 10;
  private static final int DEFAULT_IMPACT_DEPTH = 2;
  private static final int DEFAULT_IMPACT_LIMIT = 10;
  private static final int GIT_CONTENT_CAP = 1500;
  private static final int SLACK_CONTENT_CAP = 1500;
  private static final int NOTION_CONTENT_CAP = 400;
  private static final int TICKET_CONTENT_CAP = 500;

  private final SearchService searchService;
  private final UsagesLookupService usagesLookupService;
  private final ImpactAnalysisService impactAnalysisService;
  private final GraphContextEnricher graphContextEnricher;

  public ExploreService(
      SearchService searchService,
      UsagesLookupService usagesLookupService,
      ImpactAnalysisService impactAnalysisService,
      GraphContextEnricher graphContextEnricher) {
    this.searchService = searchService;
    this.usagesLookupService = usagesLookupService;
    this.impactAnalysisService = impactAnalysisService;
    this.graphContextEnricher = graphContextEnricher;
  }

  public ExploreResult explore(String query, String optionalSymbol) {
    int searchLimit =
        DecisionIntentDetector.isDecisionIntent(query)
                || ArchitectureIntentDetector.isArchitectureIntent(query)
            ? DECISION_SEARCH_LIMIT
            : DEFAULT_SEARCH_LIMIT;
    return explore(query, optionalSymbol, searchLimit);
  }

  /** Hybrid explore with an explicit hit cap (named-entity pin uses a wider pool). */
  public ExploreResult explore(String query, String optionalSymbol, int maxResults) {
    List<String> sections = new ArrayList<>();
    Set<String> seenChunkIds = new LinkedHashSet<>();

    int searchLimit = Math.max(1, maxResults);
    SearchResult primary =
        searchService.search(new SearchRequest(query, searchLimit, SearchMode.HYBRID));
    if (!primary.hits().isEmpty()) {
      StringBuilder searchSection = new StringBuilder("Hybrid search hits:\n");
      for (SearchHit hit : primary.hits()) {
        seenChunkIds.add(hit.chunkId());
        searchSection.append(formatHitLine(hit));
      }
      sections.add(searchSection.toString().trim());
    }

    String symbol = optionalSymbol;
    if (symbol == null || symbol.isBlank()) {
      List<String> seeds = GraphContextEnricher.extractSeedQueries(query);
      symbol = seeds.isEmpty() ? null : seeds.getFirst();
    }
    if (symbol != null && !symbol.isBlank()) {
      try {
        UsageLookupResult usages =
            usagesLookupService.lookup(new UsageLookupRequest(symbol, 5));
        if (!usages.references().isEmpty() || !usages.definitions().isEmpty()) {
          StringBuilder usageSection = new StringBuilder("Symbol usages for " + symbol + ":\n");
          usages
              .definitions()
              .forEach(site -> usageSection.append("- def ").append(site.symbolId()).append('\n'));
          usages
              .references()
              .forEach(site -> usageSection.append("- ref ").append(site.symbolId()).append('\n'));
          sections.add(usageSection.toString().trim());
        }
      } catch (RuntimeException ignored) {
        // usages optional
      }

      try {
        ImpactLookupResult impact =
            impactAnalysisService.analyze(
                new ImpactLookupRequest(
                    symbol, TraversalDirection.REVERSE, DEFAULT_IMPACT_DEPTH, DEFAULT_IMPACT_LIMIT, false, Set.of()));
        if (!impact.affectedSymbols().isEmpty()) {
          sections.add("Impact preview:\n" + ImpactExplanationService.formatResults(impact));
        }
      } catch (RuntimeException ignored) {
        // impact optional
      }
    }

    graphContextEnricher
        .enrich(query, DEFAULT_IMPACT_DEPTH, DEFAULT_IMPACT_LIMIT)
        .ifPresent(block -> sections.add("Graph context:\n" + block.text()));

    List<com.andromedia.common.RetrievalReference> references =
        primary.hits().stream().map(SearchHit::toRetrievalReference).toList();
    return new ExploreResult(String.join("\n\n", sections), references, primary);
  }

  static String formatHitLine(SearchHit hit) {
    String sourceType = hit.sourceType().orElse("");
    if (IndexConstants.SOURCE_TYPE_GIT.equals(sourceType)) {
      return formatGitHitLine(hit);
    }
    if (IndexConstants.SOURCE_TYPE_SLACK.equals(sourceType)) {
      return formatSlackHitLine(hit);
    }
    if (IndexConstants.SOURCE_TYPE_NOTION.equals(sourceType)) {
      return formatNotionHitLine(hit);
    }
    if (IndexConstants.SOURCE_TYPE_JIRA.equals(sourceType)) {
      return formatJiraHitLine(hit);
    }
    if (IndexConstants.SOURCE_TYPE_LINEAR.equals(sourceType)) {
      return formatLinearHitLine(hit);
    }
    if (IndexConstants.SOURCE_TYPE_ZOHO.equals(sourceType)) {
      return formatZohoHitLine(hit);
    }
    StringBuilder line = new StringBuilder("- ");
    line.append(hit.fileName());
    if (hit.className() != null && !hit.className().isBlank()) {
      line.append(' ').append(hit.className());
    }
    if (hit.methodName() != null && !hit.methodName().isBlank()) {
      line.append('#').append(hit.methodName());
    }
    line.append(" score=").append(String.format("%.3f", hit.score())).append('\n');
    return line.toString();
  }

  private static String formatGitHitLine(SearchHit hit) {
    StringBuilder line = new StringBuilder("- [GIT]");
    hit.commitSha()
        .ifPresent(sha -> line.append(" commit=").append(abbreviateSha(sha)));
    hit.timestamp().ifPresent(ts -> line.append(" @ ").append(ts));
    line.append(" score=").append(String.format("%.3f", hit.score())).append('\n');
    hit.content()
        .map(ExploreService::capGitContent)
        .ifPresent(
            content -> {
              for (String contentLine : content.split("\n")) {
                line.append("  ").append(contentLine).append('\n');
              }
            });
    return line.toString();
  }

  private static String formatSlackHitLine(SearchHit hit) {
    StringBuilder line = new StringBuilder("- [SLACK]");
    if (hit.fileName() != null && !hit.fileName().isBlank()) {
      line.append(" #").append(hit.fileName());
    }
    hit.timestamp().ifPresent(ts -> line.append(" @ ").append(ts));
    line.append(" score=").append(String.format("%.3f", hit.score())).append('\n');
    hit.content()
        .map(ExploreService::capSlackContent)
        .ifPresent(
            content -> {
              for (String contentLine : content.split("\n")) {
                line.append("  ").append(contentLine).append('\n');
              }
            });
    return line.toString();
  }

  private static String formatNotionHitLine(SearchHit hit) {
    StringBuilder line = new StringBuilder("- [NOTION]");
    if (hit.fileName() != null && !hit.fileName().isBlank()) {
      line.append(' ').append(hit.fileName());
    }
    hit.timestamp()
        .ifPresent(
            ts -> {
              String date = ts.length() >= 10 ? ts.substring(0, 10) : ts;
              line.append(" @ ").append(date);
            });
    line.append(" score=").append(String.format("%.3f", hit.score())).append('\n');
    hit.content()
        .map(ExploreService::capNotionContent)
        .ifPresent(
            content -> {
              for (String contentLine : content.split("\n")) {
                line.append("  ").append(contentLine).append('\n');
              }
            });
    return line.toString();
  }

  private static String formatJiraHitLine(SearchHit hit) {
    StringBuilder line = new StringBuilder("- [JIRA]");
    if (hit.fileName() != null && !hit.fileName().isBlank()) {
      line.append(' ').append(hit.fileName());
    }
    hit.timestamp()
        .ifPresent(
            ts -> {
              String date = ts.length() >= 10 ? ts.substring(0, 10) : ts;
              line.append(" @ ").append(date);
            });
    line.append(" score=").append(String.format("%.3f", hit.score())).append('\n');
    hit.content()
        .map(ExploreService::capTicketContent)
        .ifPresent(
            content -> {
              for (String contentLine : content.split("\n")) {
                line.append("  ").append(contentLine).append('\n');
              }
            });
    return line.toString();
  }

  private static String formatLinearHitLine(SearchHit hit) {
    StringBuilder line = new StringBuilder("- [LINEAR]");
    if (hit.fileName() != null && !hit.fileName().isBlank()) {
      line.append(' ').append(hit.fileName());
    }
    hit.timestamp()
        .ifPresent(
            ts -> {
              String date = ts.length() >= 10 ? ts.substring(0, 10) : ts;
              line.append(" @ ").append(date);
            });
    line.append(" score=").append(String.format("%.3f", hit.score())).append('\n');
    hit.content()
        .map(ExploreService::capTicketContent)
        .ifPresent(
            content -> {
              for (String contentLine : content.split("\n")) {
                line.append("  ").append(contentLine).append('\n');
              }
            });
    return line.toString();
  }

  private static String formatZohoHitLine(SearchHit hit) {
    StringBuilder line = new StringBuilder("- [ZOHO]");
    if (hit.fileName() != null && !hit.fileName().isBlank()) {
      line.append(' ').append(hit.fileName());
    }
    hit.timestamp()
        .ifPresent(
            ts -> {
              String date = ts.length() >= 10 ? ts.substring(0, 10) : ts;
              line.append(" @ ").append(date);
            });
    line.append(" score=").append(String.format("%.3f", hit.score())).append('\n');

    ZohoHitMetadata zoho = hit.zoho().orElse(ZohoHitMetadata.EMPTY);
    line.append("  Structured: ")
        .append("Amount=")
        .append(displayOrDash(zoho.amount()))
        .append("; Expected_Revenue=")
        .append(displayOrDash(zoho.expectedRevenue()))
        .append("; Probability=")
        .append(displayOrDash(zoho.probability()))
        .append("; Close=")
        .append(displayOrDash(zoho.closingDate()))
        .append("; Stage=")
        .append(displayOrDash(zoho.stage()));
    zoho.accountName()
        .filter(v -> !v.isBlank())
        .ifPresent(name -> line.append("; Account=").append(name));
    zoho.owner()
        .filter(v -> !v.isBlank())
        .ifPresent(owner -> line.append("; Owner=").append(owner));
    zoho.nextStep()
        .filter(v -> !v.isBlank())
        .ifPresent(next -> line.append("; Next_Step=").append(next));
    line.append('\n');

    List<String> answerFlags = answerFacingFlags(zoho.qualitySignals());
    if (!answerFlags.isEmpty()) {
      line.append("  Flags: ").append(String.join(" | ", answerFlags)).append('\n');
    }

    if (!zoho.contacts().isEmpty()) {
      line.append("  Contacts: ").append(String.join("; ", zoho.contacts())).append('\n');
    } else if (zoho.qualitySignals().contains("no_contacts")) {
      line.append("  Contacts: (none on record)\n");
    }

    if (zoho.accountDescription().filter(v -> !v.isBlank()).isEmpty()
        && zoho.qualitySignals().contains("missing_account_description")) {
      line.append("  Account description: (missing)\n");
    }

    if (!zoho.noteSnippets().isEmpty()) {
      for (String note : zoho.noteSnippets()) {
        line.append("  Notes: \"")
            .append(note.replace('"', '\''))
            .append("\" (unverified free-text)\n");
      }
    }

    hit.content()
        .map(ExploreService::capTicketContent)
        .ifPresent(
            content -> {
              line.append("  Narrative:\n");
              for (String contentLine : content.split("\n")) {
                line.append("  ").append(contentLine).append('\n');
              }
            });
    return line.toString();
  }

  private static String displayOrDash(Optional<String> value) {
    if (value.isEmpty() || value.get().isBlank()) {
      return "—";
    }
    return value.get();
  }

  private static List<String> answerFacingFlags(List<String> signals) {
    if (signals == null || signals.isEmpty()) {
      return List.of();
    }
    List<String> flags = new java.util.ArrayList<>();
    for (String signal : signals) {
      switch (signal) {
        case "test_demo_pattern",
            "stale_record",
            "close_date_passed",
            "missing_account_description",
            "no_contacts",
            "no_notes",
            "no_activities" ->
            flags.add(signal);
        default -> {
          // positive enrichment signals stay out of the Flags: line
        }
      }
    }
    return flags;
  }

  private static String capGitContent(String content) {
    if (content.length() <= GIT_CONTENT_CAP) {
      return content;
    }
    return content.substring(0, GIT_CONTENT_CAP) + "...";
  }

  private static String capSlackContent(String content) {
    if (content.length() <= SLACK_CONTENT_CAP) {
      return content;
    }
    return content.substring(0, SLACK_CONTENT_CAP) + "...";
  }

  private static String capNotionContent(String content) {
    if (content.length() <= NOTION_CONTENT_CAP) {
      return content;
    }
    return content.substring(0, NOTION_CONTENT_CAP) + "...";
  }

  private static String capTicketContent(String content) {
    if (content.length() <= TICKET_CONTENT_CAP) {
      return content;
    }
    return content.substring(0, TICKET_CONTENT_CAP) + "...";
  }

  private static String abbreviateSha(String sha) {
    if (sha.length() <= 12) {
      return sha;
    }
    return sha.substring(0, 12) + "…";
  }

  public record ExploreResult(
      String summaryText,
      List<com.andromedia.common.RetrievalReference> references,
      SearchResult searchResult) {}
}
