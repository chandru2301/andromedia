package com.andromedia.cli;

import org.springframework.stereotype.Component;

@Component
public class CliExecutionContext {

  private boolean debugEnabled;
  private boolean plainProgress;
  private boolean verbose;

  public boolean isDebugEnabled() {
    return debugEnabled;
  }

  public void setDebugEnabled(boolean debugEnabled) {
    this.debugEnabled = debugEnabled;
  }

  public boolean isPlainProgress() {
    return plainProgress;
  }

  public void setPlainProgress(boolean plainProgress) {
    this.plainProgress = plainProgress;
  }

  public boolean isVerbose() {
    return verbose;
  }

  public void setVerbose(boolean verbose) {
    this.verbose = verbose;
  }
}
