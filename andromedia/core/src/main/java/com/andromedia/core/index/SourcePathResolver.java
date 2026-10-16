package com.andromedia.core.index;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class SourcePathResolver {

  private SourcePathResolver() {}

  public static void validateLocalPath(String localPath) throws IOException {
    if (localPath == null || localPath.isBlank()) {
      throw new IllegalArgumentException("localPath is required");
    }
    Path path = Path.of(localPath).toAbsolutePath().normalize();
    if (!Files.exists(path)) {
      throw new IllegalArgumentException("Path does not exist: " + path);
    }
    if (!Files.isDirectory(path)) {
      throw new IllegalArgumentException("Path is not a directory: " + path);
    }
    if (!Files.isReadable(path)) {
      throw new IllegalArgumentException("Path is not readable: " + path);
    }
  }

  public static Path resolveSubpath(Path root, String subpath) throws IOException {
    Path normalizedRoot = root.toAbsolutePath().normalize();
    if (subpath == null || subpath.isBlank()) {
      return normalizedRoot;
    }
    Path resolved = normalizedRoot.resolve(subpath).normalize();
    if (!resolved.startsWith(normalizedRoot)) {
      throw new IllegalArgumentException("subpath escapes repository root");
    }
    if (!Files.exists(resolved)) {
      throw new IllegalArgumentException("subpath does not exist: " + subpath);
    }
    if (!Files.isDirectory(resolved)) {
      throw new IllegalArgumentException("subpath is not a directory: " + subpath);
    }
    return resolved;
  }
}
