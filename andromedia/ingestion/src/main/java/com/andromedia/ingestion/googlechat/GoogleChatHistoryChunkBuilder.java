package com.andromedia.ingestion.googlechat;

import com.andromedia.common.ChunkIdGenerator;
import com.andromedia.common.GoogleChatChunk;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public final class GoogleChatHistoryChunkBuilder {

  private static final int MAX_EMBEDDING_CHARS = 30_000;
  private static final int MAX_MESSAGES_IN_CHUNK = 40;
  private static final int HEAD_MESSAGES = 15;
  private static final int TAIL_MESSAGES = 15;

  private static final DateTimeFormatter DATE_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

  private GoogleChatHistoryChunkBuilder() {}

  public static GoogleChatChunk build(GoogleChatThreadEvent event) {
    String content = assembleContent(event);
    return new GoogleChatChunk(
        ChunkIdGenerator.generateGoogleChatThreadId(event.spaceId(), event.threadName()),
        event.spaceId(),
        event.spaceName(),
        event.threadName(),
        event.timestamp(),
        event.participants(),
        event.messageCount(),
        content);
  }

  static String assembleContent(GoogleChatThreadEvent event) {
    String spaceLabel =
        event.spaceName() == null || event.spaceName().isBlank() ? "(unnamed space)" : event.spaceName();
    String date = DATE_FORMAT.format(event.timestamp());
    String participants =
        event.participants().isEmpty()
            ? "(unknown)"
            : String.join(", ", event.participants());

    StringBuilder builder = new StringBuilder();
    builder
        .append("[GOOGLE CHAT] ")
        .append(spaceLabel)
        .append(" · ")
        .append(date)
        .append(" · ")
        .append(participants)
        .append('\n');
    builder.append("thread: ").append(event.messageCount()).append(" messages\n\n");

    for (GoogleChatMessage message : selectMessages(event.messages())) {
      String author =
          message.senderDisplayName() == null || message.senderDisplayName().isBlank()
              ? (message.senderId() == null ? "unknown" : message.senderId())
              : message.senderDisplayName();
      builder.append(author).append(": ").append(message.text().trim()).append("\n\n");
    }

    return truncate(builder.toString().trim(), MAX_EMBEDDING_CHARS);
  }

  private static List<GoogleChatMessage> selectMessages(List<GoogleChatMessage> messages) {
    if (messages.size() <= MAX_MESSAGES_IN_CHUNK) {
      return messages;
    }
    List<GoogleChatMessage> selected = new ArrayList<>();
    selected.addAll(messages.subList(0, HEAD_MESSAGES));
    selected.addAll(messages.subList(messages.size() - TAIL_MESSAGES, messages.size()));
    return selected;
  }

  private static String truncate(String value, int maxChars) {
    if (value.length() <= maxChars) {
      return value;
    }
    if (maxChars <= 1) {
      return value.substring(0, maxChars);
    }
    return value.substring(0, maxChars - 1) + "…";
  }
}
