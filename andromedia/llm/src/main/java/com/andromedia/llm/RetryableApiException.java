package com.andromedia.llm;

import java.util.OptionalLong;

public class RetryableApiException extends ChatException {

  private final int statusCode;
  private final OptionalLong retryAfterSeconds;

  public RetryableApiException(String message, int statusCode, OptionalLong retryAfterSeconds) {
    super(message);
    this.statusCode = statusCode;
    this.retryAfterSeconds = retryAfterSeconds == null ? OptionalLong.empty() : retryAfterSeconds;
  }

  public RetryableApiException(String message, Throwable cause) {
    super(message, cause);
    this.statusCode = 0;
    this.retryAfterSeconds = OptionalLong.empty();
  }

  public int statusCode() {
    return statusCode;
  }

  public OptionalLong retryAfterSeconds() {
    return retryAfterSeconds;
  }
}
