package com.andromedia.cli.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StreamingAnswerSinkTest {

  @Test
  void rewritesDashBulletsAndHeadings() {
    CliTheme theme = new CliTheme(true);
    assertEquals("  • slf4j-api [1]", StreamingAnswerSink.formatLine("- slf4j-api [1]", theme));
    assertEquals("Logging", StreamingAnswerSink.formatLine("## Logging", theme));
  }

  @Test
  void formatsBufferedAnswer() {
    CliTheme theme = new CliTheme(true);
    String formatted =
        StreamingAnswerSink.formatBufferedAnswer(
            "## Dependencies\n- spring-kafka [1]\n- spring-data-redis [2]", theme);
    assertTrue(formatted.contains("Dependencies"));
    assertTrue(formatted.contains("• spring-kafka [1]"));
    assertFalse(formatted.contains("- spring-kafka"));
  }
}
