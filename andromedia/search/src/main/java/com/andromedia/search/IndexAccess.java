package com.andromedia.search;

import com.andromedia.common.IndexConstants;
import com.andromedia.common.IndexPathHolder;
import com.andromedia.common.IndexPathResolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.MultiReader;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

public final class IndexAccess {

  private IndexAccess() {}

  static Path resolveSearchStartPath() {
    String userDirectory = System.getProperty("user.dir", ".");
    return Path.of(userDirectory).toAbsolutePath().normalize();
  }

  /** Primary/single path for diagnostics and callers that need one directory. */
  static Path resolveProjectIndexPath(String configuredIndexPath) {
    List<Path> paths = resolveProjectIndexPaths(configuredIndexPath);
    return paths.getFirst();
  }

  /**
   * All index directories for the current search context. Prefers {@link IndexPathHolder} multi-path
   * list when set; otherwise falls back to the configured project index.
   */
  static List<Path> resolveProjectIndexPaths(String configuredIndexPath) {
    List<Path> held = IndexPathHolder.directIndexPaths();
    if (!held.isEmpty()) {
      return held;
    }
    Path searchStartPath = resolveSearchStartPath();
    Path workspaceRoot = IndexPathResolver.resolveWorkspaceRoot(searchStartPath);
    return List.of(IndexPathResolver.resolveProjectIndexPath(configuredIndexPath, workspaceRoot));
  }

  static Path resolveWorkspaceRoot() {
    return IndexPathResolver.resolveWorkspaceRoot(resolveSearchStartPath());
  }

  public static void validateIndexExists(Path indexDirectoryPath) {
    if (!indexExists(indexDirectoryPath)) {
      throw new SearchException(
          "Index not found at "
              + indexDirectoryPath
              + ". Run `andro index <path>` first.");
    }
  }

  public static void validateAnyIndexExists(List<Path> indexDirectoryPaths) {
    if (indexDirectoryPaths == null || indexDirectoryPaths.isEmpty()) {
      throw new SearchException("No index paths provided. Run `andro index <path>` first.");
    }
    for (Path path : indexDirectoryPaths) {
      if (indexExists(path)) {
        return;
      }
    }
    throw new SearchException(
        "Index not found at any of "
            + indexDirectoryPaths
            + ". Run `andro index <path>` first.");
  }

  static boolean indexExists(Path indexDirectoryPath) {
    if (indexDirectoryPath == null || !Files.isDirectory(indexDirectoryPath)) {
      return false;
    }
    try (Directory directory = FSDirectory.open(indexDirectoryPath)) {
      return DirectoryReader.indexExists(directory);
    } catch (IOException ex) {
      throw new SearchException("Failed to open index at: " + indexDirectoryPath, ex);
    }
  }

  static boolean hasEmbeddingField(Path indexDirectoryPath) {
    if (!indexExists(indexDirectoryPath)) {
      return false;
    }
    try (OpenedIndex opened = openIndex(List.of(indexDirectoryPath))) {
      for (LeafReaderContext leaf : opened.reader().leaves()) {
        if (leaf.reader().getFieldInfos().fieldInfo(IndexConstants.FIELD_EMBEDDING) != null) {
          return true;
        }
      }
      return false;
    } catch (IOException ex) {
      throw new SearchException("Failed to inspect index at: " + indexDirectoryPath, ex);
    }
  }

  static boolean hasEmbeddingField(List<Path> indexDirectoryPaths) {
    if (indexDirectoryPaths == null) {
      return false;
    }
    for (Path path : indexDirectoryPaths) {
      if (hasEmbeddingField(path)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether embeddings exist across the resolved index directories. {@link EmbeddingCoverage#PARTIAL}
   * means some paths have vectors and others do not (e.g. Zoho sidecar embedded, Notion base index
   * not) — hybrid search must not let the embedded channel drown BM25-only sources.
   */
  static EmbeddingCoverage embeddingCoverage(List<Path> indexDirectoryPaths) {
    if (indexDirectoryPaths == null || indexDirectoryPaths.isEmpty()) {
      return EmbeddingCoverage.NONE;
    }
    int existing = 0;
    int withEmbeddings = 0;
    for (Path path : indexDirectoryPaths) {
      if (!indexExists(path)) {
        continue;
      }
      existing++;
      if (hasEmbeddingField(path)) {
        withEmbeddings++;
      }
    }
    if (existing == 0 || withEmbeddings == 0) {
      return EmbeddingCoverage.NONE;
    }
    if (withEmbeddings == existing) {
      return EmbeddingCoverage.FULL;
    }
    return EmbeddingCoverage.PARTIAL;
  }

  enum EmbeddingCoverage {
    NONE,
    PARTIAL,
    FULL
  }

  /**
   * Opens an {@link IndexReader} over one or more Lucene directories. Non-existent paths are
   * skipped; at least one must exist.
   */
  public static OpenedIndex openIndex(List<Path> indexDirectoryPaths) throws IOException {
    if (indexDirectoryPaths == null || indexDirectoryPaths.isEmpty()) {
      throw new SearchException("No index paths provided");
    }
    List<Directory> directories = new ArrayList<>();
    List<DirectoryReader> readers = new ArrayList<>();
    try {
      for (Path path : indexDirectoryPaths) {
        if (!indexExists(path)) {
          continue;
        }
        Directory directory = FSDirectory.open(path);
        directories.add(directory);
        readers.add(DirectoryReader.open(directory));
      }
      if (readers.isEmpty()) {
        throw new SearchException(
            "Index not found at any of "
                + indexDirectoryPaths
                + ". Run `andro index <path>` first.");
      }
      IndexReader combined =
          readers.size() == 1
              ? readers.getFirst()
              : new MultiReader(readers.toArray(DirectoryReader[]::new));
      return new OpenedIndex(directories, combined, List.copyOf(indexDirectoryPaths));
    } catch (IOException | RuntimeException ex) {
      for (DirectoryReader reader : readers) {
        try {
          reader.close();
        } catch (IOException ignored) {
          // best-effort cleanup
        }
      }
      for (Directory directory : directories) {
        try {
          directory.close();
        } catch (IOException ignored) {
          // best-effort cleanup
        }
      }
      throw ex;
    }
  }

  public static final class OpenedIndex implements AutoCloseable {
    private final List<Directory> directories;
    private final IndexReader reader;
    private final List<Path> paths;

    OpenedIndex(List<Directory> directories, IndexReader reader, List<Path> paths) {
      this.directories = directories;
      this.reader = reader;
      this.paths = paths;
    }

    public IndexReader reader() {
      return reader;
    }

    public List<Path> paths() {
      return paths;
    }

    public Path primaryPath() {
      return paths.getFirst();
    }

    @Override
    public void close() throws IOException {
      IOException first = null;
      try {
        reader.close();
      } catch (IOException ex) {
        first = ex;
      }
      for (Directory directory : directories) {
        try {
          directory.close();
        } catch (IOException ex) {
          if (first == null) {
            first = ex;
          } else {
            first.addSuppressed(ex);
          }
        }
      }
      if (first != null) {
        throw first;
      }
    }
  }
}
