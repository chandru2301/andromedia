package com.andromedia.cli.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AgentShellRoutingTest {

  @Test
  void classifiesLineTypes() {
    assertEquals(AgentShellRouting.SYSTEM, AgentShellRouting.classify("/help"));
    assertEquals(AgentShellRouting.SYSTEM, AgentShellRouting.classify("exit"));
    assertEquals(AgentShellRouting.EXPLICIT_PICOCLI, AgentShellRouting.classify("search jwt"));
    assertEquals(
        AgentShellRouting.EXPLICIT_PICOCLI, AgentShellRouting.classify("index --recreate ."));
    assertEquals(
        AgentShellRouting.EXPLICIT_PICOCLI, AgentShellRouting.classify("/index --recreate ."));
    assertEquals(
        AgentShellRouting.NATURAL_LANGUAGE,
        AgentShellRouting.classify("where is auth filter used?"));
  }
}
