package com.andromedia.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NotionHistoryCursorStoreTest {

  @TempDir Path tempDir;

  @Test
  void roundTripsPageCursors() {
    NotionHistoryCursorStore store = new NotionHistoryCursorStore();
    store.saveCursors(
        tempDir, Map.of("page-1", "2024-06-14T12:00:00Z", "page-2", "2024-06-15T12:00:00Z"));

    assertEquals(Optional.of("2024-06-14T12:00:00Z"), store.readLastEdited(tempDir, "page-1"));
    assertTrue(store.readCursors(tempDir).containsKey("page-2"));
  }
}
