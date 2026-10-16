package com.andromedia.core.index;

import com.andromedia.ingestion.jira.JiraApiClient;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class JiraConnectionTestService {

  public JiraTestResult test(String cloudId, String accessToken, List<String> projectKeys) {
    if (cloudId == null || cloudId.isBlank()) {
      throw new IllegalArgumentException("Jira cloudId is required");
    }
    if (accessToken == null || accessToken.isBlank()) {
      throw new IllegalArgumentException("Jira access token is required");
    }
    JiraApiClient client = new JiraApiClient(cloudId.trim(), accessToken.trim());
    try {
      JiraApiClient.JiraMyselfResult me = client.myself();
      List<String> accessible = new ArrayList<>();
      List<String> missing = new ArrayList<>();
      if (projectKeys != null && !projectKeys.isEmpty()) {
        try {
          var issues = client.searchIssues(projectKeys);
          for (String key : projectKeys) {
            boolean found =
                issues.stream().anyMatch(i -> key.equalsIgnoreCase(i.projectKey()));
            if (found) {
              accessible.add(key);
            } else {
              // project may exist with zero issues — treat successful search as accessible
              accessible.add(key);
            }
          }
        } catch (IOException ex) {
          missing.addAll(projectKeys);
        }
      }
      return new JiraTestResult(true, me.displayName(), me.email(), accessible, missing);
    } catch (IOException ex) {
      throw new IllegalArgumentException(
          ex.getMessage() == null ? "Jira API call failed" : ex.getMessage());
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
    return Arrays.stream(csv.split(",")).map(String::trim).filter(id -> !id.isBlank()).toList();
  }

  public record JiraTestResult(
      boolean ok,
      String displayName,
      String email,
      List<String> accessibleProjectKeys,
      List<String> missingProjectKeys) {

    public JiraTestResult {
      accessibleProjectKeys =
          accessibleProjectKeys == null ? List.of() : List.copyOf(accessibleProjectKeys);
      missingProjectKeys = missingProjectKeys == null ? List.of() : List.copyOf(missingProjectKeys);
    }
  }
}
