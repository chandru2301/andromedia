package com.andromedia.common;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

public record SymbolGraph(
    Map<String, Set<String>> forwardEdges,
    Map<String, Set<String>> reverseEdges,
    String version,
    String builtAt) {

  public static final String CURRENT_VERSION = "1";

  public SymbolGraph {
    forwardEdges = forwardEdges == null ? Map.of() : Map.copyOf(forwardEdges);
    reverseEdges = reverseEdges == null ? Map.of() : Map.copyOf(reverseEdges);
    version = version == null ? CURRENT_VERSION : version;
    builtAt = builtAt == null ? "" : builtAt;
  }

  public static SymbolGraph empty() {
    return new SymbolGraph(Collections.emptyMap(), Collections.emptyMap(), CURRENT_VERSION, "");
  }

  public int edgeCount() {
    return forwardEdges.values().stream().mapToInt(Set::size).sum();
  }

  public Set<String> referrers(String symbolId) {
    return reverseEdges.getOrDefault(symbolId, Set.of());
  }

  public Set<String> dependencies(String symbolId) {
    return forwardEdges.getOrDefault(symbolId, Set.of());
  }
}
