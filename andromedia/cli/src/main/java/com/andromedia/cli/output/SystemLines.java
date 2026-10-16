package com.andromedia.cli.output;

import com.andromedia.cli.progress.DurationFormat;
import java.io.PrintStream;
import java.time.Duration;

public final class SystemLines {

  private SystemLines() {}

  public static void println(PrintStream out, CliTheme theme, String message) {
    out.println(theme.system(message));
    out.flush();
  }

  public static void printlnSuccess(PrintStream out, CliTheme theme, String message, Duration duration) {
    String suffix = duration == null || duration.isZero() ? null : DurationFormat.format(duration);
    out.println(theme.systemSuccess(message, suffix));
    out.flush();
  }
}
