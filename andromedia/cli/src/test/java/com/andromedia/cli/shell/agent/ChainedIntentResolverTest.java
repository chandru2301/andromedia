package com.andromedia.cli.shell.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ChainedIntentResolverTest {

  private final ChainedIntentResolver resolver =
      new ChainedIntentResolver(new RuleBasedIntentResolver(), null);

  @Test
  void keepsHighConfidenceRuleIntents() {
    ShellIntent intent = resolver.resolve("usages of AnomalyEvent");
    assertEquals(IntentType.USAGES, intent.intent());
  }

  @Test
  void infersGraphContextForAskFallback() {
    ShellIntent intent = resolver.resolve("explain websocket flow");
    assertEquals(IntentType.ASK, intent.intent());
    assertEquals(false, intent.graphContext());
  }
}
