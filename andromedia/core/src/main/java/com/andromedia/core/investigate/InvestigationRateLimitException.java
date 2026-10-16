package com.andromedia.core.investigate;

/**
 * Thrown when CRM investigation cannot complete because the chat provider is rate-limited or the
 * retry wait was interrupted. Mapped to HTTP 503 by the API layer.
 */
public class InvestigationRateLimitException extends RuntimeException {

  public InvestigationRateLimitException(String message) {
    super(message);
  }

  public InvestigationRateLimitException(String message, Throwable cause) {
    super(message, cause);
  }

  static boolean isRateLimitMessage(String message) {
    if (message == null || message.isBlank()) {
      return false;
    }
    String lower = message.toLowerCase();
    return lower.contains("429")
        || lower.contains("rate limit")
        || lower.contains("rate-limit")
        || lower.contains("too many requests")
        || lower.contains("retry interrupted")
        || lower.contains("request retry interrupted");
  }
}
