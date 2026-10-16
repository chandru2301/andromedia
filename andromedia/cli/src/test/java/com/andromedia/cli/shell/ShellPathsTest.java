package com.andromedia.cli.shell;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ShellPathsTest {

  @Test
  void historyUnderAndromediaHome() {
    assertTrue(ShellPaths.historyFile().toString().contains(".andromedia"));
    assertTrue(ShellPaths.historyFile().toString().endsWith("history"));
  }
}
