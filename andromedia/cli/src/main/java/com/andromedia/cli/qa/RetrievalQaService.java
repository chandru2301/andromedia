package com.andromedia.cli.qa;

import com.andromedia.cli.context.ContextAttachmentService;
import com.andromedia.cli.context.ShellSessionContext;
import com.andromedia.cli.output.CliTheme;
import com.andromedia.cli.output.ContextNarrator;
import com.andromedia.cli.progress.CliPhase;
import com.andromedia.cli.progress.PhaseTiming;
import com.andromedia.common.RetrievalReference;
import com.andromedia.llm.AssembledContext;
import com.andromedia.llm.AskProperties;
import com.andromedia.llm.ChatProperties;
import com.andromedia.llm.CodeRagPromptBuilder;
import com.andromedia.llm.ContextAssembler;
import com.andromedia.llm.PromptAttachments;
import com.andromedia.llm.RagPrompt;
import com.andromedia.search.ExploreService;
import com.andromedia.search.SearchDiagnostics;
import com.andromedia.search.SearchHit;
import com.andromedia.search.SearchMode;
import com.andromedia.search.SearchRequest;
import com.andromedia.search.SearchResult;
import com.andromedia.search.SearchService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

@Service
public class RetrievalQaService {

  private final SearchService searchService;
  private final ContextAssembler contextAssembler;
  private final CodeRagPromptBuilder promptBuilder;
  private final ReportingChatCompletionService chatCompletionService;
  private final AskProperties askProperties;
  private final ChatProperties chatProperties;
  private final ContextAttachmentService contextAttachmentService;
  private final ExploreService exploreService;
  private final ShellSessionContext shellSessionContext;

  public RetrievalQaService(
      SearchService searchService,
      ContextAssembler contextAssembler,
      CodeRagPromptBuilder promptBuilder,
      ReportingChatCompletionService chatCompletionService,
      AskProperties askProperties,
      ChatProperties chatProperties,
      ContextAttachmentService contextAttachmentService,
      ExploreService exploreService,
      ShellSessionContext shellSessionContext) {
    this.searchService = searchService;
    this.contextAssembler = contextAssembler;
    this.promptBuilder = promptBuilder;
    this.chatCompletionService = chatCompletionService;
    this.askProperties = askProperties;
    this.chatProperties = chatProperties;
    this.contextAttachmentService = contextAttachmentService;
    this.exploreService = exploreService;
    this.shellSessionContext = shellSessionContext;
  }

