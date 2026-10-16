package com.andromedia.ingestion.zoho;

import java.util.OptionalLong;
import java.util.Set;

/** Retry/backoff helpers for Zoho CRM and OAuth HTTP calls. */
public final class ZohoHttpRetry {

  private static final Set<Integer> RETRYABLE =
      Set.of(429, 408, 500, 502, 503, 504);

  private ZohoHttpRetry() {}

  public record Policy(
      int maxRetries,
      long initialDelayMs,
      double backoffMultiplier,
      long minDelayMsFor429,
      long maxDelayMs) {

    public static Policy defaults() {
      return new Policy(5, 2000L, 2.0, 5000L, 60_000L);
    }

    public Policy {
      maxRetries = Math.max(0, maxRetries);
      initialDelayMs = Math.max(0, initialDelayMs);
      backoffMultiplier = Math.max(1.0, backoffMultiplier);
      minDelayMsFor429 = Math.max(0, minDelayMsFor429);
      maxDelayMs = Math.max(0, maxDelayMs);
    }
  }

  @FunctionalInterface
  public interface Sleeper {
    void sleep(long millis) throws InterruptedException;

    Sleeper THREAD =
        millis -> {
          if (millis > 0) {
            Thread.sleep(millis);
          }
        };
  }

  public static boolean isRetryableStatus(int statusCode) {
    return RETRYABLE.contains(statusCode);
  }

  public static long backoffMillis(
      int failedAttemptIndex, Policy policy, OptionalLong retryAfterSeconds, int statusCode) {
    long initialDelayMs = policy.initialDelayMs();
    double multiplier = policy.backoffMultiplier();
    long configured = (long) (initialDelayMs * Math.pow(multiplier, failedAttemptIndex));

    if (statusCode == 429) {
      long min429 = policy.minDelayMsFor429();
      long rateLimitFloor = (long) (min429 * Math.pow(2, Math.min(failedAttemptIndex, 4)));
      configured = Math.max(configured, rateLimitFloor);
    }

    if (retryAfterSeconds.isPresent()) {
      long retryAfterMs = Math.max(0, retryAfterSeconds.getAsLong()) * 1000L;
      configured = Math.max(configured, retryAfterMs);
    }

    long maxDelayMs = policy.maxDelayMs();
    if (maxDelayMs > 0) {
      configured = Math.min(configured, maxDelayMs);
    }
    return configured;
  }

  public static OptionalLong parseRetryAfterSeconds(String headerValue) {
    if (headerValue == null || headerValue.isBlank()) {
      return OptionalLong.empty();
    }
    try {
      long seconds = Long.parseLong(headerValue.trim());
      return seconds >= 0 ? OptionalLong.of(seconds) : OptionalLong.empty();
    } catch (NumberFormatException ignored) {
      return OptionalLong.empty();
    }
  }

  public static void sleep(Sleeper sleeper, long millis, String interruptMessage)
      throws java.io.IOException {
    if (millis <= 0) {
      return;
    }
    try {
      sleeper.sleep(millis);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new java.io.IOException(interruptMessage, ex);
    }
  }
}
