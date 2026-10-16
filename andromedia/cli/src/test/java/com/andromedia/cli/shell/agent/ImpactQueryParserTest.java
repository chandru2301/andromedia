package com.andromedia.cli.shell.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.andromedia.search.ImpactCategory;
import com.andromedia.search.TraversalDirection;
import org.junit.jupiter.api.Test;

class ImpactQueryParserTest {

  @Test
  void parsesKafkaConsumerBlastRadius() {
    var query =
        ImpactQueryParser.parse(
                "what kafka consumers are affected if I change AnomalyEvent?")
            .orElseThrow();

    assertEquals("AnomalyEvent", query.symbolQuery());
    assertEquals(TraversalDirection.REVERSE, query.direction());
    assertTrue(query.categoryFilters().contains(ImpactCategory.KAFKA_MESSAGING));
  }

  @Test
  void parsesServiceBreakQuestion() {
    var query =
        ImpactQueryParser.parse("if i modify anomalyevent what services break?").orElseThrow();

    assertEquals("anomalyevent", query.symbolQuery());
    assertEquals(TraversalDirection.REVERSE, query.direction());
    assertTrue(query.categoryFilters().contains(ImpactCategory.SERVICE));
  }

  @Test
  void parsesBlastRadiusPrompt() {
    var query = ImpactQueryParser.parse("show blast radius for AnomalyEvent").orElseThrow();

    assertEquals("AnomalyEvent", query.symbolQuery());
    assertEquals(TraversalDirection.REVERSE, query.direction());
    assertEquals(5, query.maxDepth());
  }

  @Test
  void parsesForwardDependencyQuestion() {
    var query = ImpactQueryParser.parse("what does UserService#saveUser use?").orElseThrow();

    assertEquals("UserService#saveUser", query.symbolQuery());
    assertEquals(TraversalDirection.FORWARD, query.direction());
  }

  @Test
  void parsesTestRerunQuestion() {
    var query =
        ImpactQueryParser.parse("what test suites should i rerun for AnomalyEvent?").orElseThrow();

    assertEquals("AnomalyEvent", query.symbolQuery());
    assertTrue(query.categoryFilters().contains(ImpactCategory.TEST));
  }

  @Test
  void detectsExplainIntent() {
    var query =
        ImpactQueryParser.parse("summarize blast radius for AnomalyEvent").orElseThrow();

    assertTrue(query.explain());
  }
}
