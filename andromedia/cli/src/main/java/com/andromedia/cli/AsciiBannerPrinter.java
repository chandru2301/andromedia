package com.andromedia.cli;

import org.fusesource.jansi.Ansi;
import org.fusesource.jansi.AnsiConsole;

/**
 * Startup banner.
 *
 * <p>Renders a block-letter "ANDRO" wordmark with a per-row colour gradient, in
 * the spirit of modern agent CLIs, plus a short subtitle and hint line. Colour is
 * applied only when the terminal actually supports it, so piping output to a file
 * or running in CI yields clean plain text.
 *
 * <p>Uses Jansi's {@link Ansi} API rather than raw escape codes: Jansi already
 * handles Windows console translation and strips sequences when colour is
 * disabled.
 */
public final class AsciiBannerPrinter {

  private AsciiBannerPrinter() {}

  /**
   * Block glyphs. Each letter is five rows tall and uses the full block
   * character so the wordmark stays solid at any font, with a thin space
   * between letters for legibility.
   */
  private static final String[] WORDMARK = {
    "  █████╗ ███╗   ██╗ ██████╗ ██████╗  ██████╗ ",
    " ██╔══██╗████╗  ██║██╔═══██╗██╔══██╗██╔═══██╗",
    " ███████║██╔██╗ ██║██║   ██║██████╔╝██║   ██║",
    " ██╔══██║██║╚██╗██║██║   ██║██╔══██╗██║   ██║",
    " ██║  ██║██║ ╚████║╚██████╔╝██║  ██║╚██████╔╝",
    " ╚═╝  ╚═╝╚═╝  ╚═══╝ ╚═════╝ ╚═╝  ╚═╝ ╚═════╝ ",
  };

  /**
   * Per-row accent, top to bottom. A vertical gradient reads as intentional at
   * a glance; horizontal gradients look like corruption on narrow terminals.
   */
  private static final Ansi.Color[] GRADIENT = {
    Ansi.Color.CYAN,
    Ansi.Color.CYAN,
    Ansi.Color.BLUE,
    Ansi.Color.BLUE,
    Ansi.Color.MAGENTA,
    Ansi.Color.MAGENTA,
  };

  private static final String TAGLINE = "local-first code intelligence";
  private static final String HINT = "Type a question, or /help for commands.";

  public static void print() {
    System.out.println(render());
  }

  /** Exposed for tests: the banner with colour stripped. */
  static String render() {
    boolean color = useColor();
    StringBuilder out = new StringBuilder();

    // Leading blank line separates the wordmark from the shell's own output.
    out.append('\n');

    for (int row = 0; row < WORDMARK.length; row++) {
      String line = WORDMARK[row];
      if (color) {
        out.append(Ansi.ansi().fg(GRADIENT[row % GRADIENT.length]).a(line).reset());
      } else {
        out.append(line);
      }
      out.append('\n');
    }

    out.append('\n');

    String tagline = TAGLINE;
    if (color) {
      out.append(Ansi.ansi().fg(Ansi.Color.CYAN).a(tagline).reset());
    } else {
      out.append(tagline);
    }
    out.append('\n');

    if (color) {
      out.append(Ansi.ansi().fgBright(Ansi.Color.BLACK).a(HINT).reset());
    } else {
      out.append(HINT);
    }
    out.append('\n');

    return out.toString();
  }

  /**
   * Whether to emit escape sequences.
   *
   * <p>Honours {@code NO_COLOR} (the informal standard), {@code TERM=dumb}, and
   * non-tty stdout. Jansi is also consulted so behaviour matches the rest of the
   * CLI on Windows consoles.
   */
  private static boolean useColor() {
    if (System.getenv("NO_COLOR") != null) {
      return false;
    }
    String term = System.getenv("TERM");
    if (term != null && "dumb".equalsIgnoreCase(term.trim())) {
      return false;
    }
    if (System.console() == null) {
      return false;
    }
    try {
      if (!AnsiConsole.isInstalled()) {
        // Not installed by the runner (non-interactive path); assume a modern
        // terminal rather than silently dropping the gradient.
        return true;
      }
      return Ansi.isEnabled();
    } catch (RuntimeException | LinkageError ex) {
      return false;
    }
  }
}
