package com.andromedia.cli.progress;

import com.andromedia.search.PlanProgressCallback;
import com.andromedia.search.PlanProgressPhase;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class CliPlanProgressAdapter implements PlanProgressCallback {

  private final CliProgressReporter progress;
  private final List<PhaseTiming> timings = new ArrayList<>();

  public CliPlanProgressAdapter(CliProgressReporter progress) {
    this.progress = progress;
  }

  @Override
  public void onPhase(
      PlanProgressPhase phase, String detail, PhaseEvent event, Duration duration) {
    CliPhase cliPhase = mapPhase(phase);
    if (event == PhaseEvent.START) {
      progress.startPhase(cliPhase);
      if (detail != null && !detail.isBlank()) {
        progress.updatePhase(cliPhase, detail);
      }
      return;
    }
    Duration elapsed = duration == null ? Duration.ZERO : duration;
    progress.completePhase(cliPhase, elapsed);
    timings.add(new PhaseTiming(cliPhase, elapsed));
  }

  public List<PhaseTiming> timings() {
    return List.copyOf(timings);
  }

  private static CliPhase mapPhase(PlanProgressPhase phase) {
    return switch (phase) {
      case EXPLORING_CODEBASE -> CliPhase.EXPLORING_CODEBASE;
      case ANALYSING_IMPACT -> CliPhase.ANALYSING_IMPACT;
      case ANALYSING_GRAPH -> CliPhase.ANALYSING_GRAPH;
      case GROUNDING_CONTEXT -> CliPhase.GROUNDING_CONTEXT;
      case WRITING_PLAN -> CliPhase.WRITING_PLAN;
    };
  }
}
