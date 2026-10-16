package com.andromedia.cli.output;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CliThemeTest {

  @Test
  void plainModeUsesTextPrefixes() {
    CliTheme theme = new CliTheme(true);
    assertTrue(theme.system("status").startsWith("[system]"));
    assertTrue(theme.assistantPrefix().startsWith("[assistant]"));
    assertTrue(theme.systemSuccess("done", "1ms").contains("[system]"));
  }
}
