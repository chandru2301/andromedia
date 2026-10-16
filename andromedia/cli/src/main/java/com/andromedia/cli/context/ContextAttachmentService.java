package com.andromedia.cli.context;

import com.andromedia.common.IndexPathResolver;
import com.andromedia.llm.PromptAttachments;
import com.andromedia.search.GraphContextEnricher;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ContextAttachmentService {

  private static final int RULES_BUDGET = 2000;
  private static final int MEMORY_BUDGET = 1500;
  private static final int FILE_ATTACHMENT_BUDGET = 3000;

  private final ProjectRulesLoader projectRulesLoader;
  private final ProjectMemoryLoader projectMemoryLoader;
  private final GraphContextEnricher graphContextEnricher;

  public ContextAttachmentService(
      ProjectRulesLoader projectRulesLoader,
      ProjectMemoryLoader projectMemoryLoader,
      GraphContextEnricher graphContextEnricher) {
    this.projectRulesLoader = projectRulesLoader;
    this.projectMemoryLoader = projectMemoryLoader;
    this.graphContextEnricher = graphContextEnricher;
  }

  public PromptAttachments loadForAsk(
      String question, boolean graphContext, List<String> filePaths, String exploreContext) {
    Path workspace =
        IndexPathResolver.resolveWorkspaceRoot(Path.of(System.getProperty("user.dir", ".")));
    ProjectRulesLoader.LoadedRules rules = projectRulesLoader.load(workspace, RULES_BUDGET);
    ProjectMemoryLoader.LoadedMemory memory =
        projectMemoryLoader.load(workspace, question, MEMORY_BUDGET);
    String graph =
        graphContext
            ? graphContextEnricher
                .enrich(question, 3, 15)
                .map(GraphContextEnricher.GraphContextBlock::text)
                .orElse("")
            : "";
    String files = loadFileAttachments(filePaths, workspace);
    return new PromptAttachments(
        rules.text(), memory.text(), graph, files, exploreContext == null ? "" : exploreContext);
  }

  public PromptAttachments loadForPlan(String description, List<String> filePaths) {
    Path workspace =
        IndexPathResolver.resolveWorkspaceRoot(Path.of(System.getProperty("user.dir", ".")));
    ProjectRulesLoader.LoadedRules rules = projectRulesLoader.load(workspace, RULES_BUDGET);
    ProjectMemoryLoader.LoadedMemory memory =
        projectMemoryLoader.load(workspace, description, MEMORY_BUDGET);
    String files = loadFileAttachments(filePaths, workspace);
    return new PromptAttachments(rules.text(), memory.text(), "", files, "");
  }

  private static String loadFileAttachments(List<String> filePaths, Path workspace) {
    if (filePaths == null || filePaths.isEmpty()) {
      return "";
    }
    StringBuilder builder = new StringBuilder();
    for (String rawPath : filePaths) {
      if (rawPath == null || rawPath.isBlank()) {
        continue;
      }
      Path path = Path.of(rawPath);
      if (!path.isAbsolute()) {
        path = workspace.resolve(rawPath).normalize();
      }
      if (!Files.isRegularFile(path)) {
        continue;
      }
      try {
        String content = Files.readString(path, StandardCharsets.UTF_8);
        String section =
            """
            --- Attached file: %s ---
            %s
            """
                .formatted(path, truncate(content, FILE_ATTACHMENT_BUDGET));
        if (builder.length() + section.length() > FILE_ATTACHMENT_BUDGET) {
          break;
        }
        builder.append(section).append('\n');
      } catch (IOException ignored) {
        // skip unreadable attachment
      }
    }
    return builder.toString().trim();
  }

  private static String truncate(String content, int maxChars) {
    if (content.length() <= maxChars) {
      return content;
    }
    return content.substring(0, maxChars) + "\n...(truncated)";
  }
}
