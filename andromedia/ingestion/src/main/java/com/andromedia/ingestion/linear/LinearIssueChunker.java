package com.andromedia.ingestion.linear;

import java.util.ArrayList;
import java.util.List;

public final class LinearIssueChunker {

  private LinearIssueChunker() {}

  public static List<LinearIssue> chunk(List<LinearIssue> issues) {
    if (issues == null || issues.isEmpty()) {
      return List.of();
    }
    List<LinearIssue> kept = new ArrayList<>();
    for (LinearIssue issue : issues) {
      if (issue == null || issue.identifier().isBlank()) {
        continue;
      }
      kept.add(issue);
    }
    return List.copyOf(kept);
  }
}
