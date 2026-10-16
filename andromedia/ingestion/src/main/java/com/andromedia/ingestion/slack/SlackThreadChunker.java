package com.andromedia.ingestion.slack;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public final class SlackThreadChunker {

  private SlackThreadChunker() {}

  public static List<SlackThreadEvent> chunk(
      String channelId,
      String channelName,
      List<SlackMessage> messages,
      Function<String, String> userDisplayName) {
    if (messages == null || messages.isEmpty()) {
      return List.of();
    }

    Map<String, List<SlackMessage>> byThread = new LinkedHashMap<>();
    for (SlackMessage message : messages) {
      String threadKey = message.effectiveThreadTs();
      byThread.computeIfAbsent(threadKey, key -> new ArrayList<>()).add(message);
    }

    List<SlackThreadEvent> threads = new ArrayList<>();
    for (Map.Entry<String, List<SlackMessage>> entry : byThread.entrySet()) {
      List<SlackMessage> threadMessages = new ArrayList<>(entry.getValue());
      threadMessages.sort(Comparator.comparing(SlackMessage::ts));

      Set<String> participants = new LinkedHashSet<>();
      int reactionCount = 0;
      for (SlackMessage message : threadMessages) {
        String display = userDisplayName.apply(message.userId());
        if (display != null && !display.isBlank()) {
          participants.add(display);
        } else if (message.userId() != null && !message.userId().isBlank()) {
          participants.add(message.userId());
        }
        reactionCount += Math.max(0, message.reactionCount());
      }

      Instant timestamp = parseTs(threadMessages.getFirst().ts());
      threads.add(
          new SlackThreadEvent(
              channelId,
              channelName,
              entry.getKey(),
              timestamp,
              List.copyOf(participants),
              reactionCount,
              threadMessages));
    }

    threads.sort(Comparator.comparing(SlackThreadEvent::timestamp).reversed());
    return List.copyOf(threads);
  }

  static Instant parseTs(String ts) {
    if (ts == null || ts.isBlank()) {
      return Instant.EPOCH;
    }
    try {
      double seconds = Double.parseDouble(ts);
      long epochMillis = Math.round(seconds * 1000.0d);
      return Instant.ofEpochMilli(epochMillis);
    } catch (NumberFormatException ex) {
      return Instant.EPOCH;
    }
  }
}
