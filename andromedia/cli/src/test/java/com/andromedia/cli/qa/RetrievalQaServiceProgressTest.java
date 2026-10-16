package com.andromedia.cli.qa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.andromedia.cli.progress.CliPhase;
import com.andromedia.cli.progress.CliProgressReporter;
import com.andromedia.cli.progress.PhaseTiming;
import com.andromedia.common.ChunkUnitType;
import com.andromedia.llm.AskProperties;
import com.andromedia.llm.AssembledContext;
import com.andromedia.llm.ChatProperties;
import com.andromedia.llm.CodeRagPromptBuilder;
import com.andromedia.llm.ContextAssembler;
import com.andromedia.llm.ContextBlock;
import com.andromedia.cli.context.ContextAttachmentService;
import com.andromedia.cli.context.ShellSessionContext;
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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RetrievalQaServiceProgressTest {

  @Mock private SearchService searchService;
  @Mock private ContextAssembler contextAssembler;
  @Mock private CodeRagPromptBuilder promptBuilder;
  @Mock private ReportingChatCompletionService chatCompletionService;
  @Mock private AskProperties askProperties;
  @Mock private ChatProperties chatProperties;
  @Mock private ContextAttachmentService contextAttachmentService;
  @Mock private ExploreService exploreService;
  @Mock private ShellSessionContext shellSessionContext;

  @InjectMocks private RetrievalQaService retrievalQaService;

  @Test
  void reportsPhasesInPipelineOrder() {
    SearchHit hit =
        new SearchHit(
            "/proj/Foo.java",
            "Foo.java",
            1.0f,
            "chunk-1",
            "Foo",
            "bar",
            ChunkUnitType.METHOD,
            1,
            5);
    SearchDiagnostics diagnostics =
        new SearchDiagnostics(null, null, null, 3, 0, true, null, null, null, null, "embedding failed");
    SearchResult searchResult =
        new SearchResult(List.of(hit), 1, Duration.ofMillis(5), diagnostics);
    when(searchService.search(any())).thenReturn(searchResult);

    AssembledContext context =
        new AssembledContext(
            List.of(new ContextBlock("chunk-1", "/proj/Foo.java", "label", "code")));
    when(contextAssembler.assemble(any())).thenReturn(context);
    when(contextAttachmentService.loadForAsk(any(), any(Boolean.class), any(), any()))
        .thenReturn(PromptAttachments.empty());
    when(promptBuilder.build("q", context, PromptAttachments.empty()))
        .thenReturn(new RagPrompt("s", "u"));
    when(chatProperties.model()).thenReturn("test-model");

    List<CliPhase> started = new ArrayList<>();
    List<String> retrievalDetails = new ArrayList<>();
    CliProgressReporter reporter =
        new CliProgressReporter() {
          @Override
          public void startPhase(CliPhase phase) {
            started.add(phase);
          }

          @Override
          public void updatePhase(CliPhase phase, String detail) {
            if (phase == CliPhase.HYBRID_RETRIEVAL) {
              retrievalDetails.add(detail);
            }
          }

          @Override
          public void completePhase(CliPhase phase, Duration duration) {}

          @Override
          public void seal() {}

          @Override
          public void fail(String message) {}

          @Override
          public void printTimingSummary(List<PhaseTiming> timings, boolean plainSymbols) {}
        };

    doAnswer(
            invocation -> {
              invocation.<java.util.function.Consumer<String>>getArgument(1).accept("ok");
              return null;
            })
        .when(chatCompletionService)
        .streamAnswer(any(), any(), any(), any(), any());

    AskOptions options =
        new AskOptions(
            false, true, false, true, false, false, false, List.of(), reporter);
    retrievalQaService.ask("q", 5, options, t -> {});

    assertEquals(List.of(CliPhase.HYBRID_RETRIEVAL, CliPhase.EXPANDING_CONTEXT), started);
    assertEquals("1 chunks, semantic unavailable, BM25 only", retrievalDetails.getFirst());
  }

  @Test
  void summaryTimingsCombinePromptAndModel() {
    List<PhaseTiming> timings =
        List.of(
            new PhaseTiming(CliPhase.HYBRID_RETRIEVAL, Duration.ofMillis(100)),
            new PhaseTiming(CliPhase.EXPANDING_CONTEXT, Duration.ofMillis(50)),
            new PhaseTiming(CliPhase.PREPARING_PROMPT, Duration.ofMillis(10)),
            new PhaseTiming(CliPhase.WAITING_FOR_MODEL, Duration.ofMillis(2000)));

    List<PhaseTiming> summary = RetrievalQaService.summaryTimings(timings);

    assertEquals(3, summary.size());
    assertEquals(Duration.ofMillis(60), summary.get(1).duration());
    assertEquals(Duration.ofMillis(2000), summary.get(2).duration());
    assertEquals("Generation", summary.get(2).phase().footerMetricLabel());
  }
}
