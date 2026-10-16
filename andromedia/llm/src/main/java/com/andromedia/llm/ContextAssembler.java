package com.andromedia.llm;

import com.andromedia.common.ChunkUnitType;
import com.andromedia.common.IndexConstants;
import com.andromedia.common.RetrievalReference;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ContextAssembler {

  private final ContextExpansionProperties properties;

  public ContextAssembler(ContextExpansionProperties properties) {
    this.properties = properties;
  }

  public AssembledContext assemble(List<RetrievalReference> hits) {
    Map<String, ContextBlock> blocksByKey = new LinkedHashMap<>();

    for (RetrievalReference hit : hits) {
      if (hit.isGitHistory() && hit.indexedContent().isPresent()) {
        addBlock(blocksByKey, buildGitHistoryBlock(hit, hit.indexedContent().orElseThrow()));
        continue;
      }
      if (hit.path() == null || hit.path().isBlank()) {
        continue;
      }
      Path sourcePath = Path.of(hit.path());
      if (!Files.isRegularFile(sourcePath)) {
        continue;
      }

      try {
        List<String> lines = Files.readAllLines(sourcePath, StandardCharsets.UTF_8);
        addBlock(blocksByKey, buildHitBlock(hit, lines));
        if (hit.unitType() == ChunkUnitType.METHOD) {
          addBlock(blocksByKey, buildFileHeaderBlock(hit, lines, properties.maxHeaderLines()));
        }
      } catch (IOException ex) {
        continue;
      }
    }

    return new AssembledContext(List.copyOf(blocksByKey.values()));
  }

  private static ContextBlock buildGitHistoryBlock(RetrievalReference hit, String content) {
    String label = "[GIT] " + hit.fileName();
    return new ContextBlock(
        hit.chunkId(),
        hit.path(),
        label,
        content,
        hit.fileName(),
        hit.className(),
        hit.methodName(),
        hit.startLine(),
        hit.endLine());
  }

  private ContextBlock buildHitBlock(RetrievalReference hit, List<String> lines) {
    int start = clampLine(hit.startLine(), lines.size());
    int end = clampLine(hit.endLine(), lines.size());
    int windowStart = Math.max(1, start - properties.neighborLineWindow());
    int windowEnd = Math.min(lines.size(), end + properties.neighborLineWindow());
    String content = joinLines(lines, windowStart, windowEnd);
    String label = buildLabel(hit, windowStart, windowEnd);
    return new ContextBlock(
        hit.chunkId(),
        hit.path(),
        label,
        content,
        hit.fileName(),
        hit.className(),
        hit.methodName(),
        start,
        end);
  }

  private static ContextBlock buildFileHeaderBlock(
      RetrievalReference hit, List<String> lines, int maxHeaderLines) {
    int headerEnd = findHeaderEndLine(lines, maxHeaderLines);
    if (headerEnd <= 0) {
      return null;
    }
    String content = joinLines(lines, 1, headerEnd);
    String label = hit.fileName() + ":header";
    String key = hit.path() + "#header";
    return new ContextBlock(
        key,
        hit.path(),
        label,
        content,
        hit.fileName(),
        hit.className(),
        null,
        1,
        headerEnd);
  }

  private static void addBlock(Map<String, ContextBlock> blocksByKey, ContextBlock block) {
    if (block == null) {
      return;
    }
    String key = block.chunkId() == null ? block.path() + ":" + block.label() : block.chunkId();
    blocksByKey.putIfAbsent(key, block);
  }

  private static String buildLabel(RetrievalReference hit, int windowStart, int windowEnd) {
    String symbol = hit.methodName() != null ? hit.methodName() : hit.className();
    if (symbol == null || symbol.isBlank()) {
      return hit.fileName() + ":" + windowStart + "-" + windowEnd;
    }
    return hit.fileName() + ":" + symbol + ":" + windowStart + "-" + windowEnd;
  }

  private static int findHeaderEndLine(List<String> lines, int maxHeaderLines) {
    int limit = Math.min(lines.size(), maxHeaderLines);
    int lastHeaderLine = 0;
    for (int i = 0; i < limit; i++) {
      String trimmed = lines.get(i).trim();
      if (trimmed.startsWith("package ")
          || trimmed.startsWith("import ")
          || trimmed.startsWith("@")
          || trimmed.isEmpty()) {
        lastHeaderLine = i + 1;
        continue;
      }
      if (trimmed.startsWith("public ")
          || trimmed.startsWith("class ")
          || trimmed.startsWith("interface ")
          || trimmed.startsWith("enum ")
          || trimmed.startsWith("record ")) {
        lastHeaderLine = i + 1;
        break;
      }
    }
    return lastHeaderLine;
  }

  private static int clampLine(int line, int lineCount) {
    if (line <= 0) {
      return 1;
    }
    return Math.min(line, Math.max(1, lineCount));
  }

  private static String joinLines(List<String> lines, int startLineInclusive, int endLineInclusive) {
    StringBuilder builder = new StringBuilder();
    for (int line = startLineInclusive; line <= endLineInclusive; line++) {
      builder.append(lines.get(line - 1));
      if (line < endLineInclusive) {
        builder.append('\n');
      }
    }
    return builder.toString();
  }
}
