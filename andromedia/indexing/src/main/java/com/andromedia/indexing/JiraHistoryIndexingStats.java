package com.andromedia.indexing;

import java.time.Instant;

public record JiraHistoryIndexingStats(
    int issuesIndexed,
    int chunksIndexed,
    int chunksEmbedded,
    int embeddingApiCalls,
    Instant startedAt,
    Instant finishedAt) {}
