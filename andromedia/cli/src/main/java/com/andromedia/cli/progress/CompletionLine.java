package com.andromedia.cli.progress;

import java.time.Duration;

final class CompletionLine {

  private CompletionLine() {}

  static String format(CliPhase phase, Duration duration, boolean plainSymbols) {
    String mark = plainSymbols ? "[ok]" : "✓";
    if (phase.showsDurationOnCompletion() && duration != null && !duration.isZero()) {
      return String.format("%s %s (%s)", mark, phase.completedLabel(), DurationFormat.format(duration));
    }
    return String.format("%s %s", mark, phase.completedLabel());
  }
}
