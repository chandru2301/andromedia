package com.andromedia.core.alerts;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "andromedia.alerts.slack")
public class PipelineAlertProperties {

  /** Optional env fallback when per-service PipelineAlertStore has no bot token. */
  private String botToken = "";

  /** Optional env fallback channel id or #name when store has no channel. */
  private String channel = "";

  private boolean enabledByDefault = false;

  public String botToken() {
    return botToken;
  }

  public void setBotToken(String botToken) {
    this.botToken = botToken == null ? "" : botToken;
  }

  public String channel() {
    return channel;
  }

  public void setChannel(String channel) {
    this.channel = channel == null ? "" : channel;
  }

  public boolean enabledByDefault() {
    return enabledByDefault;
  }

  public void setEnabledByDefault(boolean enabledByDefault) {
    this.enabledByDefault = enabledByDefault;
  }
}
