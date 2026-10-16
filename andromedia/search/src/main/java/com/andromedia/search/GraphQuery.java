package com.andromedia.search;

import java.util.Collections;
import java.util.Set;

public record GraphQuery(
    String symbolQuery,
    TraversalDirection direction,
    Set<ImpactCategory> categoryFilters,
    int maxDepth,
    int maxResults,
    boolean grouped,
    boolean explain) {

  public GraphQuery {
    categoryFilters =
        categoryFilters == null || categoryFilters.isEmpty()
            ? Set.of()
            : Set.copyOf(categoryFilters);
    direction = direction == null ? TraversalDirection.REVERSE : direction;
  }

  public static GraphQuery reverse(String symbolQuery) {
    return new GraphQuery(symbolQuery, TraversalDirection.REVERSE, Set.of(), 5, 20, false, false);
  }

  public ImpactLookupRequest toLookupRequest() {
    return new ImpactLookupRequest(
        symbolQuery, direction, maxDepth, maxResults, grouped, categoryFilters);
  }
}
