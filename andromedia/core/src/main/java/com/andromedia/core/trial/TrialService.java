package com.andromedia.core.trial;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class TrialService {

  private final TrialProperties properties;
  private final TrialSessionStore store;
  private final TrialUsageLimiter limiter;
  private final ConcurrentHashMap<String, List<Instant>> startsByIp = new ConcurrentHashMap<>();

  public TrialService(
      TrialProperties properties, TrialSessionStore store, TrialUsageLimiter limiter) {
    this.properties = properties;
    this.store = store;
    this.limiter = limiter;
  }

  public boolean enabled() {
    return properties.enabled();
  }

  public Map<String, Object> config() {
    if (!properties.enabled()) {
      return Map.of("enabled", false);
    }
    return Map.of(
        "enabled",
        true,
        "maxQueries",
        properties.maxQueriesPerSession(),
        "chatModel",
        properties.chatModel());
  }

  public TrialSession startSession(String trialId, String clientIp) {
    requireEnabled();
    validateTrialId(trialId);
    enforceIpRateLimit(clientIp);

    TrialSession existing = store.find(trialId.trim()).orElse(null);
    if (existing != null) {
      return existing;
    }

    Instant now = Instant.now();
    TrialSession session =
        new TrialSession(
            trialId.trim(),
            now,
            now.plus(properties.sessionTtlHours(), ChronoUnit.HOURS),
            0,
            properties.maxQueriesPerSession(),
            null);
    return store.save(session);
  }

  public TrialSession status(String trialId) {
    requireEnabled();
    validateTrialId(trialId);
    return store
        .find(trialId.trim())
        .orElseThrow(() -> new IllegalArgumentException("Unknown or expired trial session"));
  }

  public boolean canQuery(String trialId) {
    if (!properties.enabled()) {
      return true;
    }
    return limiter.canQuery(trialId);
  }

  public TrialSession recordQuery(String trialId) {
    return limiter.recordQuery(trialId);
  }

  public int remaining(String trialId) {
    return limiter.remaining(trialId);
  }

  public Map<String, Object> sessionView(TrialSession session) {
    return Map.of(
        "trialId",
        session.trialId(),
        "queriesUsed",
        session.queriesUsed(),
        "maxQueries",
        session.maxQueries(),
        "remaining",
        session.remaining());
  }

  private void requireEnabled() {
    if (!properties.enabled()) {
      throw new TrialNotEnabledException();
    }
  }

  private void validateTrialId(String trialId) {
    if (trialId == null || trialId.isBlank()) {
      throw new IllegalArgumentException("trialId is required");
    }
    if (trialId.trim().length() > 64) {
      throw new IllegalArgumentException("trialId is too long");
    }
    if (!trialId.trim().matches("[a-zA-Z0-9_-]+")) {
      throw new IllegalArgumentException(
          "trialId must be alphanumeric, hyphen, or underscore");
    }
  }

  private void enforceIpRateLimit(String clientIp) {
    String key = (clientIp == null || clientIp.isBlank()) ? "unknown" : clientIp.trim();
    Instant cutoff = Instant.now().minus(1, ChronoUnit.HOURS);
    List<Instant> recent =
        startsByIp.computeIfAbsent(key, ignored -> new ArrayList<>());
    synchronized (recent) {
      recent.removeIf(instant -> instant.isBefore(cutoff));
      if (recent.size() >= properties.maxStartsPerIpHour()) {
        throw new IllegalArgumentException("Too many trial sessions from this IP. Try again later.");
      }
      recent.add(Instant.now());
    }
  }

  public static class TrialNotEnabledException extends RuntimeException {}
}
