package com.andromedia.llm;

public record ContextBlock(
    String chunkId,
    String path,
    String label,
    String content,
    String fileName,
    String className,
    String methodName,
    int startLine,
    int endLine) {

  public ContextBlock(String chunkId, String path, String label, String content) {
    this(chunkId, path, label, content, null, null, null, 0, 0);
  }
}
