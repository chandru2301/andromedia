package com.andromedia.cli.shell.agent;

import com.andromedia.cli.CliExecutionContext;
import com.andromedia.cli.shell.ShellPaths;
import com.andromedia.cli.context.ProjectRulesLoader;
import com.andromedia.cli.context.ShellSessionContext;
import com.andromedia.common.IndexPathResolver;
import com.andromedia.llm.ChatProperties;
import java.io.PrintStream;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine;

@Component
public class SystemCommandHandler {

  private final CliExecutionContext executionContext;
  private final ChatProperties chatProperties;
  private final ShellSessionContext shellSessionContext;
  private final ProjectRulesLoader projectRulesLoader;
  private final PrintStream out;
  private final PrintStream err;

  @Autowired
  public SystemCommandHandler(
      CliExecutionContext executionContext,
      ChatProperties chatProperties,
      ShellSessionContext shellSessionContext,
      ProjectRulesLoader projectRulesLoader) {
    this(executionContext, chatProperties, shellSessionContext, projectRulesLoader, System.out, System.err);
  }

  private SystemCommandHandler(
      CliExecutionContext executionContext,
      ChatProperties chatProperties,
      ShellSessionContext shellSessionContext,
      ProjectRulesLoader projectRulesLoader,
      PrintStream out,
      PrintStream err) {
    this.executionContext = executionContext;
    this.chatProperties = chatProperties;
    this.shellSessionContext = shellSessionContext;
    this.projectRulesLoader = projectRulesLoader;
    this.out = out;
    this.err = err;
  }

  static SystemCommandHandler forTest(
      CliExecutionContext executionContext,
      ChatProperties chatProperties,
      ShellSessionContext shellSessionContext,
      ProjectRulesLoader projectRulesLoader,
      PrintStream out,
      PrintStream err) {
    return new SystemCommandHandler(
        executionContext, chatProperties, shellSessionContext, projectRulesLoader, out, err);
  }

  /** @return true if the shell should exit */
  public boolean handle(String line, CommandLine commandLine) {
    String trimmed = line == null ? "" : line.trim();
    if (trimmed.isEmpty()) {
      return false;
    }

  String lower = trimmed.toLowerCase();
    if (isExit(lower)) {
      return true;
    }
    if (isHelp(lower)) {
      printHelp();
      return false;
    }
    if (lower.equals("/clear") || lower.equals("clear")) {
      out.print("\u001B[2J\u001B[H");
      out.flush();
      return false;
    }
    if (lower.equals("/history") || lower.equals("history")) {
      out.println(ShellPaths.historyFile());
      return false;
    }
    if (lower.equals("/model") || lower.equals("model")) {
      out.printf("chat model: %s (%s)%n", chatProperties.model(), chatProperties.provider());
      return false;
    }
    if (lower.equals("/context") || lower.equals("context")) {
      out.println(shellSessionContext.formatStatus());
      return false;
    }
    if (lower.equals("/rules") || lower.startsWith("/rules ")) {
      printRules();
      return false;
    }
    if (lower.startsWith("/debug")) {
      applyToggle(trimmed, Toggle.DEBUG);
      return false;
    }
    if (lower.startsWith("/plain")) {
      applyToggle(trimmed, Toggle.PLAIN);
      return false;
    }
    if (lower.startsWith("/verbose")) {
      applyToggle(trimmed, Toggle.VERBOSE);
      return false;
    }

    err.println("Unknown command. Type /help for available commands.");
    return false;
  }

  private void printRules() {
    Path workspace =
        IndexPathResolver.resolveWorkspaceRoot(Path.of(System.getProperty("user.dir", ".")));
    var paths = projectRulesLoader.listRulePaths(workspace);
    if (paths.isEmpty()) {
      out.println("No project rules found (ANDROMEDIA.md or .andromedia/rules/*.md)");
      return;
    }
    out.println("Project rules:");
    paths.forEach(path -> out.println("  " + path));
  }

  private void printHelp() {
    out.println(
        """
        Andromedia agent shell
          Natural language  → routed to ask / search / index / impact / plan
          ask|search|index  → explicit Picocli commands
          @path             → attach file excerpt to ask
          /help /exit /clear /debug on|off /plain on|off /verbose on|off
          /context /rules   /index <path> /model /history
        """);
  }

  private static boolean isExit(String lower) {
    return lower.equals("/exit")
        || lower.equals("/quit")
        || lower.equals("exit")
        || lower.equals("quit");
  }

  private static boolean isHelp(String lower) {
    return lower.equals("/help") || lower.equals("help");
  }

  private enum Toggle {
    DEBUG,
    PLAIN,
    VERBOSE
  }

  private void applyToggle(String line, Toggle toggle) {
    Boolean value = parseOnOff(line);
    if (value == null) {
      err.println("Usage: /" + toggle.name().toLowerCase() + " on|off");
      return;
    }
    switch (toggle) {
      case DEBUG -> executionContext.setDebugEnabled(value);
      case PLAIN -> executionContext.setPlainProgress(value);
      case VERBOSE -> executionContext.setVerbose(value);
    }
    out.printf("%s %s%n", toggle.name().toLowerCase(), value ? "on" : "off");
  }

  private static Boolean parseOnOff(String line) {
    String[] parts = line.trim().split("\\s+");
    if (parts.length < 2) {
      return null;
    }
    return switch (parts[parts.length - 1].toLowerCase()) {
      case "on", "true", "1" -> true;
      case "off", "false", "0" -> false;
      default -> null;
    };
  }

}
