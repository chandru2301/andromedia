package com.andromedia.cli.qa;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.andromedia.cli.progress.CliPhase;
import com.andromedia.cli.progress.CliProgressReporter;
import com.andromedia.llm.ChatCompletionService;
import com.andromedia.llm.ChatProgressListener;
import com.andromedia.llm.RagPrompt;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReportingChatCompletionServiceTest {

  @Mock private ChatCompletionService delegate;
  @Mock private CliProgressReporter progress;

  @Test
  void mapsRetryCallbackToProgressReporter() {
    ArgumentCaptor<ChatProgressListener> listenerCaptor =
        ArgumentCaptor.forClass(ChatProgressListener.class);
    doAnswer(
            invocation -> {
              ChatProgressListener listener = invocation.getArgument(2);
              listener.onRequestStarted("model");
              listener.onRetry(2, 6, 5000, 429);
              listener.onFirstToken();
              java.util.function.Consumer<String> onToken = invocation.getArgument(1);
              onToken.accept("x");
              return null;
            })
        .when(delegate)
        .streamAnswer(any(), any(), listenerCaptor.capture());

    ReportingChatCompletionService service = new ReportingChatCompletionService(delegate);
    RagPrompt prompt = new RagPrompt("system", "user");
    service.streamAnswer(
        prompt, token -> {}, progress, "moonshotai/kimi-k2.6:free", new AtomicReference<>());

    verify(progress, times(2)).startPhase(CliPhase.WAITING_FOR_MODEL);
    verify(progress).startPhase(CliPhase.RETRYING_RATE_LIMIT);
    verify(progress).completePhase(eq(CliPhase.WAITING_FOR_MODEL), any());
    verify(progress).seal();
    verify(delegate).streamAnswer(eq(prompt), any(), any());
  }

  @Test
  void recordsPhaseOrderDuringAskPipeline() {
    List<CliPhase> phases = new ArrayList<>();
    CliProgressReporter tracking =
        new CliProgressReporter() {
          @Override
          public void startPhase(CliPhase phase) {
            phases.add(phase);
          }

          @Override
          public void updatePhase(CliPhase phase, String detail) {}

          @Override
          public void completePhase(CliPhase phase, java.time.Duration duration) {}

          @Override
          public void seal() {}

          @Override
          public void fail(String message) {}

          @Override
          public void printTimingSummary(
              java.util.List<com.andromedia.cli.progress.PhaseTiming> timings,
              boolean plainSymbols) {}
        };

    doAnswer(
            invocation -> {
              ChatProgressListener listener = invocation.getArgument(2);
              listener.onRequestStarted("model");
              listener.onFirstToken();
              return null;
            })
        .when(delegate)
        .streamAnswer(any(), any(), any());

    new ReportingChatCompletionService(delegate)
        .streamAnswer(new RagPrompt("s", "u"), t -> {}, tracking, "model", new AtomicReference<>());

    org.junit.jupiter.api.Assertions.assertEquals(List.of(CliPhase.WAITING_FOR_MODEL), phases);
  }
}
