package com.andromedia.llm;

import java.util.OptionalLong;

public final class TransientApiRetry {

  private TransientApiRetry() {}

  static boolean isRetryableStatus(int statusCode) {
    return statusCode == 429
        || statusCode == 408
        || statusCode == 500
        || statusCode == 502
        || statusCode == 503
        || statusCode == 504;
  }

  static long backoffMillis(
      int failedAttemptIndex, ChatProperties properties, OptionalLong retryAfterSeconds, int statusCode) {
    long initialDelayMs = Math.max(0, properties.retryInitialDelayMs());
    double multiplier = Math.max(1.0, properties.retryBackoffMultiplier());
    long configured = (long) (initialDelayMs * Math.pow(multiplier, failedAttemptIndex));

    if (statusCode == 429) {
      long min429 = Math.max(0, properties.retryMinDelayMsFor429());
      long rateLimitFloor = (long) (min429 * Math.pow(2, Math.min(failedAttemptIndex, 4)));
      configured = Math.max(configured, rateLimitFloor);
    }

    if (retryAfterSeconds.isPresent()) {
      long retryAfterMs = Math.max(0, retryAfterSeconds.getAsLong()) * 1000L;
      configured = Math.max(configured, retryAfterMs);
    }

    long maxDelayMs = Math.max(0, properties.retryMaxDelayMs());
    if (maxDelayMs > 0) {
      configured = Math.min(configured, maxDelayMs);
    }
    return configured;
  }

  public static void sleep(long millis) {
    if (millis <= 0) {
      return;
    }
    try {
      Thread.sleep(millis);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new ChatException("OpenRouter request retry interrupted", ex);
    }
  }
}
