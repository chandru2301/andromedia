package com.andromedia.indexing;

import com.andromedia.common.JiraChunk;
import java.nio.file.Path;
import java.util.List;

public record JiraHistoryIndexingRequest(
    List<JiraChunk> chunks, boolean embedChunks, boolean recreateIndex, Path workspaceRoot) {

  public JiraHistoryIndexingRequest(List<JiraChunk> chunks, boolean embedChunks, boolean recreateIndex) {
    this(chunks, embedChunks, recreateIndex, null);
  }

  public JiraHistoryIndexingRequest {
    chunks = chunks == null ? List.of() : List.copyOf(chunks);
  }
}
