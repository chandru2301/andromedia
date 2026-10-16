package com.andromedia.cli.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class DurationFormatTest {

  @Test
  void formatsSubSecondAndLongDurations() {
    assertEquals("7ms", DurationFormat.format(Duration.ofMillis(7)));
    assertEquals("3.0s", DurationFormat.format(Duration.ofMillis(2983)));
    assertEquals("74.4s", DurationFormat.format(Duration.ofMillis(74383)));
  }
}
