package com.andromedia.core.index;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

/**
 * Discovers projects the API is allowed to index.
 *
 * <p>Only directories under the read-only workspace mount are offered. The API
 * deliberately cannot index an arbitrary host path: it runs in a container, and
 * letting a request name any path would turn "index my project" into "read
 * anything this process can see".
 *
 * <p>Detection is marker-based rather than exhaustive — a directory qualifies if
 * it looks like a repository or a package root. That keeps listing cheap (one
 * shallow scan) and avoids offering directories that would produce an empty
 * index, such as {@code node_modules} or a plain folder of notes.
 */
@Service
public class WorkspaceCandidateService {

  /** Directory the workspace is mounted at; overridable for tests. */
  private static final String DEFAULT_ROOT = "/workspace";

  /** Markers that make a directory worth indexing, newest-ecosystem first. */
  private static final Set<String> PROJECT_MARKERS =
      Set.of(
          ".git",
          "package.json",
          "pom.xml",
          "build.gradle",
          "build.gradle.kts",
          "settings.gradle",
          "pyproject.toml",
          "requirements.txt",
          "setup.py",
          "go.mod",
          "Cargo.toml",
          "composer.json",
          "Gemfile",
          "tsconfig.json");

  /** Directories that are never projects, however they are named. */
  private static final Set<String> NEVER_PROJECTS =
      Set.of(
          "node_modules",
          "target",
          "build",
          "dist",
          "out",
          ".git",
          ".idea",
          ".vscode",
          "vendor",
          "__pycache__",
          ".venv",
          "venv");

  private final String workspaceRoot;

  public WorkspaceCandidateService() {
    this(DEFAULT_ROOT);
  }

  WorkspaceCandidateService(String workspaceRoot) {
    this.workspaceRoot = workspaceRoot;
  }

  /**
   * Immediate subdirectories of the mount that look like projects.
   *
   * <p>Shallow by design: a monorepo's packages are its own concern, and walking
   * the tree on every request would be slow and offer duplicates of the same
   * repository.
   */
  public List<Map<String, Object>> candidates() {
    Path root = Path.of(workspaceRoot);
    if (!Files.isDirectory(root)) {
      return List.of();
    }

    List<Map<String, Object>> rows = new ArrayList<>();
    try (Stream<Path> entries = Files.list(root)) {
      for (Path entry : entries.toList()) {
        if (!Files.isDirectory(entry)) {
          continue;
        }
        String name = entry.getFileName().toString();
        if (name.startsWith(".") || NEVER_PROJECTS.contains(name)) {
          continue;
        }
        List<String> markers = detectMarkers(entry);
        if (markers.isEmpty()) {
          continue;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        // The path as the API sees it, which is what the index request needs.
        row.put("path", entry.toAbsolutePath().normalize().toString());
        row.put("markers", markers);
        rows.add(row);
      }
    } catch (IOException ex) {
      return List.of();
    }

    rows.sort(Comparator.comparing(row -> String.valueOf(row.get("name"))));
    return rows;
  }

  private static List<String> detectMarkers(Path directory) {
    List<String> found = new ArrayList<>();
    for (String marker : PROJECT_MARKERS) {
      try {
        if (Files.exists(directory.resolve(marker))) {
          found.add(marker);
          // One marker is enough to qualify; a couple is plenty to display.
          if (found.size() == 3) {
            break;
          }
        }
      } catch (RuntimeException ignored) {
        // Unreadable entry; treat as absent rather than failing the whole scan.
      }
    }
    return found;
  }
}
