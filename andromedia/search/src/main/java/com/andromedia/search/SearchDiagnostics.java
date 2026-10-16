package com.andromedia.search;

public record SearchDiagnostics(
    String queryText,
    String workspaceRoot,
    String indexPath,
    Integer bm25Candidates,
    Integer semanticCandidates,
    Boolean semanticSkipped,
    QueryType queryType,
    Double bm25ChannelWeight,
    Double semanticChannelWeight,
    Integer semanticFilteredCount,
    String semanticSkipReason) {

  public SearchDiagnostics(String queryText, String workspaceRoot, String indexPath) {
    this(
        queryText,
        workspaceRoot,
        indexPath,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }
}
