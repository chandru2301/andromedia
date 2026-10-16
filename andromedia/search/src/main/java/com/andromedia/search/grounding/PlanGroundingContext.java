package com.andromedia.search.grounding;

import com.andromedia.search.GraphContextEnricher;
import com.andromedia.search.ImpactLookupResult;
import com.andromedia.search.SearchResult;
import com.andromedia.search.UsageLookupResult;
import java.util.List;
import java.util.Optional;

/** Inputs collected during plan generation for grounding. */
public record PlanGroundingContext(
    String description,
    String symbolQuery,
    SearchResult searchResult,
    Optional<ImpactLookupResult> reverseImpact,
    Optional<ImpactLookupResult> forwardImpact,
    Optional<GraphContextEnricher.GraphContextBlock> graphBlock,
    Optional<UsageLookupResult> usagesResult) {

  public static PlanGroundingContext of(
      String description,
      String symbolQuery,
      SearchResult searchResult) {
    return new PlanGroundingContext(
        description, symbolQuery, searchResult, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
  }
}
