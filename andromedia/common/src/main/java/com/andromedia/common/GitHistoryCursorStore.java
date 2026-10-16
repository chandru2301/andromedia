package com.andromedia.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public class GitHistoryCursorStore {

  private static final String CURSOR_FILE_NAME = "git-history.json";
  private static final String FIELD_LAST_INDEXED_COMMIT_SHA = "lastIndexedCommitSha";

  private final ObjectMapper objectMapper = new ObjectMapper();

  public Optional<String> readLastIndexedCommitSha(Path workspaceRoot) {
    Path cursorPath = cursorPath(workspaceRoot);
    if (!Files.isRegularFile(cursorPath)) {
      return Optional.empty();
    }
    try {
      JsonNode root = objectMapper.readTree(cursorPath.toFile());
      JsonNode value = root.get(FIELD_LAST_INDEXED_COMMIT_SHA);
      if (value == null || value.isNull() || value.asText().isBlank()) {
        return Optional.empty();
      }
      return Optional.of(value.asText());
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to read git history cursor at " + cursorPath, ex);
    }
  }

  public void saveLastIndexedCommitSha(Path workspaceRoot, String commitSha) {
    if (commitSha == null || commitSha.isBlank()) {
      return;
    }
    Path resolvedRoot = IndexPathResolver.resolveWorkspaceRoot(workspaceRoot);
    Path cursorPath = cursorPath(resolvedRoot);
    try {
      Files.createDirectories(cursorPath.getParent());
      ObjectNode root = objectMapper.createObjectNode();
      root.put(FIELD_LAST_INDEXED_COMMIT_SHA, commitSha);
      objectMapper.writerWithDefaultPrettyPrinter().writeValue(cursorPath.toFile(), root);
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to write git history cursor at " + cursorPath, ex);
    }
  }

  private static Path cursorPath(Path workspaceRoot) {
    return IndexPathResolver.resolveWorkspaceRoot(workspaceRoot)
        .resolve(".andromedia")
        .resolve(CURSOR_FILE_NAME);
  }
}
