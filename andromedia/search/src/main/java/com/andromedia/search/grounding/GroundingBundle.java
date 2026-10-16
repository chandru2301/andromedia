package com.andromedia.search.grounding;

import java.util.List;
import java.util.Set;

public record GroundingBundle(
    Set<String> verifiedFilePaths,
    Set<String> verifiedFileNames,
    Set<String> verifiedSymbolIds,
    Set<String> verifiedSymbolNames,
    Set<String> verifiedPackages,
    Set<String> verifiedConfigPaths,
    List<String> unresolvedQueries) {

  public static GroundingBundle empty() {
    return new GroundingBundle(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), List.of());
  }

  public int fileCount() {
    return verifiedFilePaths.size();
  }

  public int symbolCount() {
    return verifiedSymbolIds.size();
  }
}
