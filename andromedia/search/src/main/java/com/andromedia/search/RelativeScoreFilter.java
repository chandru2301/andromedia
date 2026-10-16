package com.andromedia.search;

import java.util.ArrayList;
import java.util.List;

/**
 * Drops hits that are far weaker than the top result so low-relevance Zoho KNN leftovers do not
 * pad every top-K (and activity-log) for non-CRM questions.
 */
final class RelativeScoreFilter {

  /** Keep hits scoring at least this fraction of the top hit's score. */
  static final double DEFAULT_RELATIVE_FLOOR = 0.08;

  private RelativeScoreFilter() {}

  static List<SearchHit> apply(List<SearchHit> ranked, int maxResults) {
    return apply(ranked, maxResults, DEFAULT_RELATIVE_FLOOR);
  }

  static List<SearchHit> apply(List<SearchHit> ranked, int maxResults, double relativeFloor) {
    int limit = Math.max(1, maxResults);
    if (ranked == null || ranked.isEmpty()) {
      return List.of();
    }
    float top = ranked.getFirst().score();
    if (!(top > 0f) || relativeFloor <= 0) {
      return ranked.stream().limit(limit).toList();
    }
    double floor = top * relativeFloor;
    List<SearchHit> kept = new ArrayList<>();
    for (SearchHit hit : ranked) {
      if (kept.size() >= limit) {
        break;
      }
      if (hit.score() >= floor) {
        kept.add(hit);
      }
    }
    if (kept.isEmpty()) {
      return ranked.stream().limit(Math.min(limit, 3)).toList();
    }
    return List.copyOf(kept);
  }
}
