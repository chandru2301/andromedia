package com.andromedia.cli;

import com.andromedia.cli.progress.CliPhase;
import com.andromedia.cli.progress.CliProgressReporter;
import com.andromedia.cli.progress.PhaseTiming;
import com.andromedia.search.ImpactAnalysisService;
import com.andromedia.search.ImpactCategoryFilters;
import com.andromedia.search.ImpactExplanationService;
import com.andromedia.search.ImpactLookupRequest;
import com.andromedia.search.ImpactLookupResult;
import com.andromedia.search.TraversalDirection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Component
@Command(
    name = "impact",
    description = "Analyze symbol impact using the repository graph",
    mixinStandardHelpOptions = true)
public class ImpactCommand implements Runnable {

  private final ImpactAnalysisService impactAnalysisService;
  private final ImpactExplanationService impactExplanationService;
  private final CliExecutionContext executionContext;

  @Parameters(index = "0", description = "Symbol name, Class#method, or full symbolId")
  private String symbolQuery;

  @Option(
      names = {"--reverse"},
      description = "Incoming graph: who depends on this symbol (default blast radius)")
  private boolean reverse;

  @Option(
      names = {"--forward", "--dependencies"},
      description = "Outgoing graph: what this symbol depends on")
  private boolean forward;

  @Option(
      names = {"--grouped"},
      description = "Also show category breakdown (API, Kafka, tests, etc.)")
  private boolean grouped;

  @Option(
      names = {"--category"},
      description = "Filter by category: kafka, api, repository, test, service (comma-separated)")
  private String categoryFilter;

  @Option(
      names = {"--explain"},
      description = "Summarize deterministic impact results with LLM (render only)")
  private boolean explain;

  @Option(names = {"-d", "--depth"}, defaultValue = "5", description = "Maximum traversal depth")
  private int maxDepth;

  @Option(
      names = {"-n", "--limit"},
      defaultValue = "20",
      description = "Maximum results (flat mode) or per-category cap with --grouped")
  private int maxResults;

  public ImpactCommand(
      ImpactAnalysisService impactAnalysisService,
      ImpactExplanationService impactExplanationService,
      CliExecutionContext executionContext) {
    this.impactAnalysisService = impactAnalysisService;
    this.impactExplanationService = impactExplanationService;
    this.executionContext = executionContext;
  }

  @Override
  public void run() {
    if (reverse && forward) {
      throw new IllegalArgumentException("Use either --reverse or --forward, not both");
    }

    String validatedQuery = validateQuery(symbolQuery);
    TraversalDirection direction = forward ? TraversalDirection.FORWARD : TraversalDirection.REVERSE;
    Set<com.andromedia.search.ImpactCategory> filters = ImpactCategoryFilters.parseCsv(categoryFilter);

    boolean plain = executionContext.isPlainProgress();
    CliProgressReporter progress = CliProgressReporter.create(plain, true);
    List<PhaseTiming> timings = new ArrayList<>();

    progress.startPhase(CliPhase.ANALYSING_IMPACT);
    progress.updatePhase(CliPhase.ANALYSING_IMPACT, validatedQuery);
    ImpactLookupResult result =
        impactAnalysisService.analyze(
            new ImpactLookupRequest(
                validatedQuery, direction, maxDepth, maxResults, grouped, filters));
    progress.completePhase(CliPhase.ANALYSING_IMPACT, result.elapsed());
    timings.add(new PhaseTiming(CliPhase.ANALYSING_IMPACT, result.elapsed()));

    ImpactOutputFormatter.printResult(
        result, validatedQuery, maxDepth, executionContext.isDebugEnabled());

    if (explain && !result.affectedSymbols().isEmpty()) {
      Instant summaryStartedAt = Instant.now();
      progress.startPhase(CliPhase.SUMMARIZING);
      System.out.println();
      System.out.println("Summary:");
      impactExplanationService.explain(validatedQuery, result, token -> System.out.print(token));
      System.out.println();
      Duration summaryElapsed = Duration.between(summaryStartedAt, Instant.now());
      progress.completePhase(CliPhase.SUMMARIZING, summaryElapsed);
      timings.add(new PhaseTiming(CliPhase.SUMMARIZING, summaryElapsed));
    }

    progress.seal();
    progress.printTimingSummary(timings, plain);
  }

  private static String validateQuery(String query) {
    if (query == null || query.isBlank()) {
      throw new IllegalArgumentException("Symbol query is required");
    }
    return query.trim();
  }
}
