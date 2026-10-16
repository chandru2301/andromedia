package com.andromedia.indexing;

import com.andromedia.common.GoogleChatChunk;
import java.nio.file.Path;
import java.util.List;

public record GoogleChatHistoryIndexingRequest(
    List<GoogleChatChunk> chunks,
    boolean embedChunks,
    boolean recreateIndex,
    Path workspaceRoot) {

  public GoogleChatHistoryIndexingRequest {
    chunks = chunks == null ? List.of() : List.copyOf(chunks);
  }

  public GoogleChatHistoryIndexingRequest(
      List<GoogleChatChunk> chunks, boolean embedChunks, boolean recreateIndex) {
    this(chunks, embedChunks, recreateIndex, null);
  }
}
