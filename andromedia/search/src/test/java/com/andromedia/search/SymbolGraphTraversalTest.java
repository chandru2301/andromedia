package com.andromedia.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.andromedia.common.SymbolGraph;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SymbolGraphTraversalTest {

  @Test
  void reverseTraversalFindsReferrersByHop() {
    SymbolGraph graph = graph(Map.of(
        "java:Controller#create", Set.of("java:Service#save"),
        "java:Service#save", Set.of("java:UserRepository"),
        "java:UserRepository", Set.of("java:User")));

    List<TraversalHit> hits =
        SymbolGraphTraversal.traverse(
            List.of("java:User"), graph, TraversalDirection.REVERSE, 5, 10);

    assertEquals(3, hits.size());
    assertEquals("java:UserRepository", hits.get(0).symbolId());
    assertEquals(1, hits.get(0).hop());
    assertEquals("java:Service#save", hits.get(1).symbolId());
    assertEquals(2, hits.get(1).hop());
    assertEquals("java:Controller#create", hits.get(2).symbolId());
    assertEquals(3, hits.get(2).hop());
  }

  @Test
  void forwardTraversalFindsDependenciesByHop() {
    SymbolGraph graph = graph(Map.of(
        "java:Controller#create", Set.of("java:Service#save"),
        "java:Service#save", Set.of("java:UserRepository"),
        "java:UserRepository", Set.of("java:User")));

    List<TraversalHit> hits =
        SymbolGraphTraversal.traverse(
            List.of("java:Controller#create"), graph, TraversalDirection.FORWARD, 5, 10);

    assertEquals(3, hits.size());
    assertEquals("java:Service#save", hits.get(0).symbolId());
    assertEquals(1, hits.get(0).hop());
    assertEquals("java:UserRepository", hits.get(1).symbolId());
    assertEquals("java:User", hits.get(2).symbolId());
  }

  @Test
  void cycleGraphTerminatesWithShortestHop() {
    SymbolGraph graph =
        graph(
            Map.of(
                "java:A", Set.of("java:B"),
                "java:B", Set.of("java:C"),
                "java:C", Set.of("java:A", "java:D")));

    List<TraversalHit> hits =
        SymbolGraphTraversal.traverse(
            List.of("java:A"), graph, TraversalDirection.FORWARD, 10, 10);

    assertEquals(3, hits.size());
    assertEquals("java:B", hits.get(0).symbolId());
    assertEquals(1, hits.get(0).hop());
    assertEquals("java:C", hits.get(1).symbolId());
    assertEquals(2, hits.get(1).hop());
    assertEquals("java:D", hits.get(2).symbolId());
    assertEquals(3, hits.get(2).hop());
    assertFalse(hits.stream().anyMatch(hit -> "java:A".equals(hit.symbolId())));
  }

  @Test
  void respectsMaxDepthAndLimit() {
    SymbolGraph graph =
        graph(
            Map.of(
                "java:A", Set.of("java:B"),
                "java:B", Set.of("java:C"),
                "java:C", Set.of("java:D")));

    List<TraversalHit> hits =
        SymbolGraphTraversal.traverse(
            List.of("java:A"), graph, TraversalDirection.FORWARD, 2, 1);

    assertEquals(1, hits.size());
    assertEquals("java:B", hits.get(0).symbolId());
    assertTrue(hits.stream().noneMatch(hit -> hit.symbolId().equals("java:C")));
  }

  private static SymbolGraph graph(Map<String, Set<String>> forward) {
    Map<String, Set<String>> reverse = new LinkedHashMap<>();
    for (Map.Entry<String, Set<String>> entry : forward.entrySet()) {
      for (String to : entry.getValue()) {
        reverse.computeIfAbsent(to, ignored -> new LinkedHashSet<>()).add(entry.getKey());
      }
    }
    return new SymbolGraph(forward, reverse, SymbolGraph.CURRENT_VERSION, "test");
  }
}
