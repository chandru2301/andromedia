package com.andromedia.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Persists last-indexed Notion page timestamps under {@code .andromedia/notion-history.json}. */
public class NotionHistoryCursorStore {

  private static final String CURSOR_FILE_NAME = "notion-history.json";
  private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {};

  private final ObjectMapper objectMapper =
      new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  public Map<String, String> readCursors(Path workspaceRoot) {
    Path cursorPath = cursorPath(workspaceRoot);
    if (!Files.isRegularFile(cursorPath)) {
      return Map.of();
    }
    try {
      Map<String, String> values = objectMapper.readValue(cursorPath.toFile(), MAP_TYPE);
      return values == null ? Map.of() : Map.copyOf(values);
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to read Notion history cursor at " + cursorPath, ex);
    }
  }

  public Optional<String> readLastEdited(Path workspaceRoot, String pageId) {
    if (pageId == null || pageId.isBlank()) {
      return Optional.empty();
    }
    String value = readCursors(workspaceRoot).get(pageId);
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(value);
  }

  public void saveCursors(Path workspaceRoot, Map<String, String> pageLastEdited) {
    if (pageLastEdited == null || pageLastEdited.isEmpty()) {
      return;
    }
    Path resolvedRoot = IndexPathResolver.resolveWorkspaceRoot(workspaceRoot);
    Path cursorPath = cursorPath(resolvedRoot);
    try {
      Files.createDirectories(cursorPath.getParent());
      Map<String, String> merged = new LinkedHashMap<>(readCursors(resolvedRoot));
      merged.putAll(pageLastEdited);
      objectMapper.writeValue(cursorPath.toFile(), merged);
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to write Notion history cursor at " + cursorPath, ex);
    }
  }

  private static Path cursorPath(Path workspaceRoot) {
    return IndexPathResolver.resolveWorkspaceRoot(workspaceRoot)
        .resolve(".andromedia")
        .resolve(CURSOR_FILE_NAME);
  }
}
