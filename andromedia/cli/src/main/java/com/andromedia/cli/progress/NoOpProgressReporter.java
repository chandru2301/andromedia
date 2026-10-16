package com.andromedia.cli.progress;

import java.time.Duration;
import java.util.List;

final class NoOpProgressReporter implements CliProgressReporter {

  @Override
  public void startPhase(CliPhase phase) {}

  @Override
  public void updatePhase(CliPhase phase, String detail) {}

  @Override
  public void completePhase(CliPhase phase, Duration duration) {}

  @Override
  public void seal() {}

  @Override
  public void fail(String message) {}

  @Override
  public void printTimingSummary(List<PhaseTiming> timings, boolean plainSymbols) {}
}
