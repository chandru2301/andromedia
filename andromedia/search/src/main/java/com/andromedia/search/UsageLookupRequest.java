package com.andromedia.search;

public record UsageLookupRequest(
    String symbolQuery, int maxResults, boolean includeDefinitions, boolean includeReferences) {

  public UsageLookupRequest(String symbolQuery, int maxResults) {
    this(symbolQuery, maxResults, true, true);
  }
}
