package com.andromedia.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SlackHistoryCursorStoreTest {

  @TempDir Path tempDir;

  @Test
  void roundTripsChannelCursors() {
    SlackHistoryCursorStore store = new SlackHistoryCursorStore();
    store.saveCursors(
        tempDir, Map.of("C001", "2024-06-14T12:00:00Z", "C002", "2024-06-15T12:00:00Z"));

    assertEquals(
        Optional.of("2024-06-14T12:00:00Z"), store.readLastIndexedTimestamp(tempDir, "C001"));
    assertTrue(store.readCursors(tempDir).containsKey("C002"));
  }
}
