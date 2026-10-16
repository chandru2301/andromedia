package com.andromedia.cli.shell;

import com.andromedia.cli.context.ShellSessionContext;
import com.andromedia.cli.shell.agent.ChainedIntentResolver;
import com.andromedia.cli.shell.agent.CommandDispatcher;
import com.andromedia.cli.shell.agent.ExplicitCommandDetector;
import com.andromedia.cli.shell.agent.ShellIntent;
import com.andromedia.cli.shell.agent.SystemCommandHandler;
import java.io.IOException;
import java.util.Set;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.shell.jline3.PicocliJLineCompleter;

@Component
public class AgentShell {

  private static final String PROMPT = "andro> ";

  private final CommandLineFactory commandLineFactory;
  private final ChainedIntentResolver intentResolver;
  private final CommandDispatcher commandDispatcher;
  private final SystemCommandHandler systemCommandHandler;
  private final ShellSessionContext shellSessionContext;

  public AgentShell(
      CommandLineFactory commandLineFactory,
      ChainedIntentResolver intentResolver,
      CommandDispatcher commandDispatcher,
      SystemCommandHandler systemCommandHandler,
      ShellSessionContext shellSessionContext) {
    this.commandLineFactory = commandLineFactory;
    this.intentResolver = intentResolver;
    this.commandDispatcher = commandDispatcher;
    this.systemCommandHandler = systemCommandHandler;
    this.shellSessionContext = shellSessionContext;
  }

  public int run() {
    CommandLine commandLine = commandLineFactory.create();
    commandLineFactory.syncExecutionContextFromRoot();

    try {
      ShellPaths.ensureHistoryParentExists();
    } catch (IOException ex) {
      System.err.println("Failed to create shell history directory: " + ex.getMessage());
      return 1;
    }

    try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
      LineReader reader =
          LineReaderBuilder.builder()
              .terminal(terminal)
              .completer(new PicocliJLineCompleter(commandLine.getCommandSpec()))
              .variable(LineReader.HISTORY_FILE, ShellPaths.historyFile().toString())
              .variable(LineReader.LIST_MAX, 50)
              .build();
      if (reader.getHistory() instanceof DefaultHistory defaultHistory) {
        defaultHistory.attach(reader);
      }

      while (true) {
        try {
          String line = reader.readLine(PROMPT);
          if (line == null || line.isBlank()) {
            continue;
          }
          if (handleLine(line, commandLine)) {
            return 0;
          }
        } catch (UserInterruptException ex) {
          // Ctrl+C — continue shell
        } catch (EndOfFileException ex) {
          return 0;
        }
      }
    } catch (IOException ex) {
      System.err.println("Failed to start interactive shell: " + ex.getMessage());
      return 1;
    }
  }

  boolean handleLine(String line, CommandLine commandLine) {
    String trimmed = line.trim();
    if (isPicocliSlashCommand(trimmed)) {
      String picocliLine = trimmed.substring(1);
      commandLine.execute(CommandDispatcher.parsePicocliLine(picocliLine));
      return false;
    }
    if (trimmed.startsWith("/") || isBuiltinSystem(trimmed)) {
      return systemCommandHandler.handle(trimmed, commandLine);
    }
    if (ExplicitCommandDetector.isPicocliLine(trimmed)) {
      commandLine.execute(CommandDispatcher.parsePicocliLine(trimmed));
      return false;
    }
    ShellSessionContext.ParsedShellInput parsed = shellSessionContext.parseInput(trimmed);
    ShellIntent shellIntent = intentResolver.resolve(parsed.text());
    if (!parsed.fileAttachments().isEmpty() && shellIntent.intent() == com.andromedia.cli.shell.agent.IntentType.ASK) {
      commandLine.execute(buildAskWithAttachments(parsed));
      return false;
    }
    commandDispatcher.dispatch(shellIntent, parsed.text(), commandLine);
    return false;
  }

  private String[] buildAskWithAttachments(ShellSessionContext.ParsedShellInput parsed) {
    java.util.List<String> argv = new java.util.ArrayList<>();
    argv.add("ask");
    if (Boolean.TRUE.equals(intentResolver.resolve(parsed.text()).graphContext())) {
      argv.add("--graph-context");
    }
    for (String attachment : parsed.fileAttachments()) {
      argv.add("--attach");
      argv.add(attachment);
    }
    argv.add(parsed.text());
    return argv.toArray(String[]::new);
  }

  private static boolean isBuiltinSystem(String trimmed) {
    String lower = trimmed.toLowerCase();
    return lower.equals("exit")
        || lower.equals("quit")
        || lower.equals("help")
        || lower.equals("clear")
        || lower.equals("history")
        || lower.equals("model");
  }

  private static final Set<String> SYSTEM_SLASH_COMMANDS =
      Set.of(
          "help",
          "exit",
          "quit",
          "clear",
          "history",
          "model",
          "debug",
          "plain",
          "verbose",
          "context",
          "rules");

  static boolean isPicocliSlashCommand(String trimmed) {
    if (trimmed == null || !trimmed.startsWith("/")) {
      return false;
    }
    String withoutSlash = trimmed.substring(1).trim();
    if (withoutSlash.isEmpty()) {
      return false;
    }
    String first = ExplicitCommandDetector.firstToken(withoutSlash).toLowerCase();
    if (SYSTEM_SLASH_COMMANDS.contains(first)) {
      return false;
    }
    return ExplicitCommandDetector.isPicocliLine(withoutSlash);
  }
}
