package com.andromedia.indexing;

import java.time.Duration;
import java.time.Instant;

public record GitHistoryIndexingStats(
    long commitsIndexed,
    long chunksIndexed,
    long chunksEmbedded,
    long embeddingApiCalls,
    long symbolsExtracted,
    Instant startedAt,
    Instant finishedAt) {

  public Duration duration() {
    return Duration.between(startedAt, finishedAt);
  }
}
