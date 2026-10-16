package com.andromedia.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.andromedia.cli.context.ContextAttachmentService;
import com.andromedia.cli.progress.CliPhase;
import com.andromedia.cli.progress.CliProgressReporter;
import com.andromedia.cli.progress.PhaseTiming;
import com.andromedia.llm.PromptAttachments;
import com.andromedia.search.PlanGenerationService;
import com.andromedia.search.PlanProgressCallback;
import com.andromedia.search.PlanProgressPhase;
import com.andromedia.search.SearchResult;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PlanCommandRunnerTest {

  @Mock private PlanGenerationService planGenerationService;
  @Mock private ContextAttachmentService contextAttachmentService;

  @Captor private ArgumentCaptor<PlanProgressCallback> progressCaptor;

  @Test
  void runsPhasesInOrderBeforePrintingPlan() {
    when(contextAttachmentService.loadForPlan(eq("add retry"), any()))
        .thenReturn(PromptAttachments.empty());
    when(planGenerationService.generate(
            eq("add retry"), eq("Foo#bar"), eq(3), eq(false), any(), eq(true), any()))
        .thenAnswer(
            invocation -> {
              PlanProgressCallback callback = invocation.getArgument(6);
              callback.onPhase(
                  PlanProgressPhase.EXPLORING_CODEBASE,
                  "8 chunks",
                  PlanProgressCallback.PhaseEvent.START,
                  null);
              callback.onPhase(
                  PlanProgressPhase.EXPLORING_CODEBASE,
                  "8 chunks",
                  PlanProgressCallback.PhaseEvent.COMPLETE,
                  Duration.ofMillis(100));
              callback.onPhase(
                  PlanProgressPhase.GROUNDING_CONTEXT,
                  "5 files, 8 symbols",
                  PlanProgressCallback.PhaseEvent.START,
                  null);
              callback.onPhase(
                  PlanProgressPhase.GROUNDING_CONTEXT,
                  "5 files, 8 symbols",
                  PlanProgressCallback.PhaseEvent.COMPLETE,
                  Duration.ofMillis(20));
              callback.onPhase(
                  PlanProgressPhase.WRITING_PLAN,
                  "test-model",
                  PlanProgressCallback.PhaseEvent.START,
                  null);
              callback.onPhase(
                  PlanProgressPhase.WRITING_PLAN,
                  "test-model",
                  PlanProgressCallback.PhaseEvent.COMPLETE,
                  Duration.ofMillis(2000));
              return new PlanGenerationService.PlanResult(
                  "## Requirements\nretry",
                  Path.of("/tmp/plan.md"),
                  new SearchResult(List.of(), 0, Duration.ZERO),
                  "ember-coral");
            });

    List<CliPhase> started = new ArrayList<>();
    List<CliPhase> completed = new ArrayList<>();
    CliProgressReporter progress =
        new CliProgressReporter() {
          @Override
          public void startPhase(CliPhase phase) {
            started.add(phase);
          }

          @Override
          public void updatePhase(CliPhase phase, String detail) {}

          @Override
          public void completePhase(CliPhase phase, Duration duration) {
            completed.add(phase);
          }

          @Override
          public void seal() {}

          @Override
          public void fail(String message) {}

          @Override
          public void printTimingSummary(List<PhaseTiming> timings, boolean plainSymbols) {}
        };

    PlanCommandRunner runner =
        new PlanCommandRunner(planGenerationService, contextAttachmentService);
    int exitCode = runner.run("add retry", "Foo#bar", 3, false, progress, true);

    assertEquals(0, exitCode);
    assertEquals(
        List.of(
            CliPhase.LOADING_CONTEXT,
            CliPhase.EXPLORING_CODEBASE,
            CliPhase.GROUNDING_CONTEXT,
            CliPhase.WRITING_PLAN),
        started);
    verify(planGenerationService)
        .generate(
            eq("add retry"), eq("Foo#bar"), eq(3), eq(false), any(), eq(true), progressCaptor.capture());
  }

  @Test
  void returnsErrorWhenDescriptionMissing() {
    PlanCommandRunner runner =
        new PlanCommandRunner(planGenerationService, contextAttachmentService);
    int exitCode = runner.run("  ", null, 3, false, CliProgressReporter.create(false, true), true);
    assertEquals(1, exitCode);
  }
}