  public AskResult ask(String question, int retrievalLimit, AskOptions options, Consumer<String> onToken) {
    var progress = options.progress();
    List<PhaseTiming> timings = new ArrayList<>();
    int limit = retrievalLimit > 0 ? retrievalLimit : askProperties.retrievalLimit();

    String exploreContext = "";
    if (options.deepExplore()) {
      progress.startPhase(CliPhase.HYBRID_RETRIEVAL);
      Instant exploreStart = Instant.now();
      exploreContext = exploreService.explore(question, null).summaryText();
      timings.add(new PhaseTiming(CliPhase.HYBRID_RETRIEVAL, Duration.between(exploreStart, Instant.now())));
      progress.completePhase(CliPhase.HYBRID_RETRIEVAL, timings.getLast().duration());
    }

    progress.startPhase(CliPhase.HYBRID_RETRIEVAL);
    Instant retrievalStart = Instant.now();
    SearchResult searchResult =
        searchService.search(new SearchRequest(question, limit, SearchMode.HYBRID));
    timings.add(new PhaseTiming(CliPhase.HYBRID_RETRIEVAL, Duration.between(retrievalStart, Instant.now())));

    if (searchResult.hits().isEmpty()) {
      progress.completePhase(CliPhase.HYBRID_RETRIEVAL, timings.getLast().duration());
      return new AskResult(searchResult, null, null, false, timings, summaryTimings(timings), null, null);
    }

    String retrievalDetail = searchResult.hits().size() + " chunks";
    SearchDiagnostics diagnostics = searchResult.diagnostics();
    if (diagnostics != null && Boolean.TRUE.equals(diagnostics.semanticSkipped())) {
      retrievalDetail = retrievalDetail + ", semantic unavailable, BM25 only";
    }
    progress.updatePhase(CliPhase.HYBRID_RETRIEVAL, retrievalDetail);
    progress.completePhase(CliPhase.HYBRID_RETRIEVAL, timings.getLast().duration());

    progress.startPhase(CliPhase.EXPANDING_CONTEXT);
    Instant contextStart = Instant.now();
    List<RetrievalReference> references =
        searchResult.hits().stream().map(SearchHit::toRetrievalReference).toList();
    AssembledContext context = contextAssembler.assemble(references);
    Duration contextDuration = Duration.between(contextStart, Instant.now());
    timings.add(new PhaseTiming(CliPhase.EXPANDING_CONTEXT, contextDuration));
    progress.updatePhase(CliPhase.EXPANDING_CONTEXT, context.blocks().size() + " blocks");
    progress.completePhase(CliPhase.EXPANDING_CONTEXT, contextDuration);

    PromptAttachments attachments =
        contextAttachmentService.loadForAsk(
            question, options.graphContext(), options.fileAttachments(), exploreContext);

    Instant promptStart = Instant.now();
    RagPrompt prompt = promptBuilder.build(question, context, attachments);
    Duration promptDuration = Duration.between(promptStart, Instant.now());
    timings.add(new PhaseTiming(CliPhase.PREPARING_PROMPT, promptDuration));

    shellSessionContext.recordAsk(
        searchResult.hits().size(), context.blocks().size(), prompt, question);

    Duration preparationTotal =
        timings.stream()
            .filter(
                t ->
                    t.phase() == CliPhase.HYBRID_RETRIEVAL
                        || t.phase() == CliPhase.EXPANDING_CONTEXT
                        || t.phase() == CliPhase.PREPARING_PROMPT)
            .map(PhaseTiming::duration)
            .reduce(Duration.ZERO, Duration::plus);
    progress.preparationComplete(preparationTotal);

    CliTheme theme = new CliTheme(options.plainProgress());
    ContextNarrator.narrate(System.err, context, options.verbose(), theme);

    if (options.dryRun()) {
      return new AskResult(
          searchResult, context, prompt, true, timings, summaryTimings(timings), null, null);
    }

    AtomicReference<Instant> firstTokenAt = new AtomicReference<>();
    Instant modelStart = Instant.now();
    if (options.stream()) {
      chatCompletionService.streamAnswer(
          prompt, onToken, progress, chatProperties.model(), firstTokenAt);
    } else {
      StringBuilder buffer = new StringBuilder();
      chatCompletionService.streamAnswer(
          prompt, buffer::append, progress, chatProperties.model(), firstTokenAt);
      onToken.accept(buffer.toString());
    }
    Instant modelEnd = Instant.now();
    Duration modelTotal = Duration.between(modelStart, modelEnd);
    timings.add(new PhaseTiming(CliPhase.WAITING_FOR_MODEL, modelTotal));

    Duration timeToFirstToken = null;
    Duration generationDuration = null;
    Instant firstToken = firstTokenAt.get();
    if (firstToken != null) {
      timeToFirstToken = Duration.between(modelStart, firstToken);
      generationDuration = Duration.between(firstToken, modelEnd);
    }

    return new AskResult(
        searchResult,
        context,
        prompt,
        false,
        timings,
        summaryTimings(timings),
        timeToFirstToken,
        generationDuration);
  }

  static List<PhaseTiming> summaryTimings(List<PhaseTiming> timings) {
    Duration retrieval = durationFor(timings, CliPhase.HYBRID_RETRIEVAL);
    Duration context = durationFor(timings, CliPhase.EXPANDING_CONTEXT);
    Duration prompt = durationFor(timings, CliPhase.PREPARING_PROMPT);
    Duration model = durationFor(timings, CliPhase.WAITING_FOR_MODEL);
    return Stream.of(
            new PhaseTiming(CliPhase.HYBRID_RETRIEVAL, retrieval),
            new PhaseTiming(CliPhase.EXPANDING_CONTEXT, context.plus(prompt)),
            new PhaseTiming(CliPhase.WAITING_FOR_MODEL, model))
        .filter(t -> !t.duration().isZero())
        .toList();
  }

  private static Duration durationFor(List<PhaseTiming> timings, CliPhase phase) {
    return timings.stream()
        .filter(t -> t.phase() == phase)
        .map(PhaseTiming::duration)
        .findFirst()
        .orElse(Duration.ZERO);
  }

  public record AskResult(
      SearchResult searchResult,
      AssembledContext context,
      RagPrompt prompt,
      boolean dryRun,
      List<PhaseTiming> phaseTimings,
      List<PhaseTiming> summaryTimings,
      Duration timeToFirstToken,
      Duration generationDuration) {}
}
