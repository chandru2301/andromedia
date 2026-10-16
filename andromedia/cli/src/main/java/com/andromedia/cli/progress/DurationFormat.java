package com.andromedia.cli.progress;

import java.time.Duration;

public final class DurationFormat {

  private DurationFormat() {}

  public static String format(Duration duration) {
    if (duration == null || duration.isZero() || duration.isNegative()) {
      return "0ms";
    }
    long millis = duration.toMillis();
    if (millis < 1000) {
      return millis + "ms";
    }
    double seconds = millis / 1000.0;
    if (seconds < 600) {
      return String.format("%.1fs", seconds);
    }
    long minutes = duration.toMinutes();
    long remainingSeconds = duration.toSecondsPart();
    if (remainingSeconds == 0) {
      return minutes + "m";
    }
    return String.format("%dm %ds", minutes, remainingSeconds);
  }
}
