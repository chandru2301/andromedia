package com.andromedia.ingestion.slack;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SlackIngestionService {

  private static final Logger log = LoggerFactory.getLogger(SlackIngestionService.class);

  private final SlackApiClient apiClient;

  public SlackIngestionService(String botToken) {
    this(new SlackApiClient(botToken));
  }

  public SlackIngestionService(SlackApiClient apiClient) {
    this.apiClient = apiClient;
  }

  public IngestResult ingest(SlackIngestionOptions options) throws IOException {
    SlackIngestionOptions effective = options == null ? SlackIngestionOptions.defaults() : options;
    List<SlackApiClient.SlackChannel> available = apiClient.listChannels();
    List<SlackApiClient.SlackChannel> selected = resolveChannels(available, effective.channels());
    if (selected.isEmpty()) {
      throw new IllegalArgumentException(
          "No matching Slack channels found for configured names/ids: " + effective.channels());
    }

    List<SlackThreadEvent> threads = new ArrayList<>();
    Map<String, Instant> latestByChannel = new LinkedHashMap<>();

    for (SlackApiClient.SlackChannel channel : selected) {
      Instant cursor = effective.channelCursors().get(channel.id());
      Instant oldest = effective.oldest();
      if (cursor != null && (oldest == null || cursor.isAfter(oldest))) {
        oldest = cursor;
      }
      List<SlackMessage> history =
          apiClient.fetchHistory(channel.id(), oldest, null, effective.maxMessagesPerChannel());
      List<SlackMessage> filtered = new ArrayList<>();
      Set<String> replyParents = new LinkedHashSet<>();

      for (SlackMessage message : history) {
        if (!SlackMessageFilter.keep(message, effective.skipBotMessages())) {
          continue;
        }
        filtered.add(message);
        if (message.hasReplies()) {
          replyParents.add(message.ts());
        } else if (message.threadTs() != null
            && !message.threadTs().isBlank()
            && !message.threadTs().equals(message.ts())) {
          replyParents.add(message.threadTs());
        }
      }

      Map<String, SlackMessage> byTs = new LinkedHashMap<>();
      for (SlackMessage message : filtered) {
        byTs.put(message.ts(), message);
      }

      for (String threadTs : replyParents) {
        List<SlackMessage> replies = apiClient.fetchReplies(channel.id(), threadTs);
        for (SlackMessage reply : replies) {
          if (!SlackMessageFilter.keep(reply, effective.skipBotMessages())) {
            continue;
          }
          byTs.put(reply.ts(), reply);
        }
      }

      List<SlackMessage> channelMessages = new ArrayList<>(byTs.values());
      Set<String> uniqueUserIds = new LinkedHashSet<>();
      for (SlackMessage message : channelMessages) {
        if (message.userId() != null && !message.userId().isBlank()) {
          uniqueUserIds.add(message.userId());
        }
      }
      Map<String, String> resolvedNames = apiClient.resolveUserNames(uniqueUserIds);
      for (Map.Entry<String, String> entry : resolvedNames.entrySet()) {
        if (entry.getKey().equals(entry.getValue())) {
          log.warn(
              "Could not resolve Slack user {} to a display name (add users:read scope and reinstall the app)",
              entry.getKey());
        }
      }

      List<SlackThreadEvent> channelThreads =
          SlackThreadChunker.chunk(
              channel.id(),
              channel.name(),
              channelMessages,
              userId -> resolvedNames.getOrDefault(userId, userId));
      threads.addAll(channelThreads);

      Instant newest =
          channelMessages.stream()
              .map(message -> SlackThreadChunker.parseTs(message.ts()))
              .max(Instant::compareTo)
              .orElse(null);
      if (newest != null) {
        latestByChannel.put(channel.id(), newest);
      }
      log.info(
          "Slack channel #{}: {} messages → {} threads",
          channel.name(),
          channelMessages.size(),
          channelThreads.size());
    }

    return new IngestResult(threads, latestByChannel, Map.copyOf(apiClient.userNameCacheView()));
  }

  public static Instant defaultOldest(int lookbackDays) {
    int days = lookbackDays <= 0 ? 90 : lookbackDays;
    return Instant.now().minus(days, ChronoUnit.DAYS);
  }

  private static List<SlackApiClient.SlackChannel> resolveChannels(
      List<SlackApiClient.SlackChannel> available, List<String> configured) {
    if (configured == null || configured.isEmpty()) {
      return available.stream().filter(SlackApiClient.SlackChannel::isMember).toList();
    }
    Map<String, SlackApiClient.SlackChannel> byId = new LinkedHashMap<>();
    Map<String, SlackApiClient.SlackChannel> byName = new LinkedHashMap<>();
    for (SlackApiClient.SlackChannel channel : available) {
      byId.put(channel.id(), channel);
      byName.put(normalizeChannelName(channel.name()), channel);
    }
    List<SlackApiClient.SlackChannel> selected = new ArrayList<>();
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
      if (match != null && seen.add(match.id())) {
        selected.add(match);
      }
    }
    return selected;
  }

  private static String normalizeChannelName(String name) {
    String normalized = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    if (normalized.startsWith("#")) {
      normalized = normalized.substring(1);
    }
    return normalized;
  }

  public record IngestResult(
      List<SlackThreadEvent> threads,
      Map<String, Instant> latestTimestampByChannel,
      Map<String, String> userNames) {

    public IngestResult {
      threads = threads == null ? List.of() : List.copyOf(threads);
      latestTimestampByChannel =
          latestTimestampByChannel == null ? Map.of() : Map.copyOf(latestTimestampByChannel);
      userNames = userNames == null ? Map.of() : Map.copyOf(userNames);
    }

    public Map<String, String> latestTimestampStrings() {
      return latestTimestampByChannel.entrySet().stream()
          .collect(
              Collectors.toMap(
                  Map.Entry::getKey,
                  entry -> entry.getValue().toString(),
                  (a, b) -> b,
                  LinkedHashMap::new));
    }
  }
}
