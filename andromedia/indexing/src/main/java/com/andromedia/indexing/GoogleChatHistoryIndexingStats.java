package com.andromedia.indexing;

import java.time.Duration;
import java.time.Instant;

public record GoogleChatHistoryIndexingStats(
    long threadsIndexed,
    long chunksIndexed,
    long chunksEmbedded,
    long embeddingApiCalls,
    Instant startedAt,
    Instant finishedAt) {

  public Duration duration() {
    return Duration.between(startedAt, finishedAt);
  }
}
