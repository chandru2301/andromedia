package com.andromedia.ingestion.jira;

import java.util.ArrayList;
import java.util.List;

/** One chunkable unit per Jira issue (title + description + comments). */
public final class JiraIssueChunker {

  private JiraIssueChunker() {}

  public static List<JiraIssue> chunk(List<JiraIssue> issues) {
    if (issues == null || issues.isEmpty()) {
      return List.of();
    }
    List<JiraIssue> kept = new ArrayList<>();
    for (JiraIssue issue : issues) {
      if (issue == null || issue.key().isBlank()) {
        continue;
      }
      kept.add(issue);
    }
    return List.copyOf(kept);
  }
}
