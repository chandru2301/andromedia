package com.andromedia.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class SymbolGraphStore {

  public static final String GRAPH_DIR = "graph";
  public static final String FORWARD_FILE = "forward.json";
  public static final String REVERSE_FILE = "reverse.json";
  public static final String MANIFEST_FILE = "manifest.json";

  private static final ObjectMapper MAPPER =
      new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  private SymbolGraphStore() {}

  public static Path graphDirectory(Path projectIndexPath) {
    return projectIndexPath.resolve(GRAPH_DIR);
  }

  public static void save(Path projectIndexPath, SymbolGraph graph) throws IOException {
    Path graphDir = graphDirectory(projectIndexPath);
    Files.createDirectories(graphDir);
    MAPPER.writeValue(graphDir.resolve(FORWARD_FILE).toFile(), graph.forwardEdges());
    MAPPER.writeValue(graphDir.resolve(REVERSE_FILE).toFile(), graph.reverseEdges());
    Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put("version", graph.version());
    manifest.put("builtAt", graph.builtAt());
    manifest.put("edgeCount", graph.edgeCount());
    MAPPER.writeValue(graphDir.resolve(MANIFEST_FILE).toFile(), manifest);
  }

  public static SymbolGraph load(Path projectIndexPath) throws IOException {
    Path graphDir = graphDirectory(projectIndexPath);
    Path forwardPath = graphDir.resolve(FORWARD_FILE);
    Path reversePath = graphDir.resolve(REVERSE_FILE);
    if (!Files.isRegularFile(forwardPath) || !Files.isRegularFile(reversePath)) {
      return SymbolGraph.empty();
    }
    LinkedHashMap<String, LinkedHashSet<String>> forwardRaw =
        MAPPER.readValue(forwardPath.toFile(), new TypeReference<LinkedHashMap<String, LinkedHashSet<String>>>() {});
    LinkedHashMap<String, LinkedHashSet<String>> reverseRaw =
        MAPPER.readValue(reversePath.toFile(), new TypeReference<LinkedHashMap<String, LinkedHashSet<String>>>() {});
    Map<String, Set<String>> forward = new LinkedHashMap<>(forwardRaw);
    Map<String, Set<String>> reverse = new LinkedHashMap<>(reverseRaw);
    String version = SymbolGraph.CURRENT_VERSION;
    String builtAt = "";
    Path manifestPath = graphDir.resolve(MANIFEST_FILE);
    if (Files.isRegularFile(manifestPath)) {
      Map<String, Object> manifest =
          MAPPER.readValue(manifestPath.toFile(), new TypeReference<LinkedHashMap<String, Object>>() {});
      Object versionValue = manifest.get("version");
      if (versionValue != null) {
        version = versionValue.toString();
      }
      Object builtAtValue = manifest.get("builtAt");
      if (builtAtValue != null) {
        builtAt = builtAtValue.toString();
      }
    }
    return new SymbolGraph(forward, reverse, version, builtAt);
  }

  public static boolean exists(Path projectIndexPath) {
    return Files.isRegularFile(graphDirectory(projectIndexPath).resolve(FORWARD_FILE));
  }

  public static SymbolGraph buildFromForward(Map<String, Set<String>> forwardEdges, Instant builtAt) {
    Map<String, Set<String>> reverse = new LinkedHashMap<>();
    for (Map.Entry<String, Set<String>> entry : forwardEdges.entrySet()) {
      String from = entry.getKey();
      for (String to : entry.getValue()) {
        reverse.computeIfAbsent(to, ignored -> new LinkedHashSet<>()).add(from);
      }
    }
    return new SymbolGraph(
        forwardEdges,
        reverse,
        SymbolGraph.CURRENT_VERSION,
        builtAt == null ? Instant.now().toString() : builtAt.toString());
  }
}
