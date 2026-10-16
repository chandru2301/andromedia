package com.andromedia.ingestion.slack;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record SlackIngestionOptions(
    Instant oldest,
    List<String> channels,
    Map<String, Instant> channelCursors,
    int maxMessagesPerChannel,
    boolean skipBotMessages) {

  public SlackIngestionOptions {
    channels = channels == null ? List.of() : List.copyOf(channels);
    channelCursors = channelCursors == null ? Map.of() : Map.copyOf(channelCursors);
    if (maxMessagesPerChannel <= 0) {
      maxMessagesPerChannel = 5_000;
    }
  }

  public static SlackIngestionOptions defaults() {
    return new SlackIngestionOptions(null, List.of(), Map.of(), 5_000, true);
  }
}
