package com.andromedia.core.index;

import com.andromedia.ingestion.linear.LinearApiClient;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class LinearConnectionTestService {

  public LinearTestResult test(String apiKey, List<String> teamKeys) {
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalArgumentException("Linear API key is required");
    }
    LinearApiClient client = new LinearApiClient(apiKey.trim());
    try {
      LinearApiClient.LinearViewerResult viewer = client.viewer();
      List<String> accessible = new ArrayList<>();
      List<String> missing = new ArrayList<>();
      if (teamKeys != null && !teamKeys.isEmpty()) {
        List<LinearApiClient.LinearTeam> teams = client.listTeams();
        for (String teamKey : teamKeys) {
          if (teamKey == null || teamKey.isBlank()) {
            continue;
          }
          String token = teamKey.trim();
          if (LinearApiClient.findTeamKey(teams, token) != null) {
            accessible.add(token);
          } else {
            missing.add(token);
          }
        }
      }
      return new LinearTestResult(true, viewer.name(), viewer.id(), accessible, missing);
    } catch (IOException ex) {
      throw new IllegalArgumentException(
          ex.getMessage() == null ? "Linear API call failed" : ex.getMessage());
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

  public record LinearTestResult(
      boolean ok,
      String name,
      String userId,
      List<String> accessibleTeamKeys,
      List<String> missingTeamKeys) {

    public LinearTestResult {
      accessibleTeamKeys =
          accessibleTeamKeys == null ? List.of() : List.copyOf(accessibleTeamKeys);
      missingTeamKeys = missingTeamKeys == null ? List.of() : List.copyOf(missingTeamKeys);
    }
  }
}
