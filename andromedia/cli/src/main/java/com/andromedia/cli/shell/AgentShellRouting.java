package com.andromedia.cli.shell;

/** Classifies REPL input for tests without a JLine terminal. */
public enum AgentShellRouting {
  SYSTEM,
  EXPLICIT_PICOCLI,
  NATURAL_LANGUAGE;

  public static AgentShellRouting classify(String line) {
    if (line == null || line.isBlank()) {
      return NATURAL_LANGUAGE;
    }
    String trimmed = line.trim();
    if (AgentShell.isPicocliSlashCommand(trimmed)) {
      return EXPLICIT_PICOCLI;
    }
    if (trimmed.startsWith("/")) {
      return SYSTEM;
    }
    String lower = trimmed.toLowerCase();
    if (lower.equals("exit")
        || lower.equals("quit")
        || lower.equals("help")
        || lower.equals("clear")
        || lower.equals("history")
        || lower.equals("model")) {
      return SYSTEM;
    }
    if (AgentShell.isPicocliSlashCommand(trimmed)) {
      return EXPLICIT_PICOCLI;
    }
    if (com.andromedia.cli.shell.agent.ExplicitCommandDetector.isPicocliLine(trimmed)) {
      return EXPLICIT_PICOCLI;
    }
    return NATURAL_LANGUAGE;
  }
}
