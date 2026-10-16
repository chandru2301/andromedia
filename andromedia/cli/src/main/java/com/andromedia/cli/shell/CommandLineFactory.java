package com.andromedia.cli.shell;

import com.andromedia.cli.AndromediaCommand;
import com.andromedia.cli.CliExecutionContext;
import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.IFactory;
import picocli.CommandLine.RunLast;

@Component
public class CommandLineFactory {

  private final AndromediaCommand rootCommand;
  private final CliExecutionContext cliExecutionContext;
  private final IFactory factory;

  public CommandLineFactory(
      AndromediaCommand rootCommand,
      CliExecutionContext cliExecutionContext,
      IFactory factory) {
    this.rootCommand = rootCommand;
    this.cliExecutionContext = cliExecutionContext;
    this.factory = factory;
  }

  public CommandLine create() {
    CommandLine commandLine = new CommandLine(rootCommand, factory);
    commandLine.setExecutionStrategy(
        parseResult -> {
          syncExecutionContextFromParseResult(parseResult);
          return new RunLast().execute(parseResult);
        });
    commandLine.setExecutionExceptionHandler(this::handleExecutionException);
    return commandLine;
  }

  /**
   * Turn command failures into a one-line diagnostic instead of a stack trace.
   *
   * <p>Without a handler, any {@code IllegalArgumentException} — including a
   * plain "you forgot an argument" — printed ~35 frames of Spring and picocli
   * internals into the interactive shell. That looks like a crash and buries the
   * message that actually tells the user what to do. Full traces remain
   * available with {@code --debug}.
   *
   * <p>Usage errors ({@code CommandLine.ParameterException}) are handled by
   * picocli before reaching here and keep their own formatting.
   */
  private int handleExecutionException(
      Exception ex, CommandLine commandLine, CommandLine.ParseResult parseResult) {
    String message = ex.getMessage();
    if (message == null || message.isBlank()) {
      message = ex.getClass().getSimpleName();
    }

    System.err.println("error: " + message);

    if (cliExecutionContext.isDebugEnabled()) {
      ex.printStackTrace(System.err);
    } else {
      // Point at the fix rather than only reporting the symptom.
      System.err.println("Run with --debug for the full stack trace, or 'help' for usage.");
    }
    return CommandLine.ExitCode.SOFTWARE;
  }

  void syncExecutionContextFromParseResult(CommandLine.ParseResult parseResult) {
    syncExecutionContextFromRoot();
  }

  public void syncExecutionContextFromRoot() {
    cliExecutionContext.setDebugEnabled(rootCommand.isDebug());
    cliExecutionContext.setPlainProgress(rootCommand.isPlain());
    cliExecutionContext.setVerbose(rootCommand.isVerbose());
  }
}
