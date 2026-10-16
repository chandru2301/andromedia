package com.andromedia.search;

import com.andromedia.llm.ChatCompletionService;
import com.andromedia.llm.RagPrompt;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ImpactExplanationService {

  private static final String SYSTEM_MESSAGE =
      """
      You summarize deterministic code impact analysis results.
      Answer ONLY from the provided impact results.
      Do not infer additional dependencies, files, or symbols.
      Keep the answer to 2-4 concise sentences.
      """;

  private final ChatCompletionService chatCompletionService;

  @Autowired
  public ImpactExplanationService(ChatCompletionService chatCompletionService) {
    this.chatCompletionService = chatCompletionService;
  }

  public void explain(
      String originalPrompt, ImpactLookupResult result, Consumer<String> onToken) {
    String userMessage =
        """
        User question:
        %s

        Deterministic impact results (do not go beyond this list):
        %s
        """
            .formatted(originalPrompt, formatResults(result));
    chatCompletionService.streamAnswer(new RagPrompt(SYSTEM_MESSAGE, userMessage), onToken);
  }

  static String formatResults(ImpactLookupResult result) {
    StringBuilder builder = new StringBuilder();
    builder.append("Seed symbols: ").append(String.join(", ", result.seedSymbolIds())).append('\n');
    builder.append("Direction: ").append(result.direction()).append('\n');
    if (result.affectedSymbols().isEmpty()) {
      builder.append("No affected symbols found.");
      return builder.toString();
    }
    for (ImpactSite site : result.affectedSymbols()) {
      builder
          .append("- ")
          .append(site.symbolId())
          .append(" hop=")
          .append(site.hop())
          .append(" category=")
          .append(site.category())
          .append(" path=")
          .append(site.path())
          .append(':')
          .append(site.startLine())
          .append('\n');
    }
    return builder.toString();
  }
}
