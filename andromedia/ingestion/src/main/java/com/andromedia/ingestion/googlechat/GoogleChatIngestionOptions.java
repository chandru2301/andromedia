package com.andromedia.ingestion.googlechat;

import java.time.Instant;
import java.util.List;

public record GoogleChatIngestionOptions(Instant oldest, List<String> spaces, int maxMessagesPerSpace) {

  public GoogleChatIngestionOptions {
    spaces = spaces == null ? List.of() : List.copyOf(spaces);
    if (maxMessagesPerSpace <= 0) {
      maxMessagesPerSpace = 5_000;
    }
  }

  public static GoogleChatIngestionOptions defaults() {
    return new GoogleChatIngestionOptions(null, List.of(), 5_000);
  }
}
