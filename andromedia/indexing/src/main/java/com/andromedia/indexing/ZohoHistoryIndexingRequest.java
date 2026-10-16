package com.andromedia.indexing;

import com.andromedia.common.ZohoDealContextChunk;
import java.nio.file.Path;
import java.util.List;

public record ZohoHistoryIndexingRequest(
    List<ZohoDealContextChunk> chunks,
    boolean embedChunks,
    boolean recreateIndex,
    Path workspaceRoot,
    boolean fullRefresh,
    List<String> deletedChunkIds) {

  public ZohoHistoryIndexingRequest(
      List<ZohoDealContextChunk> chunks, boolean embedChunks, boolean recreateIndex) {
    this(chunks, embedChunks, recreateIndex, null, true, List.of());
  }

  public ZohoHistoryIndexingRequest(
      List<ZohoDealContextChunk> chunks,
      boolean embedChunks,
      boolean recreateIndex,
      Path workspaceRoot) {
    this(chunks, embedChunks, recreateIndex, workspaceRoot, true, List.of());
  }

  public ZohoHistoryIndexingRequest {
    chunks = chunks == null ? List.of() : List.copyOf(chunks);
    deletedChunkIds = deletedChunkIds == null ? List.of() : List.copyOf(deletedChunkIds);
  }
}
