package com.andromedia.ingestion.slack;

import java.time.Instant;
import java.util.List;

/** One Slack conversation thread (parent + replies) ready for chunk building. */
public record SlackThreadEvent(
    String channelId,
    String channelName,
    String threadTs,
    Instant timestamp,
    List<String> participants,
    int reactionCount,
    List<SlackMessage> messages) {

  public SlackThreadEvent {
    participants = participants == null ? List.of() : List.copyOf(participants);
    messages = messages == null ? List.of() : List.copyOf(messages);
  }

  public int messageCount() {
    return messages.size();
  }
}
