package com.andromedia.cli.progress;

import java.io.PrintStream;
import java.time.Duration;
import java.util.List;

public class PlainProgressReporter implements CliProgressReporter {

  private final PrintStream out;
  private boolean sealed;

  public PlainProgressReporter() {
    this(System.err);
  }

  PlainProgressReporter(PrintStream out) {
    this.out = out;
  }

  @Override
  public void startPhase(CliPhase phase) {
    if (sealed) {
      return;
    }
    out.printf("[andro] %s...%n", phase.label());
  }

  @Override
  public void updatePhase(CliPhase phase, String detail) {
    if (sealed) {
      return;
    }
    if (detail != null && !detail.isBlank()) {
      out.printf("[andro] %s (%s)%n", phase.label(), detail);
    }
  }

  @Override
  public void completePhase(CliPhase phase, Duration duration) {
    out.println(CompletionLine.format(phase, duration, true));
  }

  @Override
  public void seal() {
    sealed = true;
  }

  @Override
  public void fail(String message) {
    out.printf("[andro] failed: %s%n", message);
  }

  @Override
  public void printTimingSummary(List<PhaseTiming> timings, boolean plainSymbols) {
    if (timings == null || timings.isEmpty()) {
      return;
    }
    for (PhaseTiming timing : timings) {
      out.println(CompletionLine.format(timing.phase(), timing.duration(), plainSymbols));
    }
    out.println();
  }
}
