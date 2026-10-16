package com.andromedia.core.index;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "andromedia.google-chat")
public class GoogleChatIngestionProperties {

  private List<String> spaces = new ArrayList<>();
  private int lookbackDays = 90;
  private int maxMessagesPerSpace = 5_000;

  public List<String> spaces() {
    return spaces;
  }

  public void setSpaces(List<String> spaces) {
    this.spaces = spaces == null ? new ArrayList<>() : new ArrayList<>(spaces);
  }

  public int lookbackDays() {
    return lookbackDays;
  }

  public void setLookbackDays(int lookbackDays) {
    this.lookbackDays = lookbackDays;
  }

  public int maxMessagesPerSpace() {
    return maxMessagesPerSpace;
  }

  public void setMaxMessagesPerSpace(int maxMessagesPerSpace) {
    this.maxMessagesPerSpace = maxMessagesPerSpace;
  }
}
