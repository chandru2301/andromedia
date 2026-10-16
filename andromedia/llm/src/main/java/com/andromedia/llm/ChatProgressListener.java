package com.andromedia.llm;

public interface ChatProgressListener {

  ChatProgressListener NOOP = new ChatProgressListener() {};

  default void onRequestStarted(String model) {}

  default void onRetry(int attempt, int maxAttempts, long delayMs, int statusCode) {}

  /** When true, the listener performs the retry wait and OpenRouterChatService will not sleep. */
  default boolean handlesRetryDelay() {
    return false;
  }

  default void onFirstToken() {}
}
