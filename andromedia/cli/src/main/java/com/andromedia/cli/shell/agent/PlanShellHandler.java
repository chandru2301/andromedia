package com.andromedia.cli.shell.agent;

import com.andromedia.cli.CliExecutionContext;
import com.andromedia.cli.PlanCommandRunner;
import com.andromedia.cli.progress.CliProgressReporter;
import java.io.PrintStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class PlanShellHandler {

  private final PlanCommandRunner planCommandRunner;
  private final CliExecutionContext executionContext;
  private final PrintStream err;

  @Autowired
  public PlanShellHandler(PlanCommandRunner planCommandRunner, CliExecutionContext executionContext) {
    this(planCommandRunner, executionContext, System.err);
  }

  PlanShellHandler(
      PlanCommandRunner planCommandRunner, CliExecutionContext executionContext, PrintStream err) {
    this.planCommandRunner = planCommandRunner;
    this.executionContext = executionContext;
    this.err = err;
  }

  public int handleNaturalLanguage(String line) {
    if (line == null || line.isBlank()) {
      err.println("Plan description is required, e.g.: plan how to add retry to consumeAnomaly");
      return 1;
    }
    boolean plain = executionContext.isPlainProgress();
    CliProgressReporter progress = CliProgressReporter.create(plain, true);
    return planCommandRunner.run(line.trim(), null, 3, false, progress, plain);
  }
}
