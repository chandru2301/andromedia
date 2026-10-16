package com.andromedia.cli.output;

import com.andromedia.cli.progress.TerminalDetector;
import org.fusesource.jansi.Ansi;
import org.fusesource.jansi.Ansi.Color;

/** ANSI styling when stderr/stdout is an interactive TTY; plain text prefixes otherwise. */
public final class CliTheme {

  private final boolean plain;
  private final boolean colorEnabled;

  public CliTheme(boolean plain) {
    this.plain = plain;
    this.colorEnabled = !plain && TerminalDetector.isInteractive(System.err);
  }

  public boolean plain() {
    return plain;
  }

  public boolean colorEnabled() {
    return colorEnabled;
  }

  public String system(String text) {
    if (plain) {
      return "[system] " + text;
    }
    if (!colorEnabled) {
      return text;
    }
    return Ansi.ansi().fgBright(Color.BLACK).a(text).reset().toString();
  }

  public String systemSuccess(String message, String durationSuffix) {
    String body = durationSuffix == null || durationSuffix.isBlank()
        ? message
        : message + " (" + durationSuffix + ")";
    if (plain) {
      return "[system] ✓ " + body;
    }
    if (!colorEnabled) {
      return "✓ " + body;
    }
    return Ansi.ansi()
        .fg(Color.GREEN)
        .a("✓ ")
        .fgBright(Color.BLACK)
        .a(body)
        .reset()
        .toString();
  }

  public String heading(String text) {
    if (plain) {
      return text;
    }
    if (!colorEnabled) {
      return text;
    }
    return Ansi.ansi().fg(Color.CYAN).bold().a(text).reset().toString();
  }

  public String assistantPrefix() {
    if (plain) {
      return "[assistant]";
    }
    return heading("Answer");
  }

  public String dim(String text) {
    if (plain || !colorEnabled) {
      return text;
    }
    return Ansi.ansi().fgBright(Color.BLACK).a(text).reset().toString();
  }

  public static String stripAnsi(String text) {
    if (text == null) {
      return "";
    }
    return text.replaceAll("\u001B\\[[0-9;]*[a-zA-Z]", "");
  }
}
