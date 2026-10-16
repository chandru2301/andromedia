package com.andromedia.indexing;

public interface IndexingProgressListener {

  IndexingProgressListener NOOP = new IndexingProgressListener() {};

  default void onProgress(
      long filesVisited, long filesIndexed, long chunksIndexed, long embeddingApiCalls) {}

  default void onEmbeddingBatch(long embeddingApiCalls, long chunksEmbedded) {}
}
