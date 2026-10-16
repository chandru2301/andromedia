package com.andromedia.cli.progress;

import com.andromedia.cli.output.CliTheme;
import com.andromedia.cli.output.SystemLines;
import java.io.PrintStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Collapsed progress for {@code andro ask}: one analyzing phase, one context-ready line, thinking
 * spinner until sealed.
 */
public class QuietProgressReporter implements CliProgressReporter {

  private static final char[] SPINNER = {'⠋', '⠙', '⠹', '⠸', '⠼', '⠴', '⠦', '⠧', '⠇', '⠏'};

  private static final String ANALYZING_LABEL = "Analyzing codebase...";
  private static final String THINKING_LABEL = "Thinking...";

  private final PrintStream out;
  private final CliTheme theme;
  private final boolean useSpinner;

  private int spinnerIndex;
  private String spinnerLabel = "";
  private final AtomicBoolean animating = new AtomicBoolean(false);
  private volatile boolean sealed;
  private Thread animationThread;
  private boolean contextReadyPrinted;
  private boolean thinkingStarted;

  public QuietProgressReporter(boolean plain) {
    this(System.err, plain);
  }

  QuietProgressReporter(PrintStream out, boolean plain) {
    this.out = out;
    this.theme = new CliTheme(plain);
    this.useSpinner = !plain && TerminalDetector.isInteractive(out);
  }

  @Override
  public void startPhase(CliPhase phase) {
    if (sealed) {
      return;
    }
    switch (phase) {
      case HYBRID_RETRIEVAL, EXPANDING_CONTEXT -> startAnalyzingIfNeeded();
      case WAITING_FOR_MODEL -> startThinkingIfNeeded();
      case RETRYING_RATE_LIMIT -> showBriefRetry();
      default -> {}
    }
  }

  @Override
  public void updatePhase(CliPhase phase, String detail) {
    // Quiet mode ignores granular updates.
  }

  @Override
  public void completePhase(CliPhase phase, Duration duration) {
    // Completion lines are collapsed; see preparationComplete().
  }

  @Override
  public void preparationComplete(Duration totalDuration) {
    if (sealed || contextReadyPrinted) {
      return;
    }
    stopAnimation();
    clearLine();
    contextReadyPrinted = true;
    SystemLines.printlnSuccess(out, theme, "Context ready", totalDuration);
  }

  @Override
  public void seal() {
    stopAnimation();
    clearLine();
    sealed = true;
  }

  @Override
  public void fail(String message) {
    stopAnimation();
    clearLine();
    out.printf("%s failed: %s%n", theme.system("[andro]"), message);
    out.flush();
  }

  @Override
  public void printTimingSummary(List<PhaseTiming> timings, boolean plainSymbols) {
    // Timing summary is shown via AskMetricsFooter in verbose mode only.
  }

  @Override
  public void waitWithProgress(CliPhase phase, String detail, long totalMillis) {
    if (sealed) {
      return;
    }
    showBriefRetry();
    long deadline = System.currentTimeMillis() + totalMillis;
    while (System.currentTimeMillis() < deadline && !sealed) {
      if (useSpinner && thinkingStarted) {
        renderSpinnerLine(THINKING_LABEL);
        spinnerIndex = (spinnerIndex + 1) % SPINNER.length;
      }
      try {
        Thread.sleep(120);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    clearLine();
  }

  private void startAnalyzingIfNeeded() {
    if (contextReadyPrinted || thinkingStarted) {
      return;
    }
    if (!useSpinner) {
      SystemLines.println(out, theme, ANALYZING_LABEL);
      return;
    }
    spinnerLabel = ANALYZING_LABEL;
    renderSpinnerLine(spinnerLabel);
    startAnimation();
  }

  private void startThinkingIfNeeded() {
    if (sealed || thinkingStarted) {
      return;
    }
    thinkingStarted = true;
    if (!useSpinner) {
      SystemLines.println(out, theme, THINKING_LABEL);
      return;
    }
    spinnerLabel = THINKING_LABEL;
    renderSpinnerLine(spinnerLabel);
    if (!animating.get()) {
      startAnimation();
    }
  }

  private void showBriefRetry() {
    if (sealed) {
      return;
    }
    stopAnimation();
    clearLine();
    SystemLines.println(out, theme, "Retrying...");
    if (thinkingStarted && useSpinner) {
      spinnerLabel = THINKING_LABEL;
      renderSpinnerLine(spinnerLabel);
      startAnimation();
    }
  }

  private void startAnimation() {
    animating.set(true);
    animationThread =
        new Thread(
            () -> {
              while (animating.get()) {
                synchronized (QuietProgressReporter.this) {
                  if (!animating.get() || sealed) {
                    break;
                  }
                  renderSpinnerLine(spinnerLabel);
                  spinnerIndex = (spinnerIndex + 1) % SPINNER.length;
                }
                try {
                  Thread.sleep(120);
                } catch (InterruptedException ex) {
                  Thread.currentThread().interrupt();
                  break;
                }
              }
            },
            "andro-quiet-spinner");
    animationThread.setDaemon(true);
    animationThread.start();
  }

  private void stopAnimation() {
    animating.set(false);
    if (animationThread != null) {
      animationThread.interrupt();
      try {
        animationThread.join(500);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
      }
      animationThread = null;
    }
  }

  private void renderSpinnerLine(String label) {
    out.printf("\r%c %s   ", SPINNER[spinnerIndex], label);
    out.flush();
  }

  private void clearLine() {
    if (useSpinner) {
      out.print("\r\u001B[2K");
      out.flush();
    }
  }
}
