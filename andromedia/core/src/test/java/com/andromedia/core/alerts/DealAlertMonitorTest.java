package com.andromedia.core.alerts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.andromedia.core.alerts.PipelineAlertStore.DealSnapshot;
import com.andromedia.core.alerts.SlackMessageService.AlertEvent;
import com.andromedia.search.ZohoQualificationService.DealLedgerEntry;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DealAlertMonitorTest {

  @Test
  void firstDiffAgainstEmptyPreviousIsHandledByCallerNotDiff() {
    // diff() itself still reports crossings when previous is empty for a key
    Map<String, DealSnapshot> previous = Map.of();
    Map<String, DealSnapshot> current =
        Map.of(
            "chunk-a",
            new DealSnapshot("Qualification", "10000", "2026-08-01", 0.4, List.of("stale_record")));
    List<AlertEvent> events =
        DealAlertMonitor.diff(previous, current, List.of(deal("A", "chunk-a", "Qualification", List.of("stale_record"))));
    assertEquals(1, events.size());
    assertEquals("stale_record", events.getFirst().kind());
  }

  @Test
  void newlyGainedStaleFiresOnce() {
    Map<String, DealSnapshot> previous =
        Map.of(
            "chunk-a",
            new DealSnapshot("Qualification", "10000", "2026-08-01", 0.4, List.of()));
    Map<String, DealSnapshot> current =
        Map.of(
            "chunk-a",
            new DealSnapshot(
                "Qualification", "10000", "2026-08-01", 0.2, List.of("stale_record")));
    List<AlertEvent> events =
        DealAlertMonitor.diff(
            previous,
            current,
            List.of(deal("Reliance", "chunk-a", "Qualification", List.of("stale_record"))));
    assertEquals(1, events.size());
    assertEquals("Reliance", events.getFirst().dealName());
    assertEquals("stale_record", events.getFirst().kind());
  }

  @Test
  void alreadyStaleDoesNotRefire() {
    Map<String, DealSnapshot> previous =
        Map.of(
            "chunk-a",
            new DealSnapshot(
                "Qualification", "10000", "2026-08-01", 0.2, List.of("stale_record")));
    Map<String, DealSnapshot> current =
        Map.of(
            "chunk-a",
            new DealSnapshot(
                "Qualification", "10000", "2026-08-01", 0.2, List.of("stale_record")));
    List<AlertEvent> events =
        DealAlertMonitor.diff(
            previous,
            current,
            List.of(deal("Reliance", "chunk-a", "Qualification", List.of("stale_record"))));
    assertTrue(events.isEmpty());
  }

  @Test
  void hotStageEntryFires() {
    Map<String, DealSnapshot> previous =
        Map.of(
            "chunk-b",
            new DealSnapshot("Qualification", "500000", "2026-07-28", 0.5, List.of()));
    Map<String, DealSnapshot> current =
        Map.of(
            "chunk-b",
            new DealSnapshot("Negotiation/Review", "500000", "2026-07-28", 0.7, List.of()));
    List<AlertEvent> events =
        DealAlertMonitor.diff(
            previous,
            current,
            List.of(deal("Acme", "chunk-b", "Negotiation/Review", List.of())));
    assertEquals(1, events.size());
    assertEquals("hot_stage", events.getFirst().kind());
    assertTrue(events.getFirst().detail().contains("Negotiation/Review"));
  }

  @Test
  void closeDatePassedFiresWhenNewlyGained() {
    Map<String, DealSnapshot> previous =
        Map.of("chunk-c", new DealSnapshot("Proposal/Price Quote", "1200", "2026-06-01", 0.3, List.of()));
    Map<String, DealSnapshot> current =
        Map.of(
            "chunk-c",
            new DealSnapshot(
                "Proposal/Price Quote",
                "1200",
                "2026-06-01",
                0.2,
                List.of("close_date_passed")));
    List<AlertEvent> events =
        DealAlertMonitor.diff(
            previous,
            current,
            List.of(
                deal(
                    "Quiet Deal",
                    "chunk-c",
                    "Proposal/Price Quote",
                    List.of("close_date_passed"))));
    assertEquals(1, events.size());
    assertEquals("close_date_passed", events.getFirst().kind());
  }

  @Test
  void toSnapshotSkipsDemoDeals() {
    DealLedgerEntry demo =
        new DealLedgerEntry(
            "Demo",
            "Demo Co",
            Map.of("Stage", "Negotiation/Review"),
            List.of("test_demo_pattern"),
            true,
            90,
            1,
            Instant.parse("2026-07-01T00:00:00Z"),
            "chunk-demo",
            "path",
            "",
            0.9);
    DealLedgerEntry real =
        deal("Real", "chunk-real", "Qualification", List.of());
    Map<String, DealSnapshot> snapshot = DealAlertMonitor.toSnapshot(List.of(demo, real));
    assertEquals(1, snapshot.size());
    assertTrue(snapshot.containsKey("chunk-real"));
  }

  private static DealLedgerEntry deal(
      String name, String chunkId, String stage, List<String> flags) {
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("Stage", stage);
    fields.put("Amount", "10000");
    fields.put("Close Date", "2026-08-01");
    return new DealLedgerEntry(
        name,
        name + " Account",
        fields,
        flags,
        false,
        50,
        10000,
        Instant.parse("2026-07-01T00:00:00Z"),
        chunkId,
        "zoho/" + name,
        "",
        0.5);
  }
}
