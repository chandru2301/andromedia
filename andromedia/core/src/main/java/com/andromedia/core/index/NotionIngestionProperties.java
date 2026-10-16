package com.andromedia.core.index;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "andromedia.notion")
public class NotionIngestionProperties {

  private String apiKey = "";
  private List<String> pageIds = new ArrayList<>();
  private List<String> databaseIds = new ArrayList<>();

  public String apiKey() {
    return apiKey;
  }

  public void setApiKey(String apiKey) {
    this.apiKey = apiKey == null ? "" : apiKey;
  }

  public List<String> pageIds() {
    return pageIds;
  }

  public void setPageIds(List<String> pageIds) {
    this.pageIds = pageIds == null ? new ArrayList<>() : new ArrayList<>(pageIds);
  }

  public List<String> databaseIds() {
    return databaseIds;
  }

  public void setDatabaseIds(List<String> databaseIds) {
    this.databaseIds = databaseIds == null ? new ArrayList<>() : new ArrayList<>(databaseIds);
  }
}
