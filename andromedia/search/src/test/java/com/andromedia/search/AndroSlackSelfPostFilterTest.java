package com.andromedia.search;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.andromedia.common.ChunkUnitType;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AndroSlackSelfPostFilterTest {

  @Test
  void dropsCausrAlertChunksAndKeepsHumanSlack() {
    SearchHit selfPost =
        slackHit(
            "[SLACK] #all-causr · 2026-08-17 · causr_alert\n"
                + "causr_alert: *Andro pipeline alert — local* 8 new signals");
    SearchHit human =
        slackHit(
            "[SLACK] #all-causr · 2026-08-17 · Chandru\n"
                + "Chandru: Bright Horizon deal is actually stalled, Fatima went quiet");
    SearchHit zoho =
        new SearchHit(
            "zoho://deal/1",
            "deal",
            1.0f,
            "z1",
            null,
            null,
            ChunkUnitType.FILE,
            0,
            0,
            null,
            null,
            null,
            null,
            null,
            Optional.of("ZOHO"),
            Optional.empty(),
            Optional.empty(),
            Optional.of("*Andro investigate — should not apply to CRM*"),
            Optional.empty());

    List<SearchHit> filtered = AndroSlackSelfPostFilter.remove(List.of(selfPost, human, zoho));
    assertEquals(2, filtered.size());
    assertEquals(human, filtered.get(0));
    assertEquals(zoho, filtered.get(1));
  }

  private static SearchHit slackHit(String content) {
    return new SearchHit(
        "slack://#all-causr/1",
        "slack",
        1.0f,
        "s1",
        null,
        null,
        ChunkUnitType.FILE,
        0,
        0,
        null,
        null,
        null,
        null,
        null,
        Optional.of("SLACK"),
        Optional.empty(),
        Optional.empty(),
        Optional.of(content),
        Optional.empty());
  }
}
