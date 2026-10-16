package com.andromedia.ingestion.linear;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Set;

public final class LinearIssueFilter {

  private static final Set<String> CANCELLED_STATES =
      Set.of("cancelled", "canceled", "duplicate");

  private LinearIssueFilter() {}

  public static boolean shouldIndex(LinearIssue issue, int lookbackDays, Set<String> teamTokens) {
    if (issue == null || issue.identifier() == null || issue.identifier().isBlank()) {
      return false;
    }
    if (issue.title() == null || issue.title().isBlank()) {
      return false;
    }
    if (teamTokens != null && !teamTokens.isEmpty()) {
      if (!matchesTeam(issue, teamTokens)) {
        return false;
      }
    }
    String state = issue.state() == null ? "" : issue.state().trim().toLowerCase(Locale.ROOT);
    if (!CANCELLED_STATES.contains(state)) {
      return true;
    }
    Instant cutoff = Instant.now().minus(Math.max(1, lookbackDays), ChronoUnit.DAYS);
    Instant updated = issue.updatedAt() == null ? Instant.EPOCH : issue.updatedAt();
    return !updated.isBefore(cutoff);
  }

  static boolean matchesTeam(LinearIssue issue, Set<String> teamTokens) {
    String teamKey = issue.team() == null ? "" : issue.team().trim();
    String teamName = issue.teamName() == null ? "" : issue.teamName().trim();
    for (String token : teamTokens) {
      if (token == null || token.isBlank()) {
        continue;
      }
      String normalized = token.trim();
      if (teamKey.equalsIgnoreCase(normalized) || teamName.equalsIgnoreCase(normalized)) {
        return true;
      }
    }
    return false;
  }
}
