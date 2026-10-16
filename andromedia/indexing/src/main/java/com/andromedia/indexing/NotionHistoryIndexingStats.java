package com.andromedia.indexing;

import java.time.Instant;

public record NotionHistoryIndexingStats(
    int pagesIndexed,
    int chunksIndexed,
    int chunksEmbedded,
    int embeddingApiCalls,
    Instant startedAt,
    Instant finishedAt) {}
