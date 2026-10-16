package com.andromedia.ingestion.git;

import java.time.Instant;

public record GitIngestionOptions(Instant since, int maxCommits, String branch, String stopAtCommitSha) {

  public GitIngestionOptions {
    maxCommits = maxCommits <= 0 ? 1000 : maxCommits;
  }

  public static GitIngestionOptions defaults() {
    return new GitIngestionOptions(null, 1000, null, null);
  }

  public GitIngestionOptions withStopAtCommitSha(String commitSha) {
    return new GitIngestionOptions(since, maxCommits, branch, commitSha);
  }
}
