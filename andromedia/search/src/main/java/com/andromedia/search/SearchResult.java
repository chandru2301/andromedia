package com.andromedia.search;

import java.time.Duration;
import java.util.List;

public record SearchResult(
    List<SearchHit> hits, long totalHits, Duration elapsed, SearchDiagnostics diagnostics) {

  public SearchResult(List<SearchHit> hits, long totalHits, Duration elapsed) {
    this(hits, totalHits, elapsed, null);
  }
}
