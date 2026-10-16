package com.andromedia.search;

import java.time.Duration;

public interface PlanProgressCallback {

  enum PhaseEvent {
    START,
    COMPLETE
  }

  void onPhase(PlanProgressPhase phase, String detail, PhaseEvent event, Duration duration);

  PlanProgressCallback NOOP = (phase, detail, event, duration) -> {};
}
