package com.andromedia.core.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.andromedia.search.ServiceIndexOverviewReader.Overview;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ServiceIndexOverviewServiceMergeTest {

  @Test
  void mergeUnionsSourceTypesAndSumsChunks() {
    Overview slack =
        new Overview(
            "/tmp/local",
            true,
            5,
            1,
            List.of("SLACK"),
            Instant.parse("2026-07-20T00:00:00Z"),
            100L);
    Overview zoho =
        new Overview(
            "/tmp/local__z_abc",
            true,
            9,
            1,
            List.of("ZOHO"),
            Instant.parse("2026-07-22T00:00:00Z"),
            250L);

    Overview merged =
        ServiceIndexOverviewService.merge(
            List.of(slack, zoho), List.of(Path.of("/tmp/local"), Path.of("/tmp/local__z_abc")));

    assertTrue(merged.exists());
    assertEquals(14, merged.totalChunks());
    assertEquals(2, merged.sourcesConnected());
    assertTrue(merged.sourceTypes().contains("SLACK"));
    assertTrue(merged.sourceTypes().contains("ZOHO"));
    assertEquals(350L, merged.indexSizeBytes());
    assertEquals(Instant.parse("2026-07-22T00:00:00Z"), merged.lastSyncAt());
    assertTrue(merged.indexPath().contains("local"));
  }

  @Test
  void mergeIgnoresMissingBuckets() {
    Overview missing = new Overview("/tmp/missing", false, 0, 0, List.of(), null, 0L);
    Overview present =
        new Overview(
            "/tmp/local__z_abc",
            true,
            3,
            1,
            List.of("ZOHO"),
            Instant.parse("2026-07-22T00:00:00Z"),
            50L);

    Overview merged =
        ServiceIndexOverviewService.merge(
            List.of(missing, present),
            List.of(Path.of("/tmp/missing"), Path.of("/tmp/local__z_abc")));

    assertTrue(merged.exists());
    assertEquals(3, merged.totalChunks());
    assertEquals(List.of("ZOHO"), merged.sourceTypes());
  }
}
