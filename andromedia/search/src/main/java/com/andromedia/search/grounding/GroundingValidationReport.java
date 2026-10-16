package com.andromedia.search.grounding;

import java.util.List;

public record GroundingValidationReport(
    List<String> verifiedReferences,
    List<String> proposedReferences,
    List<String> reuseSuggestions,
    int verifiedCount,
    int proposedCount) {

  public static GroundingValidationReport empty() {
    return new GroundingValidationReport(List.of(), List.of(), List.of(), 0, 0);
  }

  public String summaryLine() {
    return verifiedCount + " verified, " + proposedCount + " proposed";
  }
}
