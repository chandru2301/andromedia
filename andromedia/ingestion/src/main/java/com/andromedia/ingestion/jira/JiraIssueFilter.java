package com.andromedia.ingestion.jira;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Set;

public final class JiraIssueFilter {

  private static final Set<String> CLOSED_STATUSES =
      Set.of("closed", "cancelled", "canceled", "done", "resolved");

  private JiraIssueFilter() {}

  public static boolean shouldIndex(JiraIssue issue, int lookbackDays) {
    if (issue == null || issue.key() == null || issue.key().isBlank()) {
      return false;
    }
    if (issue.summary() == null || issue.summary().isBlank()) {
      return false;
    }
    String status = issue.status() == null ? "" : issue.status().trim().toLowerCase(Locale.ROOT);
    if (!CLOSED_STATUSES.contains(status)) {
      return true;
    }
    Instant cutoff = Instant.now().minus(Math.max(1, lookbackDays), ChronoUnit.DAYS);
    Instant updated = issue.updated() == null ? Instant.EPOCH : issue.updated();
    return !updated.isBefore(cutoff);
  }
}
