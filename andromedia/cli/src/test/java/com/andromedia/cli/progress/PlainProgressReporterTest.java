package com.andromedia.cli.progress;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class PlainProgressReporterTest {

  @Test
  void emitsPhaseLinesAndCompletionCheckmarks() {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    PlainProgressReporter reporter = new PlainProgressReporter(new PrintStream(buffer, true));

    reporter.startPhase(CliPhase.HYBRID_RETRIEVAL);
    reporter.updatePhase(CliPhase.HYBRID_RETRIEVAL, "3 chunks");
    reporter.completePhase(CliPhase.HYBRID_RETRIEVAL, Duration.ofMillis(10));
    reporter.startPhase(CliPhase.EXPANDING_CONTEXT);
    reporter.completePhase(CliPhase.EXPANDING_CONTEXT, Duration.ofMillis(5));
    reporter.startPhase(CliPhase.WAITING_FOR_MODEL);
    reporter.completePhase(CliPhase.WAITING_FOR_MODEL, Duration.ofMillis(20));

    String output = buffer.toString(StandardCharsets.UTF_8);
    assertTrue(output.indexOf("Searching codebase") < output.indexOf("Analysing context"));
    assertTrue(output.contains("3 chunks"));
    assertTrue(output.contains("[ok] Search completed (10ms)"));
    assertTrue(output.contains("[ok] Context ready (5ms)"));
    assertTrue(output.contains("[ok] Ready"));
  }

  @Test
  void sealBlocksFurtherPhases() {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    PlainProgressReporter reporter = new PlainProgressReporter(new PrintStream(buffer, true));

    reporter.startPhase(CliPhase.HYBRID_RETRIEVAL);
    reporter.seal();
    reporter.startPhase(CliPhase.WAITING_FOR_MODEL);

    String output = buffer.toString(StandardCharsets.UTF_8);
    assertTrue(output.contains("Searching codebase"));
    assertTrue(!output.contains("Thinking"));
  }
}
