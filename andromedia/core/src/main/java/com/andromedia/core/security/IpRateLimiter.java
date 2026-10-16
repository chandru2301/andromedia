package com.andromedia.core.security;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory sliding-window rate limiter keyed by client IP. */
public class IpRateLimiter {

  private final int requestsPerMinute;
  private final ConcurrentHashMap<String, List<Instant>> buckets = new ConcurrentHashMap<>();

  public IpRateLimiter(int requestsPerMinute) {
    this.requestsPerMinute = Math.max(1, requestsPerMinute);
  }

  public boolean allowRequest(String clientIp) {
    String key = (clientIp == null || clientIp.isBlank()) ? "unknown" : clientIp.trim();
    Instant cutoff = Instant.now().minus(1, ChronoUnit.MINUTES);
    List<Instant> recent = buckets.computeIfAbsent(key, ignored -> new ArrayList<>());
    synchronized (recent) {
      recent.removeIf(instant -> instant.isBefore(cutoff));
      if (recent.size() >= requestsPerMinute) {
        return false;
      }
      recent.add(Instant.now());
      return true;
    }
  }
}
