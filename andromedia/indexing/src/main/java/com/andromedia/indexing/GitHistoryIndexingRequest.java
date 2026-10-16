package com.andromedia.indexing;

import com.andromedia.common.GitHistoryChunk;
import java.nio.file.Path;
import java.util.List;

public record GitHistoryIndexingRequest(
    List<GitHistoryChunk> chunks,
    boolean embedChunks,
    boolean recreateIndex,
    Path workspaceRoot) {

  public GitHistoryIndexingRequest {
    chunks = chunks == null ? List.of() : List.copyOf(chunks);
  }

  public GitHistoryIndexingRequest(List<GitHistoryChunk> chunks, boolean embedChunks, boolean recreateIndex) {
    this(chunks, embedChunks, recreateIndex, null);
  }
}
