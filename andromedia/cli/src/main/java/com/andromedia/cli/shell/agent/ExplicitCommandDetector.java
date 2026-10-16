package com.andromedia.cli.shell.agent;

import java.util.Set;

public final class ExplicitCommandDetector {

  private static final Set<String> SUBCOMMANDS =
      Set.of("ask", "search", "index", "usages", "impact", "version", "help");

  private ExplicitCommandDetector() {}

  public static boolean isPicocliLine(String line) {
    if (line == null || line.isBlank()) {
      return false;
    }
    String first = firstToken(line);
    if (first.isEmpty()) {
      return false;
    }
    if (SUBCOMMANDS.contains(first.toLowerCase())) {
      return true;
    }
    return first.startsWith("-");
  }

  public static String firstToken(String line) {
    String trimmed = line.trim();
    if (trimmed.startsWith("\"")) {
      return trimmed.length() > 1 && trimmed.charAt(1) != '"'
          ? trimmed.substring(1, trimmed.indexOf('"', 1))
          : "";
    }
    int space = trimmed.indexOf(' ');
    return space < 0 ? trimmed : trimmed.substring(0, space);
  }
}
