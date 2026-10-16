package com.andromedia.cli.progress;

import java.io.PrintStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class TerminalProgressReporter implements CliProgressReporter {

  private static final char[] SPINNER = {'⠋', '⠙', '⠹', '⠸', '⠼', '⠴', '⠦', '⠧', '⠇', '⠏'};

  private final PrintStream out;
  private int spinnerIndex;
  private CliPhase currentPhase;
  private String currentDetail = "";
  private final AtomicBoolean animating = new AtomicBoolean(false);
  private volatile boolean sealed;
  private Thread animationThread;

  public TerminalProgressReporter() {
    this(System.err);
  }

  TerminalProgressReporter(PrintStream out) {
    this.out = out;
  }

  @Override
  public synchronized void startPhase(CliPhase phase) {
    if (sealed) {
      return;
    }
    stopAnimation();
    currentPhase = phase;
    currentDetail = "";
    renderLine();
    startAnimation();
  }

  @Override
  public synchronized void updatePhase(CliPhase phase, String detail) {
    if (sealed) {
      return;
    }
    currentPhase = phase;
    currentDetail = detail == null ? "" : detail;
    renderLine();
  }

  @Override
  public synchronized void completePhase(CliPhase phase, Duration duration) {
    stopAnimation();
    clearLine();
    out.println(CompletionLine.format(phase, duration, false));
    out.flush();
    currentPhase = null;
    currentDetail = "";
  }

  @Override
  public synchronized void seal() {
    stopAnimation();
    clearLine();
    sealed = true;
    currentPhase = null;
    currentDetail = "";
  }

  @Override
  public synchronized void fail(String message) {
    stopAnimation();
    clearLine();
    out.printf("[andro] failed: %s%n", message);
    out.flush();
  }

  @Override
  public void printTimingSummary(List<PhaseTiming> timings, boolean plainSymbols) {
    if (timings == null || timings.isEmpty()) {
      return;
    }
    for (PhaseTiming timing : timings) {
      out.println(CompletionLine.format(timing.phase(), timing.duration(), plainSymbols));
    }
    out.println();
  }

  @Override
  public void waitWithProgress(CliPhase phase, String detail, long totalMillis) {
    if (sealed) {
      return;
    }
    stopAnimation();
    currentPhase = phase;
    currentDetail = detail == null ? "" : detail;
    long deadline = System.currentTimeMillis() + totalMillis;
    animating.set(true);
    while (animating.get() && System.currentTimeMillis() < deadline) {
      renderLine();
      spinnerIndex = (spinnerIndex + 1) % SPINNER.length;
      try {
        Thread.sleep(120);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    animating.set(false);
    clearLine();
  }

  private void startAnimation() {
    animating.set(true);
    animationThread =
        new Thread(
            () -> {
              while (animating.get()) {
                synchronized (TerminalProgressReporter.this) {
                  if (!animating.get() || sealed) {
                    break;
                  }
                  renderLine();
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
            "andro-progress-spinner");
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

  private void renderLine() {
    if (currentPhase == null) {
      return;
    }
    String message = currentPhase.label();
    if (!currentDetail.isBlank()) {
      message = message + " (" + currentDetail + ")";
    }
    out.printf("\r%c %s   ", SPINNER[spinnerIndex], message);
    out.flush();
  }

  private void clearLine() {
    out.print("\r\u001B[2K");
    out.flush();
  }
}
