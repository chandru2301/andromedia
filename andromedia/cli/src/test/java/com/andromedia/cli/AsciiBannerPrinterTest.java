package com.andromedia.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class AsciiBannerPrinterTest {

  /**
   * The wordmark is block art, so every row must be the same display width or
   * the letters visibly shear. Box-drawing characters are single-width, so
   * {@code length()} is the right measure here.
   */
  @Test
  void everyWordmarkRowHasEqualWidth() {
    String banner = AsciiBannerPrinter.render();
    long distinctWidths =
        Arrays.stream(banner.split("\n"))
            .filter(line -> line.contains("█") || line.contains("╔") || line.contains("╚"))
            .mapToInt(String::length)
            .distinct()
            .count();
    assertEquals(1, distinctWidths, "wordmark rows have differing widths");
  }

  @Test
  void bannerContainsTagline() {
    assertTrue(AsciiBannerPrinter.render().contains("local-first"));
  }

  /**
   * Non-interactive output (pipes, CI, file redirects) must stay free of escape
   * sequences. The test JVM has no console, so this exercises the plain path.
   */
  @Test
  void plainOutputContainsNoEscapeSequences() {
    String banner = AsciiBannerPrinter.render();
    assertFalse(banner.contains("\u001B["), "banner emitted ANSI codes without a console");
  }

  @Test
  void bannerSpellsAndroInBlockLetters() {
    // Guard against a stray edit silently deforming the wordmark: the six rows
    // must together contain the distinctive glyphs of A, N, D, R, O.
    String banner = AsciiBannerPrinter.render();
    assertTrue(banner.contains("█████╗"), "missing the A/N block glyph");
    assertTrue(banner.contains("╚██████╔╝"), "missing the O block glyph");
    assertTrue(banner.contains("╚═════╝"), "missing the wordmark baseline");
  }
}
