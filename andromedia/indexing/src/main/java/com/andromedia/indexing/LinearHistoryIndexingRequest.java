package com.andromedia.indexing;

import com.andromedia.common.LinearChunk;
import java.nio.file.Path;
import java.util.List;

public record LinearHistoryIndexingRequest(
    List<LinearChunk> chunks, boolean embedChunks, boolean recreateIndex, Path workspaceRoot) {

  public LinearHistoryIndexingRequest(
      List<LinearChunk> chunks, boolean embedChunks, boolean recreateIndex) {
    this(chunks, embedChunks, recreateIndex, null);
  }

  public LinearHistoryIndexingRequest {
    chunks = chunks == null ? List.of() : List.copyOf(chunks);
  }
}
