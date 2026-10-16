package com.andromedia.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZohoSyncWatermarkStoreTest {

  @TempDir Path tempDir;

  @Test
  void missingFileReturnsEmpty() {
    ZohoSyncWatermarkStore store = new ZohoSyncWatermarkStore(tempDir);
    assertTrue(store.getWatermark("acme:com", "Deals").isEmpty());
  }

  @Test
  void roundTripsPerModuleWatermark() {
    ZohoSyncWatermarkStore store = new ZohoSyncWatermarkStore(tempDir);
    Instant dealsAt = Instant.parse("2026-07-21T10:00:00Z");
    Instant accountsAt = Instant.parse("2026-07-21T10:00:05Z");

    store.updateWatermark("acme:com", "Deals", dealsAt, 47);
    store.updateWatermark("acme:com", "Accounts", accountsAt, 23);

    assertEquals(Optional.of(dealsAt), store.getWatermark("acme:com", "Deals"));
    assertEquals(Optional.of(accountsAt), store.getWatermark("acme:com", "Accounts"));
    assertTrue(store.getWatermark("acme:com", "Contacts").isEmpty());
  }

  @Test
  void sanitizesOrgIdInFilename() throws Exception {
    ZohoSyncWatermarkStore store = new ZohoSyncWatermarkStore(tempDir);
    Instant at = Instant.parse("2026-07-21T12:00:00Z");
    store.updateWatermark("my service:us", "Deals", at, 1);

    Path expected = tempDir.resolve("my_service_us.json");
    assertTrue(Files.isRegularFile(expected));
    assertFalse(Files.exists(tempDir.resolve("my service:us.json")));
    assertEquals(Optional.of(at), store.getWatermark("my service:us", "Deals"));
  }

  @Test
  void atomicWriteLeavesNoTmpFile() throws Exception {
    ZohoSyncWatermarkStore store = new ZohoSyncWatermarkStore(tempDir);
    store.updateWatermark("org", "Deals", Instant.parse("2026-07-21T14:00:00Z"), 3);

    Path file = store.watermarkPath("org");
    assertTrue(Files.isRegularFile(file));
    assertFalse(Files.exists(file.resolveSibling(file.getFileName() + ".tmp")));
    String json = Files.readString(file);
    assertTrue(json.contains("\"lastSyncedAt\""));
    assertTrue(json.contains("\"lastSyncStatus\" : \"success\"") || json.contains("\"lastSyncStatus\":\"success\""));
    assertTrue(json.contains("\"recordsFetched\""));
  }
}
