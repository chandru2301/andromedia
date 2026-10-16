package com.andromedia.cli.shell.agent;

import com.andromedia.cli.CliExecutionContext;
import com.andromedia.cli.ImpactOutputFormatter;
import com.andromedia.cli.progress.CliPhase;
import com.andromedia.cli.progress.CliProgressReporter;
import com.andromedia.cli.progress.PhaseTiming;
import com.andromedia.llm.SideQueryService;
import com.andromedia.search.GraphQuery;
import com.andromedia.search.ImpactAnalysisService;
import com.andromedia.search.ImpactExplanationService;
import com.andromedia.search.ImpactLookupResult;
import com.andromedia.search.TraversalDirection;
import java.io.PrintStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ImpactShellHandler {

  private final ImpactAnalysisService impactAnalysisService;
  private final ImpactExplanationService impactExplanationService;
  private final SideQueryService sideQueryService;
  private final CliExecutionContext executionContext;
  private final PrintStream out;
  private final PrintStream err;

  @Autowired
  public ImpactShellHandler(
      ImpactAnalysisService impactAnalysisService,
      ImpactExplanationService impactExplanationService,
      SideQueryService sideQueryService,
      CliExecutionContext executionContext) {
    this(
        impactAnalysisService,
        impactExplanationService,
        sideQueryService,
        executionContext,
        System.out,
        System.err);
  }

  ImpactShellHandler(
      ImpactAnalysisService impactAnalysisService,
      ImpactExplanationService impactExplanationService,
      SideQueryService sideQueryService,
      CliExecutionContext executionContext,
      PrintStream out,
      PrintStream err) {
    this.impactAnalysisService = impactAnalysisService;
    this.impactExplanationService = impactExplanationService;
    this.sideQueryService = sideQueryService;
    this.executionContext = executionContext;
    this.out = out;
    this.err = err;
  }

  public int handleNaturalLanguage(String line) {
    return handleNaturalLanguage(line, null);
  }

  public int handleNaturalLanguage(String line, ShellIntent shellIntent) {
    return ImpactQueryParser.parse(line)
        .or(() -> graphQueryFromSideIntent(shellIntent))
        .or(() -> graphQueryFromSideQuery(line))
        .map(this::executeQuery)
        .orElseGet(() -> {
          err.println(
              "Could not parse impact query. Include a symbol, e.g.: what breaks if I change AnomalyEvent?");
          return 1;
        });
  }

  private Optional<GraphQuery> graphQueryFromSideIntent(ShellIntent shellIntent) {
    if (shellIntent == null || shellIntent.intent() != IntentType.IMPACT) {
      return Optional.empty();
    }
    return shellIntent
        .effectiveSymbol()
        .map(
            symbol ->
                new GraphQuery(
                    symbol,
                    shellIntent.direction() != null
                        ? shellIntent.direction()
                        : TraversalDirection.REVERSE,
                    Set.of(),
                    shellIntent.depth() != null ? shellIntent.depth() : 5,
                    20,
                    false,
                    Boolean.TRUE.equals(shellIntent.explain())));
  }

  private Optional<GraphQuery> graphQueryFromSideQuery(String line) {
    return sideQueryService
        .resolveIntent(line)
        .filter(side -> side.intent() == SideQueryService.SideQueryIntentType.IMPACT)
        .flatMap(
            side -> {
              if (side.symbol() == null || side.symbol().isBlank()) {
                return Optional.empty();
              }
              TraversalDirection direction =
                  side.direction() != null && side.direction().equalsIgnoreCase("forward")
                      ? TraversalDirection.FORWARD
                      : TraversalDirection.REVERSE;
              return Optional.of(
                  new GraphQuery(
                      side.symbol(),
                      direction,
                      Set.of(),
                      side.depth() != null ? side.depth() : 5,
                      20,
                      false,
                      Boolean.TRUE.equals(side.explain())));
            });
  }

  private int executeQuery(GraphQuery query) {
    boolean plain = executionContext.isPlainProgress();
    CliProgressReporter progress = CliProgressReporter.create(plain, true);
    List<PhaseTiming> timings = new ArrayList<>();

    progress.startPhase(CliPhase.ANALYSING_IMPACT);
    progress.updatePhase(CliPhase.ANALYSING_IMPACT, query.symbolQuery());
    ImpactLookupResult result = impactAnalysisService.analyze(query.toLookupRequest());
    progress.completePhase(CliPhase.ANALYSING_IMPACT, result.elapsed());
    timings.add(new PhaseTiming(CliPhase.ANALYSING_IMPACT, result.elapsed()));

    ImpactOutputFormatter.printResult(
        result, query.symbolQuery(), query.maxDepth(), executionContext.isDebugEnabled());

    if (query.explain() && !result.affectedSymbols().isEmpty()) {
      Instant summaryStartedAt = Instant.now();
      progress.startPhase(CliPhase.SUMMARIZING);
      out.println();
      out.println("Summary:");
      impactExplanationService.explain(query.symbolQuery(), result, token -> out.print(token));
      out.println();
      Duration summaryElapsed = Duration.between(summaryStartedAt, Instant.now());
      progress.completePhase(CliPhase.SUMMARIZING, summaryElapsed);
      timings.add(new PhaseTiming(CliPhase.SUMMARIZING, summaryElapsed));
    }

    progress.seal();
    progress.printTimingSummary(timings, plain);
    return result.seedSymbolIds().isEmpty() ? 1 : 0;
  }
}
