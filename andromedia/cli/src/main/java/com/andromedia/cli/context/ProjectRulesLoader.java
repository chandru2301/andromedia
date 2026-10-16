package com.andromedia.cli.context;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

@Component
public class ProjectRulesLoader {

  private static final String ROOT_RULES_FILE = "ANDROMEDIA.md";
  private static final String RULES_DIR = ".andromedia/rules";

  public LoadedRules load(Path startDirectory, int maxChars) {
    Path workspaceRoot = resolveWorkspaceRoot(startDirectory);
    Map<String, String> entries = new LinkedHashMap<>();
    collectRules(workspaceRoot, entries);
    if (entries.isEmpty()) {
      return LoadedRules.empty();
    }
    String combined = combineWithinBudget(entries, maxChars);
    return new LoadedRules(combined, List.copyOf(entries.keySet()));
  }

  public List<String> listRulePaths(Path startDirectory) {
    Path workspaceRoot = resolveWorkspaceRoot(startDirectory);
    Map<String, String> entries = new LinkedHashMap<>();
    collectRules(workspaceRoot, entries);
    return List.copyOf(entries.keySet());
  }

  private static void collectRules(Path workspaceRoot, Map<String, String> entries) {
    Path current = workspaceRoot.toAbsolutePath().normalize();
    List<Path> chain = new ArrayList<>();
    while (current != null) {
      chain.add(current);
      current = current.getParent();
    }
    for (int i = chain.size() - 1; i >= 0; i--) {
      Path dir = chain.get(i);
      addFile(entries, dir.resolve(ROOT_RULES_FILE));
      Path rulesDir = dir.resolve(RULES_DIR);
      if (Files.isDirectory(rulesDir)) {
        try (Stream<Path> stream = Files.list(rulesDir)) {
          stream
              .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md"))
              .sorted(Comparator.comparing(path -> path.getFileName().toString()))
              .forEach(path -> addFile(entries, path));
        } catch (IOException ignored) {
          // skip unreadable rules dir
        }
      }
    }
  }

  private static void addFile(Map<String, String> entries, Path path) {
    if (!Files.isRegularFile(path)) {
      return;
    }
    try {
      String content = Files.readString(path, StandardCharsets.UTF_8).trim();
      if (!content.isBlank()) {
        entries.put(path.toString(), content);
      }
    } catch (IOException ignored) {
      // skip unreadable file
    }
  }

  private static String combineWithinBudget(Map<String, String> entries, int maxChars) {
    StringBuilder builder = new StringBuilder();
    for (Map.Entry<String, String> entry : entries.entrySet()) {
      String section =
          """
          --- Rules from %s ---
          %s
          """
              .formatted(entry.getKey(), entry.getValue());
      if (builder.length() + section.length() > maxChars) {
        int remaining = maxChars - builder.length();
        if (remaining > 80) {
          builder.append(section, 0, remaining).append("\n...(truncated)");
        }
        break;
      }
      builder.append(section).append('\n');
    }
    return builder.toString().trim();
  }

  static Path resolveWorkspaceRoot(Path startDirectory) {
    Path normalized = startDirectory == null ? Path.of("").toAbsolutePath() : startDirectory.toAbsolutePath();
    Path current = normalized.normalize();
    while (current != null) {
      if (Files.exists(current.resolve(ROOT_RULES_FILE))
          || Files.isDirectory(current.resolve(RULES_DIR))
          || Files.isDirectory(current.resolve(".git"))
          || Files.exists(current.resolve("pom.xml"))) {
        return current;
      }
      current = current.getParent();
    }
    return normalized;
  }

  public record LoadedRules(String text, List<String> sourcePaths) {
    static LoadedRules empty() {
      return new LoadedRules("", List.of());
    }

    public boolean isEmpty() {
      return text == null || text.isBlank();
    }
  }
}
