package com.andromedia.search;

import com.andromedia.common.SymbolGraph;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.function.Function;

public final class SymbolGraphTraversal {

  private SymbolGraphTraversal() {}

  public static List<TraversalHit> traverse(
      List<String> seedSymbolIds,
      SymbolGraph graph,
      TraversalDirection direction,
      int maxDepth,
      int maxResults) {
    Function<String, Set<String>> expand =
        direction == TraversalDirection.REVERSE ? graph::referrers : graph::dependencies;
    Map<String, Integer> hops = bfsHops(seedSymbolIds, expand, maxDepth);
    hops.keySet().removeAll(seedSymbolIds);

    return hops.entrySet().stream()
        .sorted(
            Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                .thenComparing(Map.Entry::getKey))
        .limit(Math.max(1, maxResults))
        .map(entry -> new TraversalHit(entry.getKey(), entry.getValue()))
        .toList();
  }

  static Map<String, Integer> bfsHops(
      List<String> seeds, Function<String, Set<String>> expand, int maxDepth) {
    Map<String, Integer> hops = new LinkedHashMap<>();
    Queue<String> queue = new ArrayDeque<>();
    Set<String> visited = new HashSet<>(seeds);

    for (String seed : seeds) {
      for (String neighbor : expand.apply(seed)) {
        if (visited.add(neighbor)) {
          hops.put(neighbor, 1);
          queue.add(neighbor);
        }
      }
    }

    while (!queue.isEmpty()) {
      String current = queue.poll();
      int hop = hops.get(current);
      if (hop >= maxDepth) {
        continue;
      }
      for (String neighbor : expand.apply(current)) {
        if (visited.add(neighbor)) {
          hops.put(neighbor, hop + 1);
          queue.add(neighbor);
        }
      }
    }
    return hops;
  }
}
