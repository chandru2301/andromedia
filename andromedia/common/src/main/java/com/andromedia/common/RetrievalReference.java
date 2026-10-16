package com.andromedia.common;

import java.util.Optional;

public record RetrievalReference(
    String chunkId,
    String path,
    String fileName,
    String className,
    String methodName,
    ChunkUnitType unitType,
    int startLine,
    int endLine,
    Optional<String> sourceType,
    Optional<String> indexedContent) {

  public RetrievalReference {
    sourceType = sourceType == null ? Optional.empty() : sourceType;
    indexedContent = indexedContent == null ? Optional.empty() : indexedContent;
  }

  public RetrievalReference(
      String chunkId,
      String path,
      String fileName,
      String className,
      String methodName,
      ChunkUnitType unitType,
      int startLine,
      int endLine) {
    this(
        chunkId,
        path,
        fileName,
        className,
        methodName,
        unitType,
        startLine,
        endLine,
        Optional.empty(),
        Optional.empty());
  }

  public boolean isGitHistory() {
    return sourceType.orElse("").equals(IndexConstants.SOURCE_TYPE_GIT)
        || unitType == ChunkUnitType.HISTORY;
  }
}
