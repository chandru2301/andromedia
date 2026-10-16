package com.andromedia.cli.shell.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RuleBasedIntentResolverTest {

  private final RuleBasedIntentResolver resolver = new RuleBasedIntentResolver();

  @Test
  void routesLocationQuestionsToSearch() {
    assertEquals(IntentType.SEARCH, resolver.resolve("where is auth filter implemented?"));
    assertEquals(IntentType.SEARCH, resolver.resolve("find kafka consumer retry"));
    assertEquals(IntentType.SEARCH, resolver.resolve("show me websocket handlers"));
  }

  @Test
  void routesUsageQuestionsToUsages() {
    assertEquals(IntentType.USAGES, resolver.resolve("where is auth filter used?"));
    assertEquals(IntentType.USAGES, resolver.resolve("usages of AnomalyEvent"));
    assertEquals(IntentType.USAGES, resolver.resolve("who uses TokenService"));
  }

  @Test
  void routesExplanationsToAsk() {
    assertEquals(IntentType.ASK, resolver.resolve("explain websocket flow"));
    assertEquals(IntentType.ASK, resolver.resolve("why is reconnect failing?"));
    assertEquals(IntentType.ASK, resolver.resolve("explain where failures occur"));
  }

  @Test
  void routesFixPhrasesToFix() {
    assertEquals(IntentType.FIX, resolver.resolve("fix null pointer in listener"));
    assertEquals(IntentType.FIX, resolver.resolve("debug this bug in kafka consumer"));
  }

  @Test
  void routesIndexWithPath() {
    assertEquals(IntentType.INDEX, resolver.resolve("index /home/user/project"));
    assertEquals(IntentType.INDEX, resolver.resolve("reindex ./src"));
  }
}
