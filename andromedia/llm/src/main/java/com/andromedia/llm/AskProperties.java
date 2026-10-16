package com.andromedia.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "andromedia.ask")
public class AskProperties {

  private int retrievalLimit = 8;
  private int maxPromptChars = 12_000;

  public int retrievalLimit() {
    return retrievalLimit;
  }

  public void setRetrievalLimit(int retrievalLimit) {
    this.retrievalLimit = retrievalLimit;
  }

  public int maxPromptChars() {
    return maxPromptChars;
  }

  public void setMaxPromptChars(int maxPromptChars) {
    this.maxPromptChars = Math.max(1, maxPromptChars);
  }

  /** Backward-compatible binding for {@code andromedia.ask.max-context-chars}. */
  public int maxContextChars() {
    return maxPromptChars;
  }

  public void setMaxContextChars(int maxContextChars) {
    setMaxPromptChars(maxContextChars);
  }
}
