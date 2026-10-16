package com.andromedia.ingestion.linear;

import java.util.List;

public record LinearIngestionOptions(List<String> teamKeys, int lookbackDays) {

  public LinearIngestionOptions {
    teamKeys = teamKeys == null ? List.of() : List.copyOf(teamKeys);
    lookbackDays = lookbackDays <= 0 ? 90 : lookbackDays;
  }
}
