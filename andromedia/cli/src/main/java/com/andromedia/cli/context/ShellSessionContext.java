package com.andromedia.cli.context;

import com.andromedia.common.IndexPathResolver;
import com.andromedia.llm.AskProperties;
import com.andromedia.llm.CodeRagPromptBuilder;
import com.andromedia.llm.RagPrompt;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class ShellSessionContext {

  private static final Pattern FILE_ATTACHMENT = Pattern.compile("@(\\S+)");

  private final AskProperties askProperties;
  private final ProjectRulesLoader projectRulesLoader;
  private final ProjectMemoryLoader projectMemoryLoader;

  private volatile int lastRetrievalHits;
  private volatile int lastAssembledBlocks;
  private volatile int lastPromptChars;
  private volatile String lastQuestion;

  public ShellSessionContext(
      AskProperties askProperties,
      ProjectRulesLoader projectRulesLoader,
      ProjectMemoryLoader projectMemoryLoader) {
    this.askProperties = askProperties;
    this.projectRulesLoader = projectRulesLoader;
    this.projectMemoryLoader = projectMemoryLoader;
  }

  public void recordAsk(int retrievalHits, int assembledBlocks, RagPrompt prompt, String question) {
    this.lastRetrievalHits = retrievalHits;
    this.lastAssembledBlocks = assembledBlocks;
    this.lastPromptChars = prompt == null ? 0 : CodeRagPromptBuilder.estimatedRequestChars(prompt);
    this.lastQuestion = question;
  }

  public String formatStatus() {
    Path workspace =
        IndexPathResolver.resolveWorkspaceRoot(Path.of(System.getProperty("user.dir", ".")));
    List<String> rules = projectRulesLoader.listRulePaths(workspace);
    List<String> memory = projectMemoryLoader.listMemoryFiles(workspace);
    return """
        Andromedia context
          workspace: %s
          last question: %s
          last retrieval hits: %d
          last assembled blocks: %d
          last prompt chars: %d / %d
          project rules: %d file(s)
          project memory: %d file(s)
        """
        .formatted(
            workspace,
            lastQuestion == null ? "(none)" : lastQuestion,
            lastRetrievalHits,
            lastAssembledBlocks,
            lastPromptChars,
            askProperties.maxPromptChars(),
            rules.size(),
            memory.size());
  }

  public ParsedShellInput parseInput(String line) {
    if (line == null || line.isBlank()) {
      return new ParsedShellInput("", List.of());
    }
    List<String> attachments = new ArrayList<>();
    Matcher matcher = FILE_ATTACHMENT.matcher(line);
    StringBuilder cleaned = new StringBuilder();
    int lastEnd = 0;
    while (matcher.find()) {
      cleaned.append(line, lastEnd, matcher.start());
      attachments.add(matcher.group(1));
      lastEnd = matcher.end();
    }
    cleaned.append(line.substring(lastEnd));
    return new ParsedShellInput(cleaned.toString().trim(), List.copyOf(attachments));
  }

  public record ParsedShellInput(String text, List<String> fileAttachments) {}
}
