package com.andromedia.core.index;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "andromedia.jira")
public class JiraIngestionProperties {

  private List<String> projectKeys = new ArrayList<>();
  private int lookbackDays = 90;

  public List<String> projectKeys() {
    return projectKeys;
  }

  public void setProjectKeys(List<String> projectKeys) {
    this.projectKeys = projectKeys == null ? new ArrayList<>() : new ArrayList<>(projectKeys);
  }

  public int lookbackDays() {
    return lookbackDays;
  }

  public void setLookbackDays(int lookbackDays) {
    this.lookbackDays = lookbackDays <= 0 ? 90 : lookbackDays;
  }
}
