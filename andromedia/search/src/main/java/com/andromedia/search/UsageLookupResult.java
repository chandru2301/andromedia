package com.andromedia.search;

import java.time.Duration;
import java.util.List;

public record UsageLookupResult(
    List<UsageSite> definitions,
    List<UsageSite> references,
    List<String> resolvedSymbolIds,
    Duration elapsed,
    List<MethodHint> methodHints) {

  public UsageLookupResult {
    methodHints = methodHints == null ? List.of() : List.copyOf(methodHints);
  }

  public UsageLookupResult(
      List<UsageSite> definitions,
      List<UsageSite> references,
      List<String> resolvedSymbolIds,
      Duration elapsed) {
    this(definitions, references, resolvedSymbolIds, elapsed, List.of());
  }
}
