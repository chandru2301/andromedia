package com.andromedia.search;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public record ImpactLookupResult(
    List<String> seedSymbolIds,
    TraversalDirection direction,
    List<ImpactSite> affectedSymbols,
    Map<ImpactCategory, List<ImpactSite>> impactedByCategory,
    Duration elapsed,
    List<MethodHint> methodHints) {

  public ImpactLookupResult(
      List<String> seedSymbolIds,
      TraversalDirection direction,
      List<ImpactSite> affectedSymbols,
      Map<ImpactCategory, List<ImpactSite>> impactedByCategory,
      Duration elapsed) {
    this(seedSymbolIds, direction, affectedSymbols, impactedByCategory, elapsed, List.of());
  }
}
