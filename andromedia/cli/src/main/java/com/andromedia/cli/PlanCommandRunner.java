package com.andromedia.cli;

import com.andromedia.cli.context.ContextAttachmentService;
import com.andromedia.cli.progress.CliPhase;
import com.andromedia.cli.progress.CliPlanProgressAdapter;
import com.andromedia.cli.progress.CliProgressReporter;
import com.andromedia.cli.progress.PhaseTiming;
import com.andromedia.search.PlanGenerationService;
import com.andromedia.search.grounding.GroundingValidationReport;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class PlanCommandRunner {

  private final PlanGenerationService planGenerationService;
  private final ContextAttachmentService contextAttachmentService;

  public PlanCommandRunner(
      PlanGenerationService planGenerationService,
      ContextAttachmentService contextAttachmentService) {
    this.planGenerationService = planGenerationService;
    this.contextAttachmentService = contextAttachmentService;
  }

  public int run(String description, String symbolQuery, int depth, boolean deepExplore) {
    return run(
        description,
        symbolQuery,
        depth,
        deepExplore,
        true,
        CliProgressReporter.create(false, false),
        false);
  }

  public int run(
      String description,
      String symbolQuery,
      int depth,
      boolean deepExplore,
      CliProgressReporter progress,
      boolean plainProgress) {
    return run(description, symbolQuery, depth, deepExplore, true, progress, plainProgress);
  }

  public int run(
      String description,
      String symbolQuery,
      int depth,
      boolean deepExplore,
      boolean grounded,
      CliProgressReporter progress,
      boolean plainProgress) {
    if (description == null || description.isBlank()) {
      System.err.println("Plan description is required.");
      return 1;
    }
    List<PhaseTiming> timings = new ArrayList<>();
    try {
      Instant loadingStartedAt = Instant.now();
      progress.startPhase(CliPhase.LOADING_CONTEXT);
      var attachments = contextAttachmentService.loadForPlan(description, List.of());
      Duration loadingElapsed = Duration.between(loadingStartedAt, Instant.now());
      progress.completePhase(CliPhase.LOADING_CONTEXT, loadingElapsed);
      timings.add(new PhaseTiming(CliPhase.LOADING_CONTEXT, loadingElapsed));

      CliPlanProgressAdapter planProgress = new CliPlanProgressAdapter(progress);
      PlanGenerationService.PlanResult result =
          planGenerationService.generate(
              description,
              symbolQuery,
              depth,
              deepExplore,
              attachments,
              grounded,
              planProgress);
      timings.addAll(planProgress.timings());

      if (grounded && !result.grounding().unresolvedQueries().isEmpty()) {
        for (String unresolved : result.grounding().unresolvedQueries()) {
          System.err.println("Warning: unresolved symbol — " + unresolved);
        }
      }

      progress.seal();
      System.out.println(result.markdown());
      System.out.println();
      System.out.println("Plan saved to: " + result.savedPath());

      GroundingValidationReport validation = result.validation();
      if (grounded && validation.proposedCount() > 0) {
        System.err.println(
            validation.summaryLine() + " — see Grounding validation section");
      }

      progress.printTimingSummary(timings, plainProgress);
      return 0;
    } catch (RuntimeException ex) {
      String message = ex.getMessage();
      if (message == null || message.isBlank()) {
        message = ex.getClass().getSimpleName();
      }
      progress.fail(message);
      System.err.println(ex);
      return 1;
    }
  }
}
