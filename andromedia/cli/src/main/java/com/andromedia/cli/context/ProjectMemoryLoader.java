package com.andromedia.cli.context;

import com.andromedia.common.IndexPathResolver;
import com.andromedia.llm.SideQueryService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

@Component
public class ProjectMemoryLoader {

  private static final String MEMORY_DIR = "memory";
  private static final String INDEX_FILE = "MEMORY.md";

  private final SideQueryService sideQueryService;

  public ProjectMemoryLoader(SideQueryService sideQueryService) {
    this.sideQueryService = sideQueryService;
  }

  public LoadedMemory load(Path startDirectory, String query, int maxChars) {
    Path memoryDir = memoryDirectory(startDirectory);
    if (!Files.isDirectory(memoryDir)) {
      return LoadedMemory.empty();
    }
    List<MemoryFile> files = scanMemoryFiles(memoryDir);
    if (files.isEmpty()) {
      return LoadedMemory.empty();
    }
    List<MemoryFile> selected = selectRelevant(query, files, 3);
    String combined = combineWithinBudget(selected, maxChars);
    return new LoadedMemory(
        combined, selected.stream().map(file -> file.path().toString()).toList());
  }

  public List<String> listMemoryFiles(Path startDirectory) {
    Path memoryDir = memoryDirectory(startDirectory);
    if (!Files.isDirectory(memoryDir)) {
      return List.of();
    }
    return scanMemoryFiles(memoryDir).stream().map(file -> file.path().toString()).toList();
  }

  private List<MemoryFile> selectRelevant(String query, List<MemoryFile> files, int limit) {
    List<String> filenames =
        files.stream().map(file -> file.path().getFileName().toString()).toList();
    List<MemoryFile> selected = new ArrayList<>();
    sideQueryService
        .selectMemoryFiles(query, filenames)
        .ifPresent(
            csv -> {
              for (String name : csv.split(",")) {
                String trimmed = name.trim();
                files.stream()
                    .filter(file -> file.path().getFileName().toString().equals(trimmed))
                    .findFirst()
                    .ifPresent(selected::add);
              }
            });
    if (selected.isEmpty()) {
      for (MemoryFile file : files) {
        if (selected.size() >= limit) {
          break;
        }
        if (!file.path().getFileName().toString().equalsIgnoreCase(INDEX_FILE)) {
          selected.add(file);
        }
      }
    }
    return selected.stream().limit(limit).toList();
  }

  private static List<MemoryFile> scanMemoryFiles(Path memoryDir) {
    List<MemoryFile> files = new ArrayList<>();
    try (Stream<Path> stream = Files.list(memoryDir)) {
      stream
          .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md"))
          .sorted(Comparator.comparing(path -> path.getFileName().toString()))
          .forEach(
              path -> {
                try {
                  String content = Files.readString(path, StandardCharsets.UTF_8).trim();
                  if (!content.isBlank()) {
                    files.add(new MemoryFile(path, content));
                  }
                } catch (IOException ignored) {
                  // skip
                }
              });
    } catch (IOException ex) {
      return List.of();
    }
    return files;
  }

  private static String combineWithinBudget(List<MemoryFile> files, int maxChars) {
    StringBuilder builder = new StringBuilder();
    for (MemoryFile file : files) {
      String section =
          """
          --- Memory: %s ---
          %s
          """
              .formatted(file.path().getFileName(), file.content());
      if (builder.length() + section.length() > maxChars) {
        break;
      }
      builder.append(section).append('\n');
    }
    return builder.toString().trim();
  }

  public static Path memoryDirectory(Path startDirectory) {
    String projectId = IndexPathResolver.resolveProjectId(startDirectory);
    return Path.of(System.getProperty("user.home"), ".andromedia", MEMORY_DIR, projectId)
        .toAbsolutePath()
        .normalize();
  }

  private record MemoryFile(Path path, String content) {}

  public record LoadedMemory(String text, List<String> sourcePaths) {
    static LoadedMemory empty() {
      return new LoadedMemory("", List.of());
    }

    public boolean isEmpty() {
      return text == null || text.isBlank();
    }
  }
}
