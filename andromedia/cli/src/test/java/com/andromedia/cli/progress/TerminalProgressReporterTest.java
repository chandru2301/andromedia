package com.andromedia.cli.progress;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class TerminalProgressReporterTest {

  @Test
  void completePhasePrintsCheckmarkAndSealBlocksSpinner() {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    TerminalProgressReporter reporter = new TerminalProgressReporter(new PrintStream(buffer, true));

    reporter.startPhase(CliPhase.HYBRID_RETRIEVAL);
    reporter.completePhase(CliPhase.HYBRID_RETRIEVAL, Duration.ofMillis(100));
    reporter.seal();
    reporter.startPhase(CliPhase.WAITING_FOR_MODEL);

    String output = buffer.toString(StandardCharsets.UTF_8);
    assertTrue(output.contains("✓ Search completed (100ms)"));
    assertFalse(output.contains("Thinking"));
  }
}
