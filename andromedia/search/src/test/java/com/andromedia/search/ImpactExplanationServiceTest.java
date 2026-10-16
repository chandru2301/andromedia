package com.andromedia.search;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.andromedia.common.SymbolType;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ImpactExplanationServiceTest {

  @Test
  void formatResultsIncludesDeterministicSitesOnly() {
    ImpactLookupResult result =
        new ImpactLookupResult(
            List.of("java:com.foo.AnomalyEvent"),
            TraversalDirection.REVERSE,
            List.of(
                new ImpactSite(
                    "java:com.foo.AnomalyKafkaListener#consumeAnomaly",
                    "consumeAnomaly",
                    SymbolType.METHOD,
                    "src/Listener.java",
                    10,
                    1,
                    ImpactCategory.KAFKA_MESSAGING)),
            Map.of(),
            Duration.ZERO);

    String formatted = ImpactExplanationService.formatResults(result);

    assertTrue(formatted.contains("java:com.foo.AnomalyKafkaListener#consumeAnomaly"));
    assertTrue(formatted.contains("hop=1"));
  }
}
