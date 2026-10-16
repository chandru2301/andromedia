package com.andromedia.search;

import java.util.Set;

public record ImpactLookupRequest(
    String symbolQuery,
    TraversalDirection direction,
    int maxDepth,
    int maxResults,
    boolean grouped,
    Set<ImpactCategory> categoryFilters) {

  public ImpactLookupRequest(
      String symbolQuery,
      TraversalDirection direction,
      int maxDepth,
      int maxResults,
      boolean grouped) {
    this(symbolQuery, direction, maxDepth, maxResults, grouped, Set.of());
  }

  public ImpactLookupRequest(String symbolQuery, int maxDepth, int maxResultsPerCategory) {
    this(symbolQuery, TraversalDirection.REVERSE, maxDepth, maxResultsPerCategory, false, Set.of());
  }
}
