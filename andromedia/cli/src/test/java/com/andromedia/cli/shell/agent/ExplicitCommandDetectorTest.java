package com.andromedia.cli.shell.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ExplicitCommandDetectorTest {

  @Test
  void detectsExplicitSubcommands() {
    assertTrue(ExplicitCommandDetector.isPicocliLine("search jwt"));
    assertTrue(ExplicitCommandDetector.isPicocliLine("ask -n 4 \"question\""));
    assertFalse(ExplicitCommandDetector.isPicocliLine("where is auth filter used?"));
    assertFalse(ExplicitCommandDetector.isPicocliLine("explain websocket flow"));
  }
}
