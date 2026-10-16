package com.andromedia.ingestion.googlechat;

import java.time.Instant;

/** Raw Google Chat message used during ingestion before chunk assembly. */
public record GoogleChatMessage(
    String name,
    String threadName,
    String senderId,
    String senderDisplayName,
    Instant createTime,
    String text) {

  public GoogleChatMessage {
    text = text == null ? "" : text;
  }

  /** Chat threads are flat (no separate replies fetch) — a message always carries its thread. */
  public String effectiveThreadName() {
    return threadName == null || threadName.isBlank() ? name : threadName;
  }
}
