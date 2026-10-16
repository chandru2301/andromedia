package com.andromedia.core.index;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "andromedia.git")
public class GitIngestionProperties {

  private int maxCommits = 1000;

  public int maxCommits() {
    return maxCommits;
  }

  public void setMaxCommits(int maxCommits) {
    this.maxCommits = maxCommits;
  }
}
