package com.andromedia.indexing;

import com.andromedia.common.NotionChunk;
import java.nio.file.Path;
import java.util.List;

public record NotionHistoryIndexingRequest(
    List<NotionChunk> chunks, boolean embedChunks, boolean recreateIndex, Path workspaceRoot) {

  public NotionHistoryIndexingRequest(List<NotionChunk> chunks, boolean embedChunks, boolean recreateIndex) {
    this(chunks, embedChunks, recreateIndex, null);
  }

  public NotionHistoryIndexingRequest {
    chunks = chunks == null ? List.of() : List.copyOf(chunks);
  }
}
