package com.andromedia.common;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class IndexPathHolder {

  private static final ThreadLocal<List<Path>> DIRECT_INDEXES = new ThreadLocal<>();

  private IndexPathHolder() {}

  public static void setDirectIndexPath(Path path) {
    if (path == null) {
      DIRECT_INDEXES.remove();
      return;
    }
    DIRECT_INDEXES.set(List.of(path));
  }

  public static void setDirectIndexPaths(List<Path> paths) {
    if (paths == null || paths.isEmpty()) {
      DIRECT_INDEXES.remove();
      return;
    }
    List<Path> copy = new ArrayList<>();
    for (Path path : paths) {
      if (path != null) {
        copy.add(path);
      }
    }
    if (copy.isEmpty()) {
      DIRECT_INDEXES.remove();
      return;
    }
    DIRECT_INDEXES.set(List.copyOf(copy));
  }

  public static void clear() {
    DIRECT_INDEXES.remove();
  }

  /** First (or only) direct index path — used by writers and single-path resolvers. */
  public static Optional<Path> directIndexPath() {
    List<Path> paths = DIRECT_INDEXES.get();
    if (paths == null || paths.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(paths.getFirst());
  }

  /** All direct index paths for hybrid reads (immutable). Empty when unset. */
  public static List<Path> directIndexPaths() {
    List<Path> paths = DIRECT_INDEXES.get();
    if (paths == null || paths.isEmpty()) {
      return List.of();
    }
    return paths;
  }
}
