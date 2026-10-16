package com.andromedia.common;

public record CodeChunk(
    String chunkId,
    String content,
    String path,
    String fileName,
    String extension,
    String language,
    String className,
    String methodName,
    ChunkUnitType unitType,
    int startLine,
    int endLine,
    boolean oversized,
    StructuralMetadata metadata) {

  public CodeChunk {
    metadata = metadata == null ? StructuralMetadata.empty() : metadata;
  }

  public static CodeChunk withMetadata(CodeChunk base, StructuralMetadata metadata) {
    return new CodeChunk(
        base.chunkId(),
        base.content(),
        base.path(),
        base.fileName(),
        base.extension(),
        base.language(),
        base.className(),
        base.methodName(),
        base.unitType(),
        base.startLine(),
        base.endLine(),
        base.oversized(),
        metadata != null ? metadata : StructuralMetadata.empty());
  }
}
