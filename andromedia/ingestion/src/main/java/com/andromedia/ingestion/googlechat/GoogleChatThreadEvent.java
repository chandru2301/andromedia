package com.andromedia.ingestion.googlechat;

import java.time.Instant;
import java.util.List;

/** One Google Chat conversation thread, ready for chunk building. */
public record GoogleChatThreadEvent(
    String spaceId,
    String spaceName,
    String threadName,
    Instant timestamp,
    List<String> participants,
    List<GoogleChatMessage> messages) {

  public GoogleChatThreadEvent {
    participants = participants == null ? List.of() : List.copyOf(participants);
    messages = messages == null ? List.of() : List.copyOf(messages);
  }

  public int messageCount() {
    return messages.size();
  }
}
