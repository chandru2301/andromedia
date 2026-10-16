package com.andromedia.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitHistoryCursorStoreTest {

  @TempDir Path tempDir;

  @Test
  void returnsEmptyWhenCursorMissing() {
    GitHistoryCursorStore store = new GitHistoryCursorStore();
    assertTrue(store.readLastIndexedCommitSha(tempDir).isEmpty());
  }

  @Test
  void writesAndReadsLastIndexedCommitSha() throws Exception {
    GitHistoryCursorStore store = new GitHistoryCursorStore();
    store.saveLastIndexedCommitSha(tempDir, "abc123def456");

    assertEquals("abc123def456", store.readLastIndexedCommitSha(tempDir).orElseThrow());
    Path cursorPath = tempDir.resolve(".andromedia").resolve("git-history.json");
    assertTrue(Files.isRegularFile(cursorPath));
    String json = Files.readString(cursorPath);
    assertTrue(json.contains("abc123def456"));
  }
}
