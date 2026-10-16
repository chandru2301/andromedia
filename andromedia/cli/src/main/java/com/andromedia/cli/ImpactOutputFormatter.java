package com.andromedia.cli;

import com.andromedia.search.ImpactCategory;
import com.andromedia.search.ImpactLookupResult;
import com.andromedia.search.ImpactSite;
import com.andromedia.search.MethodHint;
import com.andromedia.search.SymbolQueryParser;
import com.andromedia.search.TraversalDirection;
import java.util.List;
import java.util.Map;

public final class ImpactOutputFormatter {

  private ImpactOutputFormatter() {}

  public static void printResult(
      ImpactLookupResult result,
      String validatedQuery,
      int maxDepth,
      boolean debugEnabled) {
    if (result.seedSymbolIds().isEmpty()) {
      System.out.println("No symbols matched: " + validatedQuery);
      printMethodHints(validatedQuery, result.methodHints());
      return;
    }

    String directionLabel =
        result.direction() == TraversalDirection.FORWARD ? "forward" : "reverse";
    System.out.printf(
        "Impact for %s (%s, depth %d)%n%n",
        String.join(", ", result.seedSymbolIds()), directionLabel, maxDepth);

    if (result.direction() == TraversalDirection.FORWARD) {
      printFlatSection("Dependencies", result.affectedSymbols(), debugEnabled);
    } else {
      printFlatSection("Affected symbols", result.affectedSymbols(), debugEnabled);
    }

    if (!result.impactedByCategory().isEmpty()) {
      printGroupedSections(result.impactedByCategory(), debugEnabled);
    }

    System.out.printf("Completed in %d ms%n", result.elapsed().toMillis());
  }

  private static void printFlatSection(String title, List<ImpactSite> sites, boolean debugEnabled) {
    System.out.printf("%s (%d):%n", title, sites.size());
    if (sites.isEmpty()) {
      System.out.println("  (none)");
      System.out.println();
      return;
    }
    for (ImpactSite site : sites) {
      printSite(site, debugEnabled);
    }
    System.out.println();
  }

  private static void printGroupedSections(
      Map<ImpactCategory, List<ImpactSite>> grouped, boolean debugEnabled) {
    for (ImpactCategory category : ImpactCategory.values()) {
      List<ImpactSite> sites = grouped.getOrDefault(category, List.of());
      if (sites.isEmpty()) {
        continue;
      }
      System.out.printf("%s (%d)%n", category.label(), sites.size());
      for (ImpactSite site : sites) {
        printSite(site, debugEnabled);
      }
      System.out.println();
    }
  }

  private static void printSite(ImpactSite site, boolean debugEnabled) {
    String label = formatSymbolLabel(site);
    String location = formatLocation(site);
    if (debugEnabled) {
      System.out.printf(
          "  %-40s  %-24s  %s  %s%n", label, location, site.symbolId(), site.path());
    } else {
      System.out.printf("  %-40s  %-24s  [hop %d]%n", label, location, site.hop());
    }
  }

  static String formatSymbolLabel(ImpactSite site) {
    if (site.symbolId().contains("#")) {
      int hash = site.symbolId().indexOf('#');
      String qualified = site.symbolId().substring(site.symbolId().indexOf(':') + 1, hash);
      int lastDot = qualified.lastIndexOf('.');
      String className = lastDot >= 0 ? qualified.substring(lastDot + 1) : qualified;
      return className + "#" + site.symbolName();
    }
    return site.symbolName();
  }

  private static String formatLocation(ImpactSite site) {
    if (site.startLine() > 0) {
      return site.path() + ":" + site.startLine();
    }
    return site.path();
  }

  private static void printMethodHints(String validatedQuery, List<MethodHint> methodHints) {
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
      System.out.printf("  %s%n", hint.symbolName());
    }
    System.out.printf(
        "Try: andro impact '%s#%s'%n", className, methodHints.getFirst().symbolName());
  }
}
