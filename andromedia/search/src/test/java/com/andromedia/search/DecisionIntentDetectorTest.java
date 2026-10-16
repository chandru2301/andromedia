package com.andromedia.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DecisionIntentDetectorTest {

  @Test
  void detectsDecisionLanguage() {
    assertTrue(DecisionIntentDetector.isDecisionIntent("what did the team decide about Kafka"));
    assertTrue(DecisionIntentDetector.isDecisionIntent("why did we choose async publish"));
    assertTrue(DecisionIntentDetector.isDecisionIntent("we agreed to use Feign"));
  }

  @Test
  void ignoresPureImplementationQueries() {
    assertFalse(DecisionIntentDetector.isDecisionIntent("UserService"));
    assertFalse(DecisionIntentDetector.isDecisionIntent("token validation logic"));
  }

  @Test
  void sanitizeForRetrievalDropsDecisionFiller() {
    assertEquals(
        "did we to switch to kafka",
        DecisionIntentDetector.sanitizeForRetrieval("why did we decide to switch to Kafka?"));
    assertEquals(
        "what did the team about kafka",
        DecisionIntentDetector.sanitizeForRetrieval("what did the team decide about Kafka"));
  }
}
