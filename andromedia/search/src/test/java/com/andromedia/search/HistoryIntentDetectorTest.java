package com.andromedia.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HistoryIntentDetectorTest {

  @Test
  void detectsHistoryKeywords() {
    assertTrue(HistoryIntentDetector.isHistoryIntent("why was this changed"));
    assertTrue(HistoryIntentDetector.isHistoryIntent("when did UserService change"));
    assertTrue(HistoryIntentDetector.isHistoryIntent("commit history for auth"));
    assertTrue(HistoryIntentDetector.isHistoryIntent("who changed this?"));
  }

  @Test
  void sanitizesHistoryIntentQueries() {
    assertEquals(
        "impact and plan options implemented",
        HistoryIntentDetector.sanitizeForRetrieval("why was impact and plan options implemented?"));
  }

  @Test
  void ignoresNonHistoryQueries() {
    assertFalse(HistoryIntentDetector.isHistoryIntent("UserService"));
    assertFalse(HistoryIntentDetector.isHistoryIntent("token validation logic"));
    assertFalse(HistoryIntentDetector.isHistoryIntent(""));
    assertFalse(HistoryIntentDetector.isHistoryIntent(null));
  }
}
