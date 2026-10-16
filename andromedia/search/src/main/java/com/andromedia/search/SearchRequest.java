package com.andromedia.search;

public record SearchRequest(String query, int maxResults, SearchMode mode) {

  public SearchRequest(String query, int maxResults) {
    this(query, maxResults, SearchMode.BM25);
  }
}
