package com.andromedia.cli.qa;

import com.andromedia.cli.progress.CliProgressReporter;
import com.andromedia.cli.progress.ProgressMode;
import java.util.List;

public record AskOptions(
    boolean dryRun,
    boolean stream,
    boolean debug,
    boolean plainProgress,
    boolean verbose,
    boolean graphContext,
    boolean deepExplore,
    List<String> fileAttachments,
    CliProgressReporter progress) {

  public static AskOptions of(
      boolean dryRun,
      boolean stream,
      boolean debug,
      boolean plainProgress,
      boolean verbose) {
    return of(dryRun, stream, debug, plainProgress, verbose, false, false, List.of());
  }

  public static AskOptions of(
      boolean dryRun,
      boolean stream,
      boolean debug,
      boolean plainProgress,
      boolean verbose,
      boolean graphContext,
      boolean deepExplore,
      List<String> fileAttachments) {
    boolean enabled = !dryRun;
    ProgressMode mode = verbose ? ProgressMode.VERBOSE : ProgressMode.QUIET;
    return new AskOptions(
        dryRun,
        stream,
        debug,
        plainProgress,
        verbose,
        graphContext,
        deepExplore,
        fileAttachments == null ? List.of() : List.copyOf(fileAttachments),
        CliProgressReporter.create(plainProgress, enabled, mode));
  }

  public static AskOptions defaults() {
    return of(false, true, false, true, false);
  }
}
