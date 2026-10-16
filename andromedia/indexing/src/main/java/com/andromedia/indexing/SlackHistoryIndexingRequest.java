package com.andromedia.indexing;

import com.andromedia.common.SlackChunk;
import java.nio.file.Path;
import java.util.List;

public record SlackHistoryIndexingRequest(
    List<SlackChunk> chunks,
    boolean embedChunks,
    boolean recreateIndex,
    Path workspaceRoot) {

  public SlackHistoryIndexingRequest {
    chunks = chunks == null ? List.of() : List.copyOf(chunks);
  }

  public SlackHistoryIndexingRequest(
      List<SlackChunk> chunks, boolean embedChunks, boolean recreateIndex) {
    this(chunks, embedChunks, recreateIndex, null);
  }
}
