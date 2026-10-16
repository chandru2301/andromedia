package com.andromedia.core.index;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "andromedia.slack")
public class SlackIngestionProperties {

  private String botToken = "";
  private List<String> channels = new ArrayList<>();
  private int lookbackDays = 90;
  private boolean skipBotMessages = true;
  private int maxMessagesPerChannel = 5_000;

  public String botToken() {
    return botToken;
  }

  public void setBotToken(String botToken) {
    this.botToken = botToken == null ? "" : botToken;
  }

  public List<String> channels() {
    return channels;
  }

  public void setChannels(List<String> channels) {
    this.channels = channels == null ? new ArrayList<>() : new ArrayList<>(channels);
  }

  public int lookbackDays() {
    return lookbackDays;
  }

  public void setLookbackDays(int lookbackDays) {
    this.lookbackDays = lookbackDays;
  }

  public boolean skipBotMessages() {
    return skipBotMessages;
  }

  public void setSkipBotMessages(boolean skipBotMessages) {
    this.skipBotMessages = skipBotMessages;
  }

  public int maxMessagesPerChannel() {
    return maxMessagesPerChannel;
  }

  public void setMaxMessagesPerChannel(int maxMessagesPerChannel) {
    this.maxMessagesPerChannel = maxMessagesPerChannel;
  }
}
