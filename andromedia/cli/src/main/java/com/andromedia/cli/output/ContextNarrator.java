package com.andromedia.cli.output;

import com.andromedia.llm.AssembledContext;
import com.andromedia.llm.ContextBlock;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ContextNarrator {

  private static final int MAX_FILES = 5;
  private static final long VERBOSE_STAGGER_MS = 100;

  private ContextNarrator() {}

  public static void narrate(
      PrintStream out, AssembledContext context, boolean verbose, CliTheme theme) {
    if (context == null || context.blocks() == null || context.blocks().isEmpty()) {
      return;
    }
    List<String> files = uniqueFileNames(context.blocks());
    for (int i = 0; i < files.size(); i++) {
      SystemLines.println(out, theme, "Reading " + files.get(i) + "...");
      if (verbose && i < files.size() - 1) {
        sleep(VERBOSE_STAGGER_MS);
      }
    }
  }

  static List<String> uniqueFileNames(List<ContextBlock> blocks) {
    Set<String> seen = new LinkedHashSet<>();
    List<String> result = new ArrayList<>();
    for (ContextBlock block : blocks) {
      String name = fileLabel(block);
      if (name != null && !name.isBlank() && seen.add(name)) {
        result.add(name);
        if (result.size() >= MAX_FILES) {
          break;
        }
      }
    }
    return result;
  }

  private static String fileLabel(ContextBlock block) {
    if (block.fileName() != null && !block.fileName().isBlank()) {
      return block.fileName();
    }
    if (block.path() != null && !block.path().isBlank()) {
      String path = block.path();
      int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
      return slash >= 0 ? path.substring(slash + 1) : path;
    }
    return null;
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
  }
}
