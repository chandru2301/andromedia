package com.andromedia.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class TransientApiRetryTest {

  @Test
  void classifiesRetryableStatuses() {
    assertTrue(TransientApiRetry.isRetryableStatus(429));
    assertTrue(TransientApiRetry.isRetryableStatus(500));
    assertTrue(TransientApiRetry.isRetryableStatus(503));
    assertTrue(TransientApiRetry.isRetryableStatus(408));
    assertFalse(TransientApiRetry.isRetryableStatus(401));
    assertFalse(TransientApiRetry.isRetryableStatus(404));
  }

  @Test
  void exponentialBackoffUsesMultiplier() {
    ChatProperties properties = new ChatProperties();
    properties.setRetryInitialDelayMs(1000);
    properties.setRetryBackoffMultiplier(2.0);
    properties.setRetryMinDelayMsFor429(0);

    assertEquals(
        1000,
        TransientApiRetry.backoffMillis(0, properties, OptionalLong.empty(), 0));
    assertEquals(
        2000,
        TransientApiRetry.backoffMillis(1, properties, OptionalLong.empty(), 0));
    assertEquals(
        4000,
        TransientApiRetry.backoffMillis(2, properties, OptionalLong.empty(), 0));
  }

  @Test
  void backoffHonorsRetryAfterHeader() {
    ChatProperties properties = new ChatProperties();
    properties.setRetryInitialDelayMs(1000);
    properties.setRetryBackoffMultiplier(2.0);
    properties.setRetryMinDelayMsFor429(0);

    assertEquals(
        5000,
        TransientApiRetry.backoffMillis(0, properties, OptionalLong.of(5), 0));
  }

  @Test
  void backoffUsesHigherFloorFor429() {
    ChatProperties properties = new ChatProperties();
    properties.setRetryInitialDelayMs(2000);
    properties.setRetryBackoffMultiplier(2.0);
    properties.setRetryMinDelayMsFor429(5000);

    assertEquals(
        5000,
        TransientApiRetry.backoffMillis(0, properties, OptionalLong.empty(), 429));
    assertEquals(
        10000,
        TransientApiRetry.backoffMillis(1, properties, OptionalLong.empty(), 429));
  }

  @Test
  void backoffRespectsMaxDelayCap() {
    ChatProperties properties = new ChatProperties();
    properties.setRetryInitialDelayMs(10_000);
    properties.setRetryBackoffMultiplier(2.0);
    properties.setRetryMinDelayMsFor429(5000);
    properties.setRetryMaxDelayMs(8000);

    assertEquals(
        8000,
        TransientApiRetry.backoffMillis(2, properties, OptionalLong.empty(), 429));
  }
}
