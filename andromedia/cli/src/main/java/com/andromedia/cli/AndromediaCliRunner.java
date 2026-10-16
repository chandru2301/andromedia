package com.andromedia.cli;

import com.andromedia.cli.progress.TerminalDetector;
import com.andromedia.cli.shell.AgentShell;
import com.andromedia.cli.shell.CommandLineFactory;
import org.fusesource.jansi.AnsiConsole;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import picocli.CommandLine;

@Component
public class AndromediaCliRunner implements CommandLineRunner {

  private final CommandLineFactory commandLineFactory;
  private final AgentShell agentShell;
  private final ApplicationContext applicationContext;

  public AndromediaCliRunner(
      CommandLineFactory commandLineFactory,
      AgentShell agentShell,
      ApplicationContext applicationContext) {
    this.commandLineFactory = commandLineFactory;
    this.agentShell = agentShell;
    this.applicationContext = applicationContext;
  }

  @Override
  public void run(String... args) {
    if (TerminalDetector.isInteractive(System.err)) {
      AnsiConsole.systemInstall();
    }

    boolean noArgs = args == null || args.length == 0;
    if (noArgs && TerminalDetector.isInteractive(System.err)) {
      if (shouldPrintBanner(args)) {
        AsciiBannerPrinter.print();
      }
      int exitCode = agentShell.run();
      SpringApplication.exit(applicationContext, () -> exitCode);
      return;
    }

    if (shouldPrintBanner(args)) {
      AsciiBannerPrinter.print();
    }

    if (noArgs) {
      System.out.println("Andromedia CLI — use `andro --help` or run in a terminal for the agent shell.");
      SpringApplication.exit(applicationContext, () -> 0);
      return;
    }

    CommandLine commandLine = commandLineFactory.create();
    int exitCode = commandLine.execute(args);
    SpringApplication.exit(applicationContext, () -> exitCode);
  }

  static boolean shouldPrintBanner(String[] args) {
    if (args == null || args.length == 0) {
      return true;
    }
    if (args.length > 2) {
      return false;
    }

    for (String arg : args) {
      if (!isHelpOption(arg)) {
        return false;
      }
    }
    return true;
  }

  private static boolean isHelpOption(String arg) {
    return "-h".equals(arg) || "--help".equals(arg);
  }
}
