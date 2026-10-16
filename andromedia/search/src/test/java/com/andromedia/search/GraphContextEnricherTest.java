package com.andromedia.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class GraphContextEnricherTest {

  @Test
  void detectsGraphContextQuestions() {
    assertTrue(
        GraphContextEnricher.shouldIncludeGraphContext(
            "what happens if I change AnomalyEvent?"));
    assertFalse(GraphContextEnricher.shouldIncludeGraphContext("explain websocket flow"));
  }

  @Test
  void extractsSeedQueries() {
    List<String> seeds =
        GraphContextEnricher.extractSeedQueries(
            "what breaks if I change AnomalyKafkaListener#consumeAnomaly?");
    assertTrue(seeds.contains("AnomalyKafkaListener#consumeAnomaly"));
  }

  @Test
  void formatsGraphBlock() {
    String formatted =
        GraphContextEnricher.formatBlock(
            List.of("java:com.foo.AnomalyEvent"),
            TraversalDirection.REVERSE,
            List.of());
    assertTrue(formatted.contains("Seed symbols"));
    assertTrue(formatted.contains("No graph neighbors"));
  }
}
