package com.andromedia.cli;

import com.andromedia.cli.progress.CliPhase;
import com.andromedia.cli.progress.CliProgressReporter;
import com.andromedia.cli.progress.PhaseTiming;
import com.andromedia.llm.AssembledContext;
import com.andromedia.llm.ContextAssembler;
import com.andromedia.search.SearchDiagnostics;
import com.andromedia.search.SearchMode;
import com.andromedia.search.SearchRequest;
import com.andromedia.search.SearchResult;
import com.andromedia.search.SearchService;
import java.util.List;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Component
@Command(
    name = "search",
    description = "Search the Lucene index",
    mixinStandardHelpOptions = true)
public class SearchCommand implements Runnable {

  private final SearchService searchService;
  private final ContextAssembler contextAssembler;
  private final CliExecutionContext executionContext;

  @Parameters(index = "0", description = "Search query")
  private String query;

  @Option(names = {"-n", "--limit"}, defaultValue = "10", description = "Maximum results to return")
  private int maxResults;

  @Option(
      names = "--semantic",
      description = "Semantic vector search (requires index built with `andro index --embed`)")
  private boolean semanticSearch;

  @Option(
      names = "--hybrid",
      description = "Hybrid BM25 + semantic search with reciprocal rank fusion")
  private boolean hybridSearch;

  @Option(
      names = "--expand-context",
      description = "Print expanded LLM context blocks (stderr; use with --debug)")
  private boolean expandContext;

  public SearchCommand(
      SearchService searchService,
      ContextAssembler contextAssembler,
      CliExecutionContext executionContext) {
    this.searchService = searchService;
    this.contextAssembler = contextAssembler;
    this.executionContext = executionContext;
  }

  @Override
  public void run() {
    String validatedQuery = validateQuery(query);
    SearchMode mode = resolveSearchMode();
    boolean plain = executionContext.isPlainProgress();
    CliProgressReporter progress = CliProgressReporter.create(plain, true);

    CliPhase searchPhase = searchPhaseFor(mode);
    progress.startPhase(searchPhase);
    progress.updatePhase(searchPhase, validatedQuery);

    SearchRequest request = new SearchRequest(validatedQuery, maxResults, mode);
    SearchResult result;
    try {
      result = searchService.search(request);
    } catch (RuntimeException ex) {
      progress.fail(ex.getMessage());
      throw ex;
    }

    String searchDetail = result.hits().size() + " hits";
    SearchDiagnostics diagnostics = result.diagnostics();
    if (mode == SearchMode.HYBRID
        && diagnostics != null
        && Boolean.TRUE.equals(diagnostics.semanticSkipped())) {
      searchDetail = searchDetail + ", semantic unavailable, BM25 only";
    }
    progress.updatePhase(searchPhase, searchDetail);
    progress.completePhase(searchPhase, result.elapsed());

    if (executionContext.isDebugEnabled() && diagnostics != null) {
      printDebugDiagnostics(result, mode);
    }

    if (result.hits().isEmpty()) {
      System.out.println("No results found.");
    } else {
      for (var hit : result.hits()) {
        printHit(hit, mode);
      }
    }

    System.out.printf(
        "%n%d results (%d total matches) in %d ms%n",
        result.hits().size(), result.totalHits(), result.elapsed().toMillis());

    if (shouldExpandContext()) {
      progress.startPhase(CliPhase.EXPANDING_CONTEXT);
      AssembledContext context =
          contextAssembler.assemble(
              result.hits().stream().map(hit -> hit.toRetrievalReference()).toList());
      progress.updatePhase(CliPhase.EXPANDING_CONTEXT, context.blocks().size() + " blocks");
      progress.completePhase(CliPhase.EXPANDING_CONTEXT, result.elapsed());
      printExpandedContext(context);
    }
  }

  private static CliPhase searchPhaseFor(SearchMode mode) {
    return switch (mode) {
      case BM25 -> CliPhase.BM25_SEARCH;
      case SEMANTIC -> CliPhase.SEMANTIC_SEARCH;
      case HYBRID -> CliPhase.HYBRID_SEARCH;
    };
  }

  private SearchMode resolveSearchMode() {
    if (hybridSearch && semanticSearch) {
      throw new IllegalArgumentException("Use either --hybrid or --semantic, not both");
    }
    if (hybridSearch) {
      return SearchMode.HYBRID;
    }
    if (semanticSearch) {
      return SearchMode.SEMANTIC;
    }
    return SearchMode.BM25;
  }

  private boolean shouldExpandContext() {
    return expandContext && executionContext.isDebugEnabled();
  }

  private void printHit(com.andromedia.search.SearchHit hit, SearchMode mode) {
    String symbol = hit.symbolLabel();
    if (executionContext.isDebugEnabled() && mode == SearchMode.HYBRID) {
      System.out.printf(
          "%-40s  %-24s  (%.4f)  bm25=%s semantic=%s  %s%n",
          hit.locationLabel(),
          symbol.isBlank() ? "-" : symbol,
          hit.score(),
          formatChannelScore(hit.bm25Score()),
          formatChannelScore(hit.semanticScore()),
          hit.path());
      return;
    }
    if (symbol.isBlank()) {
      System.out.printf("%-40s  (%.2f)  %s%n", hit.locationLabel(), hit.score(), hit.path());
    } else {
      System.out.printf(
          "%-40s  %-24s  (%.2f)  %s%n",
          hit.locationLabel(), symbol, hit.score(), hit.path());
    }
  }

  private static String formatChannelScore(Float score) {
    return score == null ? "-" : String.format("%.2f", score);
  }

  private void printDebugDiagnostics(SearchResult result, SearchMode mode) {
    var diagnostics = result.diagnostics();
    System.err.printf("[debug] query='%s'%n", diagnostics.queryText());
    System.err.printf("[debug] mode=%s%n", mode.name());
    System.err.printf("[debug] workspaceRoot=%s%n", diagnostics.workspaceRoot());
    System.err.printf("[debug] indexPath=%s%n", diagnostics.indexPath());
    if (diagnostics.queryType() != null) {
      System.err.printf("[debug] queryType=%s%n", diagnostics.queryType());
    }
    if (mode == SearchMode.HYBRID) {
      System.err.printf(
          "[debug] bm25Candidates=%s semanticCandidates=%s semanticSkipped=%s semanticFiltered=%s%n",
          diagnostics.bm25Candidates(),
          diagnostics.semanticCandidates(),
          diagnostics.semanticSkipped(),
          diagnostics.semanticFilteredCount());
      System.err.printf(
          "[debug] channelWeights bm25=%s semantic=%s%n",
          diagnostics.bm25ChannelWeight(), diagnostics.semanticChannelWeight());
      if (diagnostics.semanticSkipReason() != null) {
        System.err.printf("[debug] semanticSkipReason=%s%n", diagnostics.semanticSkipReason());
      }
    }
    System.err.printf("[debug] elapsedMs=%d%n", result.elapsed().toMillis());
  }

  private void printExpandedContext(AssembledContext context) {
    if (context.blocks().isEmpty()) {
      System.err.println("[debug] context expansion produced no blocks");
      return;
    }
    System.err.println("[debug] expanded context:");
    for (var block : context.blocks()) {
      System.err.printf("[debug] --- %s (%s) ---%n", block.label(), block.path());
      System.err.println(block.content());
      System.err.println();
    }
  }

  private static String validateQuery(String queryText) {
    if (queryText == null || queryText.isBlank()) {
      throw new IllegalArgumentException("Search query is required");
    }
    return queryText.trim();
  }
}
