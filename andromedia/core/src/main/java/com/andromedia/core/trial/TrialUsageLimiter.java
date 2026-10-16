package com.andromedia.core.trial;

import org.springframework.stereotype.Component;

@Component
public class TrialUsageLimiter {

  private final TrialSessionStore store;

  public TrialUsageLimiter(TrialSessionStore store) {
    this.store = store;
  }

  public boolean canQuery(String trialId) {
    return store.find(trialId).map(session -> session.remaining() > 0).orElse(false);
  }

  public int remaining(String trialId) {
    return store.find(trialId).map(TrialSession::remaining).orElse(0);
  }

  public TrialSession recordQuery(String trialId) {
    return store.recordQuery(trialId);
  }
}
