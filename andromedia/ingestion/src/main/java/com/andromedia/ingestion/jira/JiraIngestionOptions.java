package com.andromedia.ingestion.jira;

import java.util.List;

public record JiraIngestionOptions(List<String> projectKeys, int lookbackDays) {

  public JiraIngestionOptions {
    projectKeys = projectKeys == null ? List.of() : List.copyOf(projectKeys);
    lookbackDays = lookbackDays <= 0 ? 90 : lookbackDays;
  }
}
