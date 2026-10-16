package com.andromedia.common;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public record GitChangeEvent(
    String commitSha,
    Instant committedAt,
    String author,
    String subject,
    String body,
    List<String> parentShas,
    List<ChangedFile> changedFiles,
    List<ChangedSymbol> changedSymbols,
    Optional<PullRequestMetadata> pullRequest) {

  public GitChangeEvent {
    parentShas = parentShas == null ? List.of() : List.copyOf(parentShas);
    changedFiles = changedFiles == null ? List.of() : List.copyOf(changedFiles);
    changedSymbols = changedSymbols == null ? List.of() : List.copyOf(changedSymbols);
    pullRequest = pullRequest == null ? Optional.empty() : pullRequest;
  }
}
