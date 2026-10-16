package com.andromedia.core.index;

import com.andromedia.ingestion.notion.NotionApiClient;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class NotionConnectionTestService {

  public NotionTestResult test(String apiKey, List<String> pageIds) {
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalArgumentException("Notion API key is required");
    }
    NotionApiClient client = new NotionApiClient(apiKey.trim());
    try {
      NotionApiClient.NotionMeResult me = client.usersMe();
      List<String> accessible = new ArrayList<>();
      List<String> missing = new ArrayList<>();
      if (pageIds != null) {
        for (String pageId : pageIds) {
          if (pageId == null || pageId.isBlank()) {
            continue;
          }
          try {
            client.retrievePage(pageId.trim());
            accessible.add(pageId.trim());
          } catch (IOException ex) {
            missing.add(pageId.trim());
          }
        }
      }
      return new NotionTestResult(true, me.workspace(), me.user(), accessible, missing);
    } catch (IOException ex) {
      throw new IllegalArgumentException(
          ex.getMessage() == null ? "Notion API call failed" : ex.getMessage());
    }
  }

  public static List<String> parseIds(Object value) {
    if (value == null) {
      return List.of();
    }
    if (value instanceof List<?> list) {
      List<String> parsed = new ArrayList<>();
      for (Object item : list) {
        if (item != null && !item.toString().isBlank()) {
          parsed.add(item.toString().trim());
        }
      }
      return parsed;
    }
    String csv = value.toString();
    if (csv.isBlank()) {
      return List.of();
    }
    return Arrays.stream(csv.split(","))
        .map(String::trim)
        .filter(id -> !id.isBlank())
        .toList();
  }

  public record NotionTestResult(
      boolean ok,
      String workspace,
      String user,
      List<String> accessiblePageIds,
      List<String> missingPageIds) {

    public NotionTestResult {
      accessiblePageIds = accessiblePageIds == null ? List.of() : List.copyOf(accessiblePageIds);
      missingPageIds = missingPageIds == null ? List.of() : List.copyOf(missingPageIds);
    }
  }
}
