package com.andromedia.indexing;

import com.andromedia.common.CodeChunk;
import com.andromedia.common.CodeSymbol;
import com.andromedia.common.SymbolGraph;
import com.andromedia.common.SymbolGraphStore;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SymbolGraphBuilder {

  private final Map<String, Set<String>> forwardEdges = new LinkedHashMap<>();

  public void addChunk(CodeChunk chunk) {
    if (chunk == null || chunk.metadata() == null) {
      return;
    }
    List<String> referenced = chunk.metadata().referencedSymbolIds();
    if (referenced == null || referenced.isEmpty()) {
      return;
    }
    List<String> fromSymbols =
        chunk.metadata().symbols().stream().map(CodeSymbol::symbolId).filter(id -> id != null && !id.isBlank()).toList();
    if (fromSymbols.isEmpty()) {
      return;
    }
    for (String from : fromSymbols) {
      Set<String> targets = forwardEdges.computeIfAbsent(from, ignored -> new LinkedHashSet<>());
      for (String to : referenced) {
        if (to != null && !to.isBlank() && !to.equals(from)) {
          targets.add(to);
        }
      }
    }
  }

  public SymbolGraph build() {
    return SymbolGraphStore.buildFromForward(forwardEdges, Instant.now());
  }

  public int pendingEdgeCount() {
    return forwardEdges.values().stream().mapToInt(Set::size).sum();
  }
}
