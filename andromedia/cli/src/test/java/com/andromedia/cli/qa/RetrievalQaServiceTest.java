package com.andromedia.cli.qa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
class RetrievalQaServiceTest {

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
  void skipsLlmWhenNoRetrievalHits() {
    when(searchService.search(any()))
        .thenReturn(new SearchResult(List.of(), 0, Duration.ZERO, null));

    List<String> tokens = new ArrayList<>();
    RetrievalQaService.AskResult result =
        retrievalQaService.ask("missing feature", 5, AskOptions.defaults(), tokens::add);

    assertTrue(result.searchResult().hits().isEmpty());
    verify(chatCompletionService, never()).streamAnswer(any(), any(), any(), any(), any());
    assertTrue(tokens.isEmpty());
  }

  @Test
  void runsRetrievalContextPromptThenChat() {
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
    SearchResult searchResult =
        new SearchResult(List.of(hit), 1, Duration.ofMillis(10), null);
    when(searchService.search(any())).thenReturn(searchResult);

    AssembledContext context =
        new AssembledContext(
            List.of(new ContextBlock("chunk-1", "/proj/Foo.java", "Foo.java:bar:1-5", "code")));
    when(contextAssembler.assemble(any())).thenReturn(context);

    when(contextAttachmentService.loadForAsk(eq("question"), eq(false), any(), eq("")))
        .thenReturn(PromptAttachments.empty());

    RagPrompt prompt = new RagPrompt("system", "user");
    when(promptBuilder.build("question", context, PromptAttachments.empty())).thenReturn(prompt);

    List<String> tokens = new ArrayList<>();
    when(chatProperties.model()).thenReturn("test-model");
    doAnswer(
            invocation -> {
              java.util.function.Consumer<String> consumer = invocation.getArgument(1);
              consumer.accept("answer");
              return null;
            })
        .when(chatCompletionService)
        .streamAnswer(any(), any(), any(), any(), any());

    RetrievalQaService.AskResult result =
        retrievalQaService.ask("question", 5, AskOptions.defaults(), tokens::add);

    verify(searchService).search(new SearchRequest("question", 5, SearchMode.HYBRID));
    verify(contextAssembler).assemble(any());
    verify(promptBuilder).build("question", context, PromptAttachments.empty());
    verify(chatCompletionService).streamAnswer(eq(prompt), any(), any(), eq("test-model"), any());
    assertEquals("answer", tokens.getFirst());
    assertFalse(result.dryRun());
  }

  @Test
  void dryRunSkipsChatCall() {
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
    when(searchService.search(any()))
        .thenReturn(new SearchResult(List.of(hit), 1, Duration.ZERO, null));
    AssembledContext context =
        new AssembledContext(
            List.of(new ContextBlock("chunk-1", "/proj/Foo.java", "label", "code")));
    when(contextAssembler.assemble(any())).thenReturn(context);
    when(contextAttachmentService.loadForAsk(eq("question"), eq(false), any(), eq("")))
        .thenReturn(PromptAttachments.empty());
    RagPrompt prompt = new RagPrompt("system", "user with context");
    when(promptBuilder.build("question", context, PromptAttachments.empty())).thenReturn(prompt);

    RetrievalQaService.AskResult result =
        retrievalQaService.ask(
            "question", 5, AskOptions.of(true, true, false, true, false), token -> {});

    assertTrue(result.dryRun());
    assertEquals(prompt, result.prompt());
    verify(chatCompletionService, never()).streamAnswer(any(), any(), any(), any(), any());
  }
}
