package com.andromedia.cli.progress;

import com.andromedia.cli.output.BoxRenderer;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

public final class AskMetricsFooter {

  private AskMetricsFooter() {}

  public static void print(PrintStream out, List<PhaseTiming> summaryTimings, boolean verbose) {
    if (!verbose || summaryTimings == null || summaryTimings.isEmpty()) {
      return;
    }
    List<String> lines = new ArrayList<>();
    for (PhaseTiming timing : summaryTimings) {
      lines.add(
          timing.phase().footerMetricLabel()
              + ": "
              + DurationFormat.format(timing.duration()));
    }
    for (String line : BoxRenderer.box("Timing", lines)) {
      out.println(line);
    }
    out.println();
  }
}
