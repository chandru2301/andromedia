package com.andromedia.ingestion.notion;

import java.util.List;
import java.util.Set;

public record NotionIngestionOptions(List<String> pageIds, List<String> databaseIds) {

  public NotionIngestionOptions {
    pageIds = pageIds == null ? List.of() : List.copyOf(pageIds);
    databaseIds = databaseIds == null ? List.of() : List.copyOf(databaseIds);
  }

  public Set<String> configuredPageIdSet() {
    return Set.copyOf(pageIds);
  }
}
