package com.andromedia.cli.progress;

/** Controls how much phase detail is shown during CLI operations. */
public enum ProgressMode {
  /** Collapsed status for `andro ask` (default). */
  QUIET,
  /** Per-phase checkmarks and timing lines. */
  VERBOSE
}
