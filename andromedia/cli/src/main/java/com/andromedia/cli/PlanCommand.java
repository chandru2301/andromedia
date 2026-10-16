package com.andromedia.cli;

import com.andromedia.cli.progress.CliProgressReporter;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Component
@Command(
    name = "plan",
    description = "Generate a graph-grounded implementation plan",
    mixinStandardHelpOptions = true)
public class PlanCommand implements Runnable {

  private final PlanCommandRunner planCommandRunner;
  private final CliExecutionContext executionContext;

  @Parameters(index = "0", description = "Plan description or change request")
  private String description;

  @Option(names = {"--symbol"}, description = "Target symbol (Class#method or simple name)")
  private String symbolQuery;

  @Option(names = {"-d", "--depth"}, defaultValue = "3", description = "Impact traversal depth")
  private int depth;

  @Option(
      names = {"--deep"},
      description = "Run indexed explore (search + usages + impact) before planning")
  private boolean deepExplore;

  @Option(
      names = {"--no-ground"},
      description = "Skip repository grounding validation (faster, less trustworthy output)")
  private boolean noGround;

  public PlanCommand(PlanCommandRunner planCommandRunner, CliExecutionContext executionContext) {
    this.planCommandRunner = planCommandRunner;
    this.executionContext = executionContext;
  }

  @Override
  public void run() {
    boolean plain = executionContext.isPlainProgress();
    CliProgressReporter progress = CliProgressReporter.create(plain, true);
    int exitCode =
        planCommandRunner.run(
            description, symbolQuery, depth, deepExplore, !noGround, progress, plain);
    if (exitCode != 0) {
      throw new IllegalStateException("Plan generation failed");
    }
  }
}
