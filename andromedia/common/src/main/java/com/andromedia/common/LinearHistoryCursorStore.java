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

/** Persists last-indexed Linear team timestamps under {@code .andromedia/linear-history.json}. */
public class LinearHistoryCursorStore {

  private static final String CURSOR_FILE_NAME = "linear-history.json";
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
      throw new IllegalStateException("Failed to read Linear history cursor at " + cursorPath, ex);
    }
  }

  public Optional<String> readLastUpdated(Path workspaceRoot, String teamKey) {
    if (teamKey == null || teamKey.isBlank()) {
      return Optional.empty();
    }
    String value = readCursors(workspaceRoot).get(teamKey);
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(value);
  }

  public void saveCursors(Path workspaceRoot, Map<String, String> teamLastUpdated) {
    if (teamLastUpdated == null || teamLastUpdated.isEmpty()) {
      return;
    }
    Path resolvedRoot = IndexPathResolver.resolveWorkspaceRoot(workspaceRoot);
    Path cursorPath = cursorPath(resolvedRoot);
    try {
      Files.createDirectories(cursorPath.getParent());
      Map<String, String> merged = new LinkedHashMap<>(readCursors(resolvedRoot));
      merged.putAll(teamLastUpdated);
      objectMapper.writeValue(cursorPath.toFile(), merged);
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to write Linear history cursor at " + cursorPath, ex);
    }
  }

  private static Path cursorPath(Path workspaceRoot) {
    return IndexPathResolver.resolveWorkspaceRoot(workspaceRoot)
        .resolve(".andromedia")
        .resolve(CURSOR_FILE_NAME);
  }
}
