package com.andromedia.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AndromediaCliRunnerTest {

  @Test
  void returnsTrueForRootInvocation() {
    assertTrue(AndromediaCliRunner.shouldPrintBanner(new String[0]));
  }

  @Test
  void returnsTrueForRootHelpInvocation() {
    assertTrue(AndromediaCliRunner.shouldPrintBanner(new String[] {"--help"}));
    assertTrue(AndromediaCliRunner.shouldPrintBanner(new String[] {"-h"}));
  }

  @Test
  void returnsFalseForSubcommandInvocation() {
    assertFalse(AndromediaCliRunner.shouldPrintBanner(new String[] {"search", "jwt"}));
  }

  @Test
  void returnsFalseForSubcommandHelpInvocation() {
    assertFalse(AndromediaCliRunner.shouldPrintBanner(new String[] {"search", "--help"}));
  }
}
