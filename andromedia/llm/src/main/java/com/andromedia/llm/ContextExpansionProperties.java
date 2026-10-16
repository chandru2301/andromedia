package com.andromedia.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "andromedia.context")
public class ContextExpansionProperties {

  private int neighborLineWindow = 15;
  private int maxHeaderLines = 40;

  public int neighborLineWindow() {
    return neighborLineWindow;
  }

  public void setNeighborLineWindow(int neighborLineWindow) {
    this.neighborLineWindow = neighborLineWindow;
  }

  public int maxHeaderLines() {
    return maxHeaderLines;
  }

  public void setMaxHeaderLines(int maxHeaderLines) {
    this.maxHeaderLines = maxHeaderLines;
  }
}
