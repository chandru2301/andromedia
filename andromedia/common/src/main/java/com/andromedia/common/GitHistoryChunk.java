package com.andromedia.common;

import java.time.Instant;
import java.util.List;

public record GitHistoryChunk(
    String chunkId,
    String content,
    String commitSha,
    Instant committedAt,
    String author,
    List<String> filePaths,
    StructuralMetadata metadata) {

  public GitHistoryChunk {
    filePaths = filePaths == null ? List.of() : List.copyOf(filePaths);
    metadata = metadata == null ? StructuralMetadata.empty() : metadata;
  }
}
