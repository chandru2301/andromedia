package com.andromedia.cli.progress;

import java.io.PrintStream;

public final class TerminalDetector {

  private TerminalDetector() {}

  public static boolean isInteractive(PrintStream stream) {
    if ("true".equalsIgnoreCase(System.getenv("CI"))) {
      return false;
    }
    String term = System.getenv("TERM");
    if (term == null || term.isBlank() || "dumb".equalsIgnoreCase(term)) {
      return false;
    }
    try {
      var method = PrintStream.class.getMethod("isTerminal");
      return (boolean) method.invoke(stream);
    } catch (ReflectiveOperationException ex) {
      return System.console() != null;
    }
  }
}
