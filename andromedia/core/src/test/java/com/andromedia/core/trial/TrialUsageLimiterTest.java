package com.andromedia.core.trial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TrialUsageLimiterTest {

  private TrialSessionStore store;
  private TrialUsageLimiter limiter;

  @BeforeEach
  void setUp() {
    TrialProperties properties = new TrialProperties();
    store = new TrialSessionStore(properties);
    limiter = new TrialUsageLimiter(store);
    store.save(
        new TrialSession(
            "trial-1",
            Instant.now(),
            Instant.now().plus(1, ChronoUnit.HOURS),
            0,
            2,
            null));
  }

  @Test
  void allowsQueriesUntilLimit() {
    assertTrue(limiter.canQuery("trial-1"));
    limiter.recordQuery("trial-1");
    assertTrue(limiter.canQuery("trial-1"));
    limiter.recordQuery("trial-1");
    assertFalse(limiter.canQuery("trial-1"));
    assertEquals(0, limiter.remaining("trial-1"));
  }
}
