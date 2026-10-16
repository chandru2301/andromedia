package com.andromedia.llm;

public class ChatException extends RuntimeException {

  private final boolean transientFailure;

  public ChatException(String message) {
    super(message);
    this.transientFailure = false;
  }

  public ChatException(String message, Throwable cause) {
    super(message, cause);
    this.transientFailure = false;
  }

  ChatException(String message, Throwable cause, boolean transientFailure) {
    super(message, cause);
    this.transientFailure = transientFailure;
  }

  public boolean isTransientFailure() {
    return transientFailure;
  }
}
