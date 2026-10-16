package com.andromedia.core.alerts;

import com.andromedia.ingestion.slack.SlackApiClient;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class SlackMessageService {

  public List<Map<String, Object>> listChannels(String botToken) throws IOException {
    SlackApiClient client = new SlackApiClient(requireToken(botToken));
    List<Map<String, Object>> channels = new ArrayList<>();
    for (SlackApiClient.SlackChannel channel : client.listChannels()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", channel.id());
      row.put("name", channel.name());
      row.put("isMember", channel.isMember());
      channels.add(row);
    }
    return channels;
  }

  public SlackApiClient.SlackPostResult sendInvestigateMessage(InvestigateSlackMessage message)
      throws IOException {
    String text = formatInvestigateFallback(message);
    String blocks = formatInvestigateBlocks(message);
    SlackApiClient client = new SlackApiClient(requireToken(message.botToken()));
    return client.postMessage(message.channelId(), text, blocks);
  }

  public SlackApiClient.SlackPostResult sendAlertDigest(AlertDigestMessage message)
      throws IOException {
    String text = formatAlertFallback(message);
    String blocks = formatAlertBlocks(message);
    SlackApiClient client = new SlackApiClient(requireToken(message.botToken()));
    return client.postMessage(message.channelId(), text, blocks);
  }

  static String formatInvestigateFallback(InvestigateSlackMessage message) {
    StringBuilder sb = new StringBuilder();
    sb.append("*Andro investigate");
    if (message.title() != null && !message.title().isBlank()) {
      sb.append(" — ").append(escapeMrkdwn(message.title()));
    }
    sb.append("*\n");
    if (message.summary() != null && !message.summary().isBlank()) {
      sb.append(escapeMrkdwn(message.summary())).append('\n');
    }
    if (message.reasons() != null) {
      for (String reason : message.reasons()) {
        if (reason != null && !reason.isBlank()) {
          sb.append("• ").append(escapeMrkdwn(reason)).append('\n');
        }
      }
    }
    appendFields(sb, message.primaryFields());
    if (message.flags() != null && !message.flags().isEmpty()) {
      sb.append("Flags: ").append(String.join(", ", message.flags())).append('\n');
    }
    if (message.link() != null && !message.link().isBlank()) {
      sb.append('<').append(message.link()).append("|Open in Andro>");
    }
    return sb.toString().trim();
  }

  static String formatInvestigateBlocks(InvestigateSlackMessage message) {
    List<Map<String, Object>> blocks = new ArrayList<>();
    String heading =
        message.title() == null || message.title().isBlank()
            ? "Andro investigate"
            : "Andro investigate — " + message.title();
    blocks.add(
        Map.of(
            "type",
            "header",
            "text",
            Map.of("type", "plain_text", "text", truncate(heading, 150), "emoji", true)));
    if (message.summary() != null && !message.summary().isBlank()) {
      blocks.add(
          Map.of(
              "type",
              "section",
              "text",
              Map.of("type", "mrkdwn", "text", escapeMrkdwn(truncate(message.summary(), 2900)))));
    }
    if (message.reasons() != null && !message.reasons().isEmpty()) {
      StringBuilder why = new StringBuilder("*Why this answer?*\n");
      for (String reason : message.reasons()) {
        if (reason != null && !reason.isBlank()) {
          why.append("• ").append(escapeMrkdwn(reason)).append('\n');
        }
      }
      blocks.add(
          Map.of(
              "type",
              "section",
              "text",
              Map.of("type", "mrkdwn", "text", truncate(why.toString().trim(), 2900))));
    }
    String fields = formatFieldsMrkdwn(message.primaryFields());
    if (!fields.isBlank()) {
      blocks.add(
          Map.of("type", "section", "text", Map.of("type", "mrkdwn", "text", fields)));
    }
    if (message.flags() != null && !message.flags().isEmpty()) {
      blocks.add(
          Map.of(
              "type",
              "context",
              "elements",
              List.of(
                  Map.of(
                      "type",
                      "mrkdwn",
                      "text",
                      "*Flags:* " + String.join(", ", message.flags())))));
    }
    if (message.link() != null && !message.link().isBlank()) {
      blocks.add(
          Map.of(
              "type",
              "actions",
              "elements",
              List.of(
                  Map.of(
                      "type",
                      "button",
                      "text",
                      Map.of("type", "plain_text", "text", "Open in Andro"),
                      "url",
                      message.link()))));
    }
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(blocks);
    } catch (IOException ex) {
      return null;
    }
  }

  static String formatAlertFallback(AlertDigestMessage message) {
    StringBuilder sb = new StringBuilder();
    sb.append("*Andro pipeline alert");
    if (message.service() != null && !message.service().isBlank()) {
      sb.append(" — ").append(escapeMrkdwn(message.service()));
    }
    sb.append("*\n");
    sb.append(message.events().size())
        .append(" new signal")
        .append(message.events().size() == 1 ? "" : "s")
        .append(" after Zoho reindex\n");
    for (AlertEvent event : message.events()) {
      sb.append("• ")
          .append(escapeMrkdwn(event.dealName()))
          .append(" — ")
          .append(escapeMrkdwn(event.detail()))
          .append('\n');
    }
    return sb.toString().trim();
  }

  static String formatAlertBlocks(AlertDigestMessage message) {
    List<Map<String, Object>> blocks = new ArrayList<>();
    String header =
        "Andro pipeline alert"
            + (message.service() == null || message.service().isBlank()
                ? ""
                : " — " + message.service());
    blocks.add(
        Map.of(
            "type",
            "header",
            "text",
            Map.of("type", "plain_text", "text", truncate(header, 150), "emoji", true)));
    StringBuilder body = new StringBuilder();
    body.append(message.events().size())
        .append(" new signal")
        .append(message.events().size() == 1 ? "" : "s")
        .append(" after Zoho reindex\n");
    for (AlertEvent event : message.events()) {
      body.append("• *")
          .append(escapeMrkdwn(event.dealName()))
          .append("* — ")
          .append(escapeMrkdwn(event.detail()))
          .append('\n');
    }
    blocks.add(
        Map.of(
            "type",
            "section",
            "text",
            Map.of("type", "mrkdwn", "text", truncate(body.toString().trim(), 2900))));
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(blocks);
    } catch (IOException ex) {
      return null;
    }
  }

  private static void appendFields(StringBuilder sb, Map<String, String> fields) {
    if (fields == null || fields.isEmpty()) {
      return;
    }
    for (String key :
        List.of("Stage", "Amount", "Probability", "Close Date", "Next Step", "Account")) {
      String value = fields.get(key);
      if (value != null && !value.isBlank() && !"—".equals(value)) {
        sb.append(key).append(": ").append(escapeMrkdwn(value)).append('\n');
      }
    }
  }

  private static String formatFieldsMrkdwn(Map<String, String> fields) {
    if (fields == null || fields.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder("*Key fields*\n");
    boolean any = false;
    for (String key :
        List.of("Stage", "Amount", "Probability", "Close Date", "Next Step", "Account")) {
      String value = fields.get(key);
      if (value != null && !value.isBlank() && !"—".equals(value)) {
        sb.append("• *").append(key).append(":* ").append(escapeMrkdwn(value)).append('\n');
        any = true;
      }
    }
    return any ? sb.toString().trim() : "";
  }

  static String escapeMrkdwn(String value) {
    if (value == null) {
      return "";
    }
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  private static String truncate(String value, int max) {
    if (value == null) {
      return "";
    }
    if (value.length() <= max) {
      return value;
    }
    return value.substring(0, Math.max(0, max - 1)) + "…";
  }

  private static String requireToken(String botToken) {
    if (botToken == null || botToken.isBlank()) {
      throw new IllegalArgumentException("Slack bot token is required");
    }
    return botToken.trim();
  }

  public record InvestigateSlackMessage(
      String botToken,
      String channelId,
      String title,
      String summary,
      List<String> reasons,
      Map<String, String> primaryFields,
      List<String> flags,
      String link) {}

  public record AlertEvent(String dealName, String detail, String kind) {}

  public record AlertDigestMessage(
      String botToken, String channelId, String service, List<AlertEvent> events) {}
}
