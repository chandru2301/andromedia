package com.andromedia.common;

import java.time.Instant;
import java.util.List;

public record GoogleChatChunk(
    String chunkId,
    String spaceId,
    String spaceName,
    String threadName,
    Instant timestamp,
    List<String> participants,
    int messageCount,
    String content) {

  public GoogleChatChunk {
    participants = participants == null ? List.of() : List.copyOf(participants);
    content = content == null ? "" : content;
  }

  public String path() {
    String space = spaceName == null || spaceName.isBlank() ? spaceId : spaceName;
    return "googlechat://" + space + "/" + threadName;
  }

  public String fileName() {
    return spaceName == null || spaceName.isBlank() ? spaceId : spaceName;
  }
}
