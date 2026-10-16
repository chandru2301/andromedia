package com.andromedia.indexing;

import java.nio.file.Path;
import java.util.Set;

public record IndexingRequest(
    Path rootDirectory,
    Set<String> includedExtensions,
    boolean recreateIndex,
    boolean dryRun,
    boolean embedChunks,
    IndexingProgressListener progressListener) {

  public IndexingRequest(
      Path rootDirectory,
      Set<String> includedExtensions,
      boolean recreateIndex,
      boolean dryRun,
      boolean embedChunks) {
    this(
        rootDirectory,
        includedExtensions,
        recreateIndex,
        dryRun,
        embedChunks,
        IndexingProgressListener.NOOP);
  }
}
