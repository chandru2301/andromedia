package com.andromedia.cli;

import com.andromedia.common.AndromediaConstants;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Component
@Command(
    name = AndromediaConstants.APPLICATION_NAME,
    mixinStandardHelpOptions = true,
    version = "0.1.0-SNAPSHOT",
    description = "Andromedia command-line interface",
    subcommands = {
      AndromediaCommand.VersionCommand.class,
      IndexCommand.class,
      SearchCommand.class,
      AskCommand.class,
      UsagesCommand.class,
      ImpactCommand.class,
      PlanCommand.class
    })
public class AndromediaCommand implements Runnable {

  @Option(
      names = {"-v", "--verbose"},
      description = "Verbose output")
  private boolean verbose;

  @Option(
      names = {"--debug"},
      description = "Enable debug diagnostics")
  private boolean debug;

  @Option(
      names = "--plain",
      description = "Plain progress lines without terminal spinner animation")
  private boolean plain;

  public boolean isDebug() {
    return debug;
  }

  public boolean isPlain() {
    return plain;
  }

  public boolean isVerbose() {
    return verbose;
  }

  @Override
  public void run() {
    if (verbose) {
      System.out.println("Andromedia CLI (verbose mode)");
    } else {
      System.out.println("Andromedia CLI — use --help for available commands");
    }
  }

  @Command(name = "version", description = "Print the Andromedia version", mixinStandardHelpOptions = true)
  static class VersionCommand implements Runnable {

    @Override
    public void run() {
      System.out.println("andromedia 0.1.0-SNAPSHOT");
    }
  }
}
