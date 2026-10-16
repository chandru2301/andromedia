package com.andromedia.common;

import java.time.Instant;
import java.util.List;

public record SlackChunk(
    String chunkId,
    String channelId,
    String channelName,
    String threadTs,
    Instant timestamp,
    List<String> participants,
    int reactionCount,
    int messageCount,
    String content) {

  public SlackChunk {
    participants = participants == null ? List.of() : List.copyOf(participants);
    content = content == null ? "" : content;
  }

  public String path() {
    String channel = channelName == null || channelName.isBlank() ? channelId : channelName;
    if (channel != null && !channel.startsWith("#")) {
      channel = "#" + channel;
    }
    return "slack://" + channel + "/" + threadTs;
  }

  public String fileName() {
    String channel = channelName == null || channelName.isBlank() ? channelId : channelName;
    if (channel != null && channel.startsWith("#")) {
      channel = channel.substring(1);
    }
    return channel == null || channel.isBlank() ? threadTs : channel;
  }
}
