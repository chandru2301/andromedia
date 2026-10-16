package com.andromedia.search.grounding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.andromedia.search.UsageLookupResult;
import com.andromedia.search.UsagesLookupService;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PlanGroundingValidatorTest {

  @Mock private UsagesLookupService usagesLookupService;

  @Test
  void classifiesVerifiedAndProposedReferences() {
    when(usagesLookupService.lookup(any()))
        .thenReturn(new UsageLookupResult(List.of(), List.of(), List.of(), Duration.ZERO));

    GroundingBundle bundle =
        new GroundingBundle(
            Set.of("/workspace/src/AnomalyKafkaListener.java"),
            Set.of("AnomalyKafkaListener.java"),
            Set.of("java:com.foo.AnomalyKafkaListener#consumeAnomaly"),
            Set.of("AnomalyKafkaListener#consumeAnomaly"),
            Set.of(),
            Set.of(),
            List.of());

    PlanGroundingValidator validator = new PlanGroundingValidator(usagesLookupService);
    String markdown =
        """
        ## Critical files
        - AnomalyKafkaListener.java
        - AnomalyConsumer.java

        ### Critical Files for Implementation
        - src/AnomalyKafkaListener.java
        - Suggested new file: AnomalyConsumer.java
        """;

    GroundingValidationReport report =
        validator.validate(markdown, bundle, List.of(), Path.of("/workspace"));

    assertTrue(report.verifiedCount() >= 1);
    assertTrue(report.proposedCount() >= 1);
    assertTrue(report.proposedReferences().stream().anyMatch(r -> r.contains("AnomalyConsumer")));
  }

  @Test
  void appendsGroundingValidationSection() {
    PlanGroundingValidator validator = new PlanGroundingValidator(usagesLookupService);
    GroundingValidationReport report =
        new GroundingValidationReport(
            List.of("AnomalyKafkaListener.java"),
            List.of("AnomalyConsumer.java — not in index"),
            List.of("Prefer RedisCacheService over AnomalyCache.java"),
            1,
            1);

    String result =
        validator.appendValidationSection("## Requirements\nDo thing", report, List.of());

    assertTrue(result.contains("## Grounding validation"));
    assertTrue(result.contains("### Verified"));
    assertTrue(result.contains("### Suggested new (not in index)"));
    assertTrue(result.contains("### Reuse instead of inventing"));
  }
}
