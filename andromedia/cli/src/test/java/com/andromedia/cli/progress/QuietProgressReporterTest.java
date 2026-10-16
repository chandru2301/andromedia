package com.andromedia.cli.progress;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.andromedia.cli.output.CliTheme;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class QuietProgressReporterTest {

  @Test
  void collapsesPhasesIntoContextReady() {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    PrintStream err = new PrintStream(buffer, true);
    QuietProgressReporter reporter = new QuietProgressReporter(err, true);

    reporter.startPhase(CliPhase.HYBRID_RETRIEVAL);
    reporter.completePhase(CliPhase.HYBRID_RETRIEVAL, Duration.ofMillis(50));
    reporter.startPhase(CliPhase.EXPANDING_CONTEXT);
    reporter.completePhase(CliPhase.EXPANDING_CONTEXT, Duration.ofMillis(10));
    reporter.preparationComplete(Duration.ofMillis(60));

    String text = CliTheme.stripAnsi(buffer.toString(StandardCharsets.UTF_8));
    assertTrue(text.contains("Analyzing codebase"));
    assertTrue(text.contains("Context ready"));
    assertFalse(text.contains("Hybrid retrieval completed"));
    assertFalse(text.contains("Model connected"));
  }
}
