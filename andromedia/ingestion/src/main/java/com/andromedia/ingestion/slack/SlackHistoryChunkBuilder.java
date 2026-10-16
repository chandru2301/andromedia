package com.andromedia.ingestion.slack;

import com.andromedia.common.ChunkIdGenerator;
import com.andromedia.common.SlackChunk;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class SlackHistoryChunkBuilder {

  private static final int MAX_EMBEDDING_CHARS = 30_000;
  private static final int MAX_MESSAGES_IN_CHUNK = 40;
  private static final int HEAD_MESSAGES = 15;
  private static final int TAIL_MESSAGES = 15;

  private static final DateTimeFormatter DATE_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

  private SlackHistoryChunkBuilder() {}

  public static SlackChunk build(SlackThreadEvent event, Map<String, String> userNames) {
    Map<String, String> names = userNames == null ? Map.of() : userNames;
    String content = assembleContent(event, names);
    return new SlackChunk(
        ChunkIdGenerator.generateSlackThreadId(event.channelId(), event.threadTs()),
        event.channelId(),
        event.channelName(),
        event.threadTs(),
        event.timestamp(),
        event.participants(),
        event.reactionCount(),
        event.messageCount(),
        content);
  }

  static String assembleContent(SlackThreadEvent event, Map<String, String> userNames) {
    String channelLabel = formatChannel(event.channelName());
    String date = DATE_FORMAT.format(event.timestamp());
    String participants =
        event.participants().isEmpty()
            ? "(unknown)"
            : event.participants().stream().collect(Collectors.joining(", "));

    StringBuilder builder = new StringBuilder();
    builder
        .append("[SLACK] ")
        .append(channelLabel)
        .append(" · ")
        .append(date)
        .append(" · ")
        .append(participants)
        .append('\n');
    builder
        .append("thread: ")
        .append(event.messageCount())
        .append(" messages · reactions: ")
        .append(event.reactionCount())
        .append("\n\n");

    List<SlackMessage> messages = selectMessages(event.messages());
    for (SlackMessage message : messages) {
      String author = resolveAuthor(message.userId(), userNames);
      builder.append(author).append(": ").append(message.text().trim()).append("\n\n");
    }

    String reactionSummary = formatReactions(event.messages());
    if (!reactionSummary.isBlank()) {
      builder.append("[reactions: ").append(reactionSummary).append("]\n");
    }

    return truncate(builder.toString().trim(), MAX_EMBEDDING_CHARS);
  }

  private static List<SlackMessage> selectMessages(List<SlackMessage> messages) {
    if (messages.size() <= MAX_MESSAGES_IN_CHUNK) {
      return messages;
    }
    List<SlackMessage> selected = new ArrayList<>();
    selected.addAll(messages.subList(0, HEAD_MESSAGES));
    selected.addAll(messages.subList(messages.size() - TAIL_MESSAGES, messages.size()));
    return selected;
  }

  private static String formatReactions(List<SlackMessage> messages) {
    Map<String, Integer> totals = new LinkedHashMap<>();
    for (SlackMessage message : messages) {
      message.reactions().forEach((name, count) -> totals.merge(name, count, Integer::sum));
    }
    if (totals.isEmpty()) {
      return "";
    }
    return totals.entrySet().stream()
        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
        .limit(8)
        .map(entry -> entry.getKey() + "×" + entry.getValue())
        .collect(Collectors.joining(" "));
  }

  private static String resolveAuthor(String userId, Map<String, String> userNames) {
    if (userId == null || userId.isBlank()) {
      return "unknown";
    }
    String name = userNames.get(userId);
    return name == null || name.isBlank() ? userId : name;
  }

  private static String formatChannel(String channelName) {
    if (channelName == null || channelName.isBlank()) {
      return "#unknown";
    }
    return channelName.startsWith("#") ? channelName : "#" + channelName;
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
