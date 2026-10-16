package com.andromedia.core.index;

import com.andromedia.ingestion.slack.SlackApiClient;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class SlackConnectionTestService {

  public SlackTestResult test(String botToken, List<String> channels) {
    if (botToken == null || botToken.isBlank()) {
      throw new IllegalArgumentException("Slack bot token is required");
    }

    SlackApiClient client = new SlackApiClient(botToken.trim());
    try {
      SlackApiClient.SlackAuthTestResult auth = client.authTest();
      List<SlackApiClient.SlackChannel> available = client.listChannels();
      ChannelMatch match = resolveChannels(available, channels);

      return new SlackTestResult(
          true,
          auth.team(),
          auth.teamId(),
          auth.user(),
          match.resolved(),
          match.missing());
    } catch (IOException ex) {
      throw new IllegalArgumentException(ex.getMessage() == null ? "Slack API call failed" : ex.getMessage());
    }
  }

  static ChannelMatch resolveChannels(
      List<SlackApiClient.SlackChannel> available, List<String> configured) {
    if (configured == null || configured.isEmpty()) {
      return new ChannelMatch(List.of(), List.of());
    }

    Map<String, SlackApiClient.SlackChannel> byId = new LinkedHashMap<>();
    Map<String, SlackApiClient.SlackChannel> byName = new LinkedHashMap<>();
    for (SlackApiClient.SlackChannel channel : available) {
      byId.put(channel.id(), channel);
      byName.put(normalizeChannelName(channel.name()), channel);
    }

    List<String> resolved = new ArrayList<>();
    List<String> missing = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();

    for (String raw : configured) {
      if (raw == null || raw.isBlank()) {
        continue;
      }
      String trimmed = raw.trim();
      SlackApiClient.SlackChannel match = byId.get(trimmed);
      if (match == null) {
        match = byName.get(normalizeChannelName(trimmed));
      }
      if (match == null) {
        missing.add(trimmed);
        continue;
      }
      if (seen.add(match.id())) {
        resolved.add("#" + match.name());
      }
    }
    return new ChannelMatch(List.copyOf(resolved), List.copyOf(missing));
  }

  public static List<String> parseChannels(Object channels) {
    if (channels == null) {
      return List.of();
    }
    if (channels instanceof List<?> list) {
      List<String> parsed = new ArrayList<>();
      for (Object item : list) {
        if (item != null && !item.toString().isBlank()) {
          parsed.add(item.toString().trim());
        }
      }
      return parsed;
    }
    String csv = channels.toString();
    if (csv.isBlank()) {
      return List.of();
    }
    return Arrays.stream(csv.split(","))
        .map(String::trim)
        .filter(value -> !value.isBlank())
        .toList();
  }

  private static String normalizeChannelName(String name) {
    String normalized = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    if (normalized.startsWith("#")) {
      normalized = normalized.substring(1);
    }
    return normalized;
  }

  public record SlackTestResult(
      boolean ok,
      String team,
      String teamId,
      String user,
      List<String> resolvedChannels,
      List<String> missingChannels) {

    public SlackTestResult {
      resolvedChannels = resolvedChannels == null ? List.of() : List.copyOf(resolvedChannels);
      missingChannels = missingChannels == null ? List.of() : List.copyOf(missingChannels);
    }
  }

  record ChannelMatch(List<String> resolved, List<String> missing) {}
}
