package com.andromedia.ingestion.zoho;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class ZohoHttpRetryTest {

  @Test
  void classifiesRetryableStatuses() {
    assertTrue(ZohoHttpRetry.isRetryableStatus(429));
    assertTrue(ZohoHttpRetry.isRetryableStatus(500));
    assertTrue(ZohoHttpRetry.isRetryableStatus(503));
    assertTrue(ZohoHttpRetry.isRetryableStatus(408));
    assertFalse(ZohoHttpRetry.isRetryableStatus(401));
    assertFalse(ZohoHttpRetry.isRetryableStatus(400));
    assertFalse(ZohoHttpRetry.isRetryableStatus(404));
  }

  @Test
  void exponentialBackoffUsesMultiplier() {
    ZohoHttpRetry.Policy policy = new ZohoHttpRetry.Policy(5, 1000L, 2.0, 0L, 60_000L);

    assertEquals(1000, ZohoHttpRetry.backoffMillis(0, policy, OptionalLong.empty(), 0));
    assertEquals(2000, ZohoHttpRetry.backoffMillis(1, policy, OptionalLong.empty(), 0));
    assertEquals(4000, ZohoHttpRetry.backoffMillis(2, policy, OptionalLong.empty(), 0));
  }

  @Test
  void backoffHonorsRetryAfterHeader() {
    ZohoHttpRetry.Policy policy = new ZohoHttpRetry.Policy(5, 1000L, 2.0, 0L, 60_000L);

    assertEquals(5000, ZohoHttpRetry.backoffMillis(0, policy, OptionalLong.of(5), 0));
  }

  @Test
  void backoffUsesHigherFloorFor429() {
    ZohoHttpRetry.Policy policy = new ZohoHttpRetry.Policy(5, 2000L, 2.0, 5000L, 60_000L);

    assertEquals(5000, ZohoHttpRetry.backoffMillis(0, policy, OptionalLong.empty(), 429));
    assertEquals(10000, ZohoHttpRetry.backoffMillis(1, policy, OptionalLong.empty(), 429));
  }

  @Test
  void backoffRespectsMaxDelayCap() {
    ZohoHttpRetry.Policy policy = new ZohoHttpRetry.Policy(5, 10_000L, 2.0, 5000L, 8000L);

    assertEquals(8000, ZohoHttpRetry.backoffMillis(2, policy, OptionalLong.empty(), 429));
  }

  @Test
  void parseRetryAfterSeconds() {
    assertEquals(OptionalLong.of(3), ZohoHttpRetry.parseRetryAfterSeconds("3"));
    assertEquals(OptionalLong.empty(), ZohoHttpRetry.parseRetryAfterSeconds(""));
    assertEquals(OptionalLong.empty(), ZohoHttpRetry.parseRetryAfterSeconds("not-a-number"));
  }
}
