package com.andromedia.core.index;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "andromedia.linear")
public class LinearIngestionProperties {

  private String apiKey = "";
  private List<String> teamKeys = new ArrayList<>();
  private int lookbackDays = 90;

  public String apiKey() {
    return apiKey;
  }

  public void setApiKey(String apiKey) {
    this.apiKey = apiKey == null ? "" : apiKey;
  }

  public List<String> teamKeys() {
    return teamKeys;
  }

  public void setTeamKeys(List<String> teamKeys) {
    this.teamKeys = teamKeys == null ? new ArrayList<>() : new ArrayList<>(teamKeys);
  }

  public int lookbackDays() {
    return lookbackDays;
  }

  public void setLookbackDays(int lookbackDays) {
    this.lookbackDays = lookbackDays <= 0 ? 90 : lookbackDays;
  }
}
