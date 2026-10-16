package com.andromedia.ingestion.googlechat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class GoogleChatThreadChunker {

  private GoogleChatThreadChunker() {}

  public static List<GoogleChatThreadEvent> chunk(
      String spaceId, String spaceName, List<GoogleChatMessage> messages) {
    if (messages == null || messages.isEmpty()) {
      return List.of();
    }

    Map<String, List<GoogleChatMessage>> byThread = new LinkedHashMap<>();
    for (GoogleChatMessage message : messages) {
      byThread
          .computeIfAbsent(message.effectiveThreadName(), key -> new ArrayList<>())
          .add(message);
    }

    List<GoogleChatThreadEvent> threads = new ArrayList<>();
    for (Map.Entry<String, List<GoogleChatMessage>> entry : byThread.entrySet()) {
      List<GoogleChatMessage> threadMessages = new ArrayList<>(entry.getValue());
      threadMessages.sort(Comparator.comparing(GoogleChatMessage::createTime));

      Set<String> participants = new LinkedHashSet<>();
      for (GoogleChatMessage message : threadMessages) {
        String display = message.senderDisplayName();
        if (display != null && !display.isBlank()) {
          participants.add(display);
        } else if (message.senderId() != null && !message.senderId().isBlank()) {
          participants.add(message.senderId());
        }
      }

      Instant timestamp = threadMessages.getFirst().createTime();
      threads.add(
          new GoogleChatThreadEvent(
              spaceId, spaceName, entry.getKey(), timestamp, List.copyOf(participants), threadMessages));
    }

    threads.sort(Comparator.comparing(GoogleChatThreadEvent::timestamp).reversed());
    return List.copyOf(threads);
  }
}
