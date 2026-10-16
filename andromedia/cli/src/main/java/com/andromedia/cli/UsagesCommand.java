package com.andromedia.cli;

import com.andromedia.cli.progress.CliPhase;
import com.andromedia.cli.progress.CliProgressReporter;
import com.andromedia.cli.progress.PhaseTiming;
import com.andromedia.search.MethodHint;
import com.andromedia.search.SymbolQueryParser;
import com.andromedia.search.UsageLookupRequest;
import com.andromedia.search.UsageLookupResult;
import com.andromedia.search.UsageSite;
import com.andromedia.search.UsagesLookupService;
import java.util.List;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Component
@Command(
    name = "usages",
    description = "Look up symbol definitions and references",
    mixinStandardHelpOptions = true)
public class UsagesCommand implements Runnable {

  private final UsagesLookupService usagesLookupService;
  private final CliExecutionContext executionContext;

  @Parameters(index = "0", description = "Symbol name, Class#method, or full symbolId")
  private String symbolQuery;

  @Option(names = {"-n", "--limit"}, defaultValue = "20", description = "Maximum sites per section")
  private int maxResults;

  @Option(names = "--definitions-only", description = "Show definition sites only")
  boolean definitionsOnly;

  @Option(names = "--references-only", description = "Show reference sites only")
  boolean referencesOnly;

  public UsagesCommand(UsagesLookupService usagesLookupService, CliExecutionContext executionContext) {
    this.usagesLookupService = usagesLookupService;
    this.executionContext = executionContext;
  }

  @Override
  public void run() {
    if (definitionsOnly && referencesOnly) {
      throw new IllegalArgumentException("Use either --definitions-only or --references-only, not both");
    }
    String validatedQuery = validateQuery(symbolQuery);

    boolean plain = executionContext.isPlainProgress();
    CliProgressReporter progress = CliProgressReporter.create(plain, true);

    progress.startPhase(CliPhase.LOOKING_UP_USAGES);
    progress.updatePhase(CliPhase.LOOKING_UP_USAGES, validatedQuery);
    UsageLookupRequest request =
        new UsageLookupRequest(
            validatedQuery,
            maxResults,
            !referencesOnly,
            !definitionsOnly);
    UsageLookupResult result = usagesLookupService.lookup(request);
    progress.completePhase(CliPhase.LOOKING_UP_USAGES, result.elapsed());
    progress.printTimingSummary(
        List.of(new PhaseTiming(CliPhase.LOOKING_UP_USAGES, result.elapsed())), plain);

    if (executionContext.isDebugEnabled()) {
      System.err.printf("resolvedSymbolIds=%s%n", result.resolvedSymbolIds());
    }

    if (result.resolvedSymbolIds().isEmpty()) {
      System.out.println("No symbols matched: " + validatedQuery);
      printMethodHints(validatedQuery, result.methodHints());
      return;
    }

    if (!referencesOnly) {
      printSection("Definitions", result.definitions());
    }
    if (!definitionsOnly) {
      printSection("References", result.references());
    }

    System.out.printf(
        "%n%d definitions, %d references in %d ms%n",
        result.definitions().size(),
        result.references().size(),
        result.elapsed().toMillis());
  }

  private void printMethodHints(String validatedQuery, java.util.List<MethodHint> methodHints) {
    if (methodHints.isEmpty()) {
      return;
    }
    var classMethodQuery = SymbolQueryParser.parseClassMethodQuery(validatedQuery);
    if (classMethodQuery.isEmpty()) {
      return;
    }
    String className = classMethodQuery.get().className();
    System.out.printf("Methods on %s:%n", className);
    for (MethodHint hint : methodHints) {
      if (executionContext.isDebugEnabled()) {
        System.err.printf("  hintSymbolId=%s%n", hint.symbolId());
      }
      System.out.printf("  %s%n", hint.symbolName());
    }
    System.out.printf(
        "Try: andro usages '%s#%s'%n", className, methodHints.getFirst().symbolName());
  }

  private void printSection(String title, java.util.List<UsageSite> sites) {
    System.out.printf("%s (%d):%n", title, sites.size());
    if (sites.isEmpty()) {
      System.out.println("  (none)");
      return;
    }
    for (UsageSite site : sites) {
      String location = locationLabel(site);
      String symbol = site.symbolName() == null || site.symbolName().isBlank() ? "-" : site.symbolName();
      if (executionContext.isDebugEnabled()) {
        System.out.printf("  %-32s  %-20s  %s  %s%n", location, symbol, site.symbolId(), site.path());
      } else {
        System.out.printf("  %-32s  %-20s  %s%n", location, symbol, site.path());
      }
    }
  }

  private static String locationLabel(UsageSite site) {
    if (site.startLine() > 0 && site.endLine() > 0) {
      return site.fileName() + ":" + site.startLine() + "-" + site.endLine();
    }
    return site.fileName();
  }

  private static String validateQuery(String query) {
    if (query == null || query.isBlank()) {
      throw new IllegalArgumentException("Symbol query is required");
    }
    return query.trim();
  }
}
