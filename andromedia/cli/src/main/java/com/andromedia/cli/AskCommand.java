package com.andromedia.cli;

import com.andromedia.cli.output.CliTheme;
import com.andromedia.cli.output.StreamingAnswerSink;
import com.andromedia.cli.progress.AskMetricsFooter;
import com.andromedia.cli.qa.AskOptions;
import com.andromedia.cli.qa.RetrievalQaService;
import com.andromedia.llm.AskProperties;
import com.andromedia.llm.ChatProperties;
import com.andromedia.llm.CodeRagPromptBuilder;
import com.andromedia.search.SearchMode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Component
@Command(
    name = "ask",
    description = "Retrieval-augmented question answering over the indexed codebase",
    mixinStandardHelpOptions = true)
public class AskCommand implements Runnable {

  private final RetrievalQaService retrievalQaService;
  private final AskProperties askProperties;
  private final ChatProperties chatProperties;
  private final CliExecutionContext executionContext;

  @Parameters(index = "0", description = "Question about the codebase")
  private String question;

  @Option(names = {"-n", "--limit"}, description = "Maximum retrieval chunks to include in context")
  private Integer retrievalLimit;

  @Option(
      names = "--dry-run",
      description = "Print assembled prompt to stderr without calling the LLM")
  private boolean dryRun;

  @Option(
      names = "--no-stream",
      description = "Buffer the full LLM response before printing")
  private boolean noStream;

  @Option(
      names = "--graph-context",
      description = "Include deterministic symbol graph context in the prompt")
  private boolean graphContext;

  @Option(
      names = "--deep",
      description = "Run indexed explore (search + usages + impact) before answering")
  private boolean deepExplore;

  @Option(
      names = "--attach",
      description = "Attach file excerpt to prompt (repeatable)")
  private List<String> attachments = new ArrayList<>();

  public AskCommand(
      RetrievalQaService retrievalQaService,
      AskProperties askProperties,
      ChatProperties chatProperties,
      CliExecutionContext executionContext) {
    this.retrievalQaService = retrievalQaService;
    this.askProperties = askProperties;
    this.chatProperties = chatProperties;
    this.executionContext = executionContext;
  }

  @Override
  public void run() {
    String validatedQuestion = validateQuestion(question);
    int limit = retrievalLimit != null ? retrievalLimit : askProperties.retrievalLimit();
    boolean debug = executionContext.isDebugEnabled();
    boolean plain = executionContext.isPlainProgress();
    boolean verbose = executionContext.isVerbose();
    CliTheme theme = new CliTheme(plain);
    AskOptions options =
        AskOptions.of(
            dryRun,
            !noStream,
            debug,
            plain,
            verbose,
            graphContext,
            deepExplore,
            attachments);

    if (debug) {
      printConfigDebug();
    }

    StreamingAnswerSink answerSink = new StreamingAnswerSink(System.out, theme);
    AtomicBoolean answerStarted = new AtomicBoolean(false);

    RetrievalQaService.AskResult result;
    try {
      result =
          retrievalQaService.ask(
              validatedQuestion,
              limit,
              options,
              token -> {
                if (answerStarted.compareAndSet(false, true)) {
                  answerSink.beginAssistantBlock();
                }
                answerSink.accept(token);
              });
    } catch (RuntimeException ex) {
      options.progress().fail(ex.getMessage());
      throw ex;
    }

    if (result.searchResult().hits().isEmpty()) {
      System.err.println("No indexed chunks matched the question. Try `andro index --embed <path>` first.");
      return;
    }

    if (dryRun && result.prompt() != null) {
      System.err.println("[dry-run] assembled prompt:");
      System.err.println("--- system ---");
      System.err.println(result.prompt().systemMessage());
      System.err.println("--- user ---");
      System.err.println(result.prompt().userMessage());
      return;
    }

    if (!dryRun) {
      if (noStream && answerStarted.get()) {
        answerSink.finish();
      } else if (answerStarted.get()) {
        answerSink.finish();
        System.out.println();
      }
      AskMetricsFooter.print(System.err, result.summaryTimings(), verbose);
    }

    if (debug && result.searchResult().diagnostics() != null) {
      printRetrievalDiagnostics(result);
    }
  }

  private void printConfigDebug() {
    String apiKey = chatProperties.apiKey();
    String masked = maskApiKey(apiKey);
    System.err.printf("[debug] chatProvider=%s%n", chatProperties.provider());
    System.err.printf("[debug] chatModel=%s%n", chatProperties.model());
    System.err.printf("[debug] chatApiKey=%s (%s)%n", masked, chatProperties.apiKeyEnvVar());
    System.err.printf(
        "[debug] chatRetries=%d (embeddings still use OPENROUTER_API_KEY)%n",
        chatProperties.maxRetries() + 1);
  }

  private static String maskApiKey(String apiKey) {
    if (apiKey == null || apiKey.isBlank()) {
      return "(not set)";
    }
    if (apiKey.length() <= 8) {
      return "***";
    }
    return "..." + apiKey.substring(apiKey.length() - 6);
  }

  private void printRetrievalDiagnostics(RetrievalQaService.AskResult result) {
    var diagnostics = result.searchResult().diagnostics();
    System.err.printf("[debug] mode=%s%n", SearchMode.HYBRID.name());
    System.err.printf("[debug] retrievalHits=%d%n", result.searchResult().hits().size());
    if (diagnostics.queryType() != null) {
      System.err.printf("[debug] queryType=%s%n", diagnostics.queryType());
    }
    System.err.printf(
        "[debug] bm25Candidates=%s semanticCandidates=%s semanticSkipped=%s%n",
        diagnostics.bm25Candidates(),
        diagnostics.semanticCandidates(),
        diagnostics.semanticSkipped());
    if (diagnostics.semanticSkipReason() != null) {
      System.err.printf("[debug] semanticSkipReason=%s%n", diagnostics.semanticSkipReason());
    }
    if (result.context() != null) {
      System.err.printf("[debug] assembledBlocks=%d%n", result.context().blocks().size());
    }
    if (result.prompt() != null) {
      System.err.printf(
          "[debug] estimatedRequestChars=%d excerptChars=%d (max-prompt-chars=%d)%n",
          CodeRagPromptBuilder.estimatedRequestChars(result.prompt()),
          CodeRagPromptBuilder.excerptChars(
              result.context() == null ? List.of() : result.context().blocks()),
          askProperties.maxPromptChars());
    }
    if (result.timeToFirstToken() != null) {
      System.err.printf("[debug] timeToFirstTokenMs=%d%n", result.timeToFirstToken().toMillis());
    }
    if (result.generationDuration() != null) {
      System.err.printf("[debug] generationMs=%d%n", result.generationDuration().toMillis());
    }
    System.err.printf("[debug] retrievalMs=%d%n", result.searchResult().elapsed().toMillis());
  }

  private static String validateQuestion(String questionText) {
    if (questionText == null || questionText.isBlank()) {
      throw new IllegalArgumentException("Question is required");
    }
    return questionText.trim();
  }
}
