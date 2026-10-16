package com.andromedia.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "andromedia.side-query")
public class SideQueryProperties {

  private boolean enabled = true;
  private String model = "qwen/qwen3.8-27b";
  private int maxOutputTokens = 512;
  private double temperature = 0.0;

  public boolean enabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String model() {
    return model;
  }

  public void setModel(String model) {
    this.model = model;
  }

  public int maxOutputTokens() {
    return maxOutputTokens;
  }

  public void setMaxOutputTokens(int maxOutputTokens) {
    this.maxOutputTokens = Math.max(64, maxOutputTokens);
  }

  public double temperature() {
    return temperature;
  }

  public void setTemperature(double temperature) {
    this.temperature = temperature;
  }
}
