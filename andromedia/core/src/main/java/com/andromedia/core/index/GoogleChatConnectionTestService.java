package com.andromedia.core.index;

import com.andromedia.ingestion.googlechat.GoogleChatApiClient;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class GoogleChatConnectionTestService {

  public GoogleChatTestResult test(String accessToken, List<String> spaces) {
    if (accessToken == null || accessToken.isBlank()) {
      throw new IllegalArgumentException("Google Chat access token is required");
    }

    GoogleChatApiClient client = new GoogleChatApiClient(accessToken.trim());
    try {
      List<GoogleChatApiClient.GoogleChatSpace> available = client.listSpaces();
      SpaceMatch match = resolveSpaces(available, spaces);
      return new GoogleChatTestResult(true, available.size(), match.resolved(), match.missing());
    } catch (IOException ex) {
      throw new IllegalArgumentException(
          ex.getMessage() == null ? "Google Chat API call failed" : ex.getMessage());
    }
  }

  static SpaceMatch resolveSpaces(
      List<GoogleChatApiClient.GoogleChatSpace> available, List<String> configured) {
    if (configured == null || configured.isEmpty()) {
      return new SpaceMatch(List.of(), List.of());
    }
    Map<String, GoogleChatApiClient.GoogleChatSpace> byName = new LinkedHashMap<>();
    Map<String, GoogleChatApiClient.GoogleChatSpace> byDisplayName = new LinkedHashMap<>();
    for (GoogleChatApiClient.GoogleChatSpace space : available) {
      byName.put(space.name(), space);
      byDisplayName.put(normalize(space.displayName()), space);
    }

    List<String> resolved = new ArrayList<>();
    List<String> missing = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();

    for (String raw : configured) {
      if (raw == null || raw.isBlank()) {
        continue;
      }
      String trimmed = raw.trim();
      GoogleChatApiClient.GoogleChatSpace match = byName.get(trimmed);
      if (match == null) {
        match = byDisplayName.get(normalize(trimmed));
      }
      if (match == null) {
        missing.add(trimmed);
        continue;
      }
      if (seen.add(match.name())) {
        resolved.add(match.displayName());
      }
    }
    return new SpaceMatch(List.copyOf(resolved), List.copyOf(missing));
  }

  private static String normalize(String name) {
    return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
  }

  public record GoogleChatTestResult(
      boolean ok, int totalSpaces, List<String> resolvedSpaces, List<String> missingSpaces) {

    public GoogleChatTestResult {
      resolvedSpaces = resolvedSpaces == null ? List.of() : List.copyOf(resolvedSpaces);
      missingSpaces = missingSpaces == null ? List.of() : List.copyOf(missingSpaces);
    }
  }

  record SpaceMatch(List<String> resolved, List<String> missing) {}
}
