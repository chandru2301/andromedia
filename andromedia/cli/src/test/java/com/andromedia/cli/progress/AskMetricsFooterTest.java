package com.andromedia.cli.progress;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class AskMetricsFooterTest {

  @Test
  void printsFooterWithHumanReadableDurations() {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(buffer, true);

    AskMetricsFooter.print(
        out,
        List.of(
            new PhaseTiming(CliPhase.HYBRID_RETRIEVAL, Duration.ofMillis(2983)),
            new PhaseTiming(CliPhase.EXPANDING_CONTEXT, Duration.ofMillis(7)),
            new PhaseTiming(CliPhase.WAITING_FOR_MODEL, Duration.ofMillis(74383))),
        true);

    String text = buffer.toString(StandardCharsets.UTF_8);
    assertTrue(text.contains("Timing"));
    assertTrue(text.contains("Search: 3.0s"));
    assertTrue(text.contains("Context: 7ms"));
    assertTrue(text.contains("Generation: 74.4s"));
  }

  @Test
  void hiddenWhenNotVerbose() {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    AskMetricsFooter.print(
        new PrintStream(buffer, true),
        List.of(new PhaseTiming(CliPhase.HYBRID_RETRIEVAL, Duration.ofMillis(100))),
        false);
    assertTrue(buffer.toString(StandardCharsets.UTF_8).isBlank());
  }
}
