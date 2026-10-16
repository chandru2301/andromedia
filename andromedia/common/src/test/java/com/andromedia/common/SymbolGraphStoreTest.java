package com.andromedia.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SymbolGraphStoreTest {

  @TempDir Path tempDir;

  @Test
  void savesAndLoadsForwardAndReverseEdges() throws Exception {
    Map<String, Set<String>> forward = new LinkedHashMap<>();
    forward.put("java:com.foo.Service#save", Set.of("java:com.foo.UserRepository"));
    forward.put("java:com.foo.UserRepository", Set.of("java:com.foo.User"));

    SymbolGraph graph = SymbolGraphStore.buildFromForward(forward, Instant.parse("2026-05-29T00:00:00Z"));
    SymbolGraphStore.save(tempDir, graph);

    SymbolGraph loaded = SymbolGraphStore.load(tempDir);
    assertEquals("java:com.foo.UserRepository", loaded.dependencies("java:com.foo.Service#save").iterator().next());
    assertEquals("java:com.foo.Service#save", loaded.referrers("java:com.foo.UserRepository").iterator().next());
    assertTrue(SymbolGraphStore.exists(tempDir));
    assertEquals(2, loaded.edgeCount());
  }
}
