package com.andromedia.indexing;

import java.time.Instant;

public record ZohoHistoryIndexingStats(
    int chunksIndexed,
    int chunksEmbedded,
    int embeddingApiCalls,
    int dealsSkipped,
    Instant startedAt,
    Instant finishedAt) {}
