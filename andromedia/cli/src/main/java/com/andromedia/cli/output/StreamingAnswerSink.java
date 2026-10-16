package com.andromedia.cli.output;

import java.io.PrintStream;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Line-buffered answer formatter; reformats complete lines on newline. */
public final class StreamingAnswerSink implements Consumer<String> {

  private static final Pattern MARKDOWN_HEADING = Pattern.compile("^#{1,3}\\s+(.+)$");
  private static final Pattern DASH_BULLET = Pattern.compile("^-\\s+(.+)$");

  private final PrintStream out;
  private final CliTheme theme;
  private final StringBuilder lineBuffer = new StringBuilder();
  private boolean assistantStarted;

  public StreamingAnswerSink(PrintStream out, CliTheme theme) {
    this.out = out;
    this.theme = theme;
  }

  public void beginAssistantBlock() {
    if (assistantStarted) {
      return;
    }
    assistantStarted = true;
    out.println(theme.assistantPrefix());
    out.flush();
  }

  @Override
  public void accept(String token) {
    if (token == null || token.isEmpty()) {
      return;
    }
    for (int i = 0; i < token.length(); i++) {
      char ch = token.charAt(i);
      if (ch == '\n') {
        emitCompletedLine();
        out.println();
      } else {
        lineBuffer.append(ch);
        out.print(ch);
      }
    }
  }

  public void finish() {
    if (!lineBuffer.isEmpty()) {
      rewriteCurrentLine();
      lineBuffer.setLength(0);
    }
    out.flush();
  }

  /** Formats a full line (for tests and buffered answers). */
  static String formatLine(String line, CliTheme theme) {
    if (line == null) {
      return "";
    }
    String trimmed = line.trim();
    var heading = MARKDOWN_HEADING.matcher(trimmed);
    if (heading.matches()) {
      return theme.heading(heading.group(1));
    }
    var bullet = DASH_BULLET.matcher(trimmed);
    if (bullet.matches()) {
      return "  • " + bullet.group(1).trim();
    }
    return line;
  }

  public static String formatBufferedAnswer(String text, CliTheme theme) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    String[] lines = text.split("\n", -1);
    StringBuilder builder = new StringBuilder();
    for (int i = 0; i < lines.length; i++) {
      if (i > 0) {
        builder.append('\n');
      }
      builder.append(formatLine(lines[i], theme));
    }
    return builder.toString();
  }

  private void emitCompletedLine() {
    if (lineBuffer.isEmpty()) {
      return;
    }
    rewriteCurrentLine();
    lineBuffer.setLength(0);
  }

  private void rewriteCurrentLine() {
    String raw = lineBuffer.toString();
    String formatted = formatLine(raw, theme);
    if (!formatted.equals(raw)) {
      out.print('\r');
      out.print("\u001B[2K");
      out.print(formatted);
    }
  }
}
