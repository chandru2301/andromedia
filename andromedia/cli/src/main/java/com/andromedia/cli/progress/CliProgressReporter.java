package com.andromedia.cli.progress;

import java.time.Duration;
import java.util.List;

public interface CliProgressReporter {

  void startPhase(CliPhase phase);

  void updatePhase(CliPhase phase, String detail);

  void completePhase(CliPhase phase, Duration duration);

  /** Prevents further spinner phases; streaming may begin. */
  void seal();

  void fail(String message);

  void printTimingSummary(List<PhaseTiming> timings, boolean plainSymbols);

  /** Called after retrieval, context expansion, and prompt assembly in quiet ask mode. */
  default void preparationComplete(Duration totalDuration) {}

  /** Animated wait during retry backoff; override for spinner UX. */
  default void waitWithProgress(CliPhase phase, String detail, long totalMillis) {
    try {
      Thread.sleep(Math.max(0, totalMillis));
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
  }

  static CliProgressReporter create(boolean plain, boolean enabled) {
    return create(plain, enabled, ProgressMode.VERBOSE);
  }

  static CliProgressReporter create(boolean plain, boolean enabled, ProgressMode mode) {
    if (!enabled) {
      return new NoOpProgressReporter();
    }
    if (mode == ProgressMode.QUIET) {
      return new QuietProgressReporter(plain);
    }
    if (plain || !TerminalDetector.isInteractive(System.err)) {
      return new PlainProgressReporter();
    }
    return new TerminalProgressReporter();
  }
}
