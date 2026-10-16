package com.andromedia.cli.shell.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RuleBasedIntentResolverImpactTest {

  private final RuleBasedIntentResolver resolver = new RuleBasedIntentResolver();

  @Test
  void routesBlastRadiusQuestionsToImpact() {
    assertEquals(
        IntentType.IMPACT,
        resolver.resolve("what kafka consumers are affected if I change AnomalyEvent?"));
    assertEquals(IntentType.IMPACT, resolver.resolve("show blast radius for AnomalyEvent"));
    assertEquals(IntentType.IMPACT, resolver.resolve("what does UserService#saveUser use?"));
  }

  @Test
  void keepsUsagesSeparateFromImpact() {
    assertEquals(IntentType.USAGES, resolver.resolve("where is AnomalyEvent used?"));
    assertEquals(IntentType.USAGES, resolver.resolve("who uses TokenService"));
  }

  @Test
  void keepsSearchSeparateFromImpact() {
    assertEquals(IntentType.SEARCH, resolver.resolve("find kafka consumer retry"));
  }
}
