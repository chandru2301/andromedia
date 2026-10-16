package com.andromedia.core.trial;

import java.time.Instant;

public record TrialSession(
    String trialId,
    Instant createdAt,
    Instant expiresAt,
    int queriesUsed,
    int maxQueries,
    String zohoOrgHint) {

  public int remaining() {
    return Math.max(0, maxQueries - queriesUsed);
  }

  public TrialSession withQueryRecorded() {
    return new TrialSession(
        trialId, createdAt, expiresAt, queriesUsed + 1, maxQueries, zohoOrgHint);
  }
}
