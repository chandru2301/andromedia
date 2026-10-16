package com.andromedia.search.grounding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ArchitecturePatternDetectorTest {

  private final ArchitecturePatternDetector detector = new ArchitecturePatternDetector();

  @Test
  void surfacesCacheReuseWhenPlanMentionsCache() {
    GroundingBundle bundle =
        new GroundingBundle(
            Set.of("/workspace/RedisCacheService.java"),
            Set.of("RedisCacheService.java"),
            Set.of("java:com.foo.RedisCacheService"),
            Set.of("RedisCacheService", "com.foo.RedisCacheService"),
            Set.of("com.foo"),
            Set.of(),
            List.of());

    List<ArchitectureHint> hints =
        detector.detect("add caching for anomaly events", bundle, Optional.empty());

    assertTrue(hints.stream().anyMatch(h -> h.symbolId().contains("RedisCacheService")));
    assertTrue(hints.stream().anyMatch(h -> "cache".equals(h.category())));
  }

  @Test
  void returnsEmptyWhenNoMatchingAbstractionExists() {
    GroundingBundle bundle =
        new GroundingBundle(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), List.of());

    List<ArchitectureHint> hints =
        detector.detect("add redis cache layer", bundle, Optional.empty());

    assertTrue(hints.isEmpty());
  }

  @Test
  void formatForPromptIncludesReuseSection() {
    List<ArchitectureHint> hints =
        List.of(
            new ArchitectureHint(
                "cache",
                "RedisCacheService",
                "RedisCacheService.java",
                "Reuse existing cache abstraction"));

    String formatted = ArchitecturePatternDetector.formatForPrompt(hints);
    assertTrue(formatted.contains("REUSE EXISTING PATTERNS"));
    assertTrue(formatted.contains("RedisCacheService"));
  }
}
