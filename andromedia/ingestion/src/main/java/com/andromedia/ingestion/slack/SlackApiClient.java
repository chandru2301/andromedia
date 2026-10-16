package com.andromedia.ingestion.slack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SlackApiClient {

  private static final String API_BASE = "https://slack.com/api/";
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  public interface Transport {
    JsonNode get(String method, Map<String, String> queryParams) throws IOException;

    default JsonNode postJson(String method, Map<String, Object> body) throws IOException {
      throw new UnsupportedOperationException("POST not supported by this transport");
    }
  }

  private final Transport transport;
  private final Map<String, String> userNameCache = new ConcurrentHashMap<>();

  public SlackApiClient(String botToken) {
    this(new HttpTransport(botToken));
  }

  public SlackApiClient(Transport transport) {
    this.transport = transport;
  }

  public SlackAuthTestResult authTest() throws IOException {
    JsonNode response = transport.get("auth.test", Map.of());
    ensureOk(response, "auth.test");
    return new SlackAuthTestResult(
        response.path("ok").asBoolean(false),
        response.path("team").asText(""),
        response.path("team_id").asText(""),
        response.path("user").asText(""),
        response.path("user_id").asText(""),
        response.path("bot_id").asText(""));
  }

  public List<SlackChannel> listChannels() throws IOException {
    List<SlackChannel> channels = new ArrayList<>();
    String cursor = null;
    do {
      Map<String, String> params = new LinkedHashMap<>();
      params.put("types", "public_channel,private_channel");
      params.put("exclude_archived", "true");
      params.put("limit", "200");
      if (cursor != null && !cursor.isBlank()) {
        params.put("cursor", cursor);
      }
      JsonNode response = transport.get("conversations.list", params);
      ensureOk(response, "conversations.list");
      JsonNode list = response.path("channels");
      if (list.isArray()) {
        for (JsonNode channel : list) {
          channels.add(
              new SlackChannel(
                  channel.path("id").asText(),
                  channel.path("name").asText(),
                  channel.path("is_member").asBoolean(false)));
        }
      }
      cursor = response.path("response_metadata").path("next_cursor").asText(null);
    } while (cursor != null && !cursor.isBlank());
    return channels;
  }

  public List<SlackMessage> fetchHistory(
      String channelId, Instant oldest, Instant latest, int maxMessages) throws IOException {
    List<SlackMessage> messages = new ArrayList<>();
    String cursor = null;
    do {
      Map<String, String> params = new LinkedHashMap<>();
      params.put("channel", channelId);
      params.put("limit", "200");
      if (oldest != null) {
        params.put("oldest", toSlackTs(oldest));
      }
      if (latest != null) {
        params.put("latest", toSlackTs(latest));
      }
      if (cursor != null && !cursor.isBlank()) {
        params.put("cursor", cursor);
      }
      JsonNode response = transport.get("conversations.history", params);
      ensureOk(response, "conversations.history");
      JsonNode list = response.path("messages");
      if (list.isArray()) {
        for (JsonNode node : list) {
          messages.add(parseMessage(node));
          if (messages.size() >= maxMessages) {
            return messages;
          }
        }
      }
      cursor = response.path("response_metadata").path("next_cursor").asText(null);
    } while (cursor != null && !cursor.isBlank());
    return messages;
  }

  public List<SlackMessage> fetchReplies(String channelId, String threadTs) throws IOException {
    List<SlackMessage> messages = new ArrayList<>();
    String cursor = null;
    do {
      Map<String, String> params = new LinkedHashMap<>();
      params.put("channel", channelId);
      params.put("ts", threadTs);
      params.put("limit", "200");
      if (cursor != null && !cursor.isBlank()) {
        params.put("cursor", cursor);
      }
      JsonNode response = transport.get("conversations.replies", params);
      ensureOk(response, "conversations.replies");
      JsonNode list = response.path("messages");
      if (list.isArray()) {
        for (JsonNode node : list) {
          messages.add(parseMessage(node));
        }
      }
      cursor = response.path("response_metadata").path("next_cursor").asText(null);
    } while (cursor != null && !cursor.isBlank());
    return messages;
  }

  public String resolveUserName(String userId) throws IOException {
    if (userId == null || userId.isBlank()) {
      return "unknown";
    }
    String cached = userNameCache.get(userId);
    if (cached != null) {
      return cached;
    }
    JsonNode response = transport.get("users.info", Map.of("user", userId));
    ensureOk(response, "users.info");
    String name = extractDisplayName(response.path("user"));
    if (name == null || name.isBlank()) {
      name = userId;
    }
    userNameCache.put(userId, name);
    return name;
  }

  /**
   * Resolves and caches display names for all unique user IDs. Failures are recorded as the raw
   * user ID so chunk building never blocks, but callers should check logs for {@code missing_scope}.
   */
  public Map<String, String> resolveUserNames(Iterable<String> userIds) {
    Map<String, String> resolved = new LinkedHashMap<>();
    if (userIds == null) {
      return resolved;
    }
    for (String userId : userIds) {
      if (userId == null || userId.isBlank() || resolved.containsKey(userId)) {
        continue;
      }
      try {
        resolved.put(userId, resolveUserName(userId));
      } catch (IOException ex) {
        // Cache the fallback so we do not hammer Slack for the same missing scope / user.
        userNameCache.putIfAbsent(userId, userId);
        resolved.put(userId, userId);
      }
    }
    return resolved;
  }

  static String extractDisplayName(JsonNode user) {
    if (user == null || user.isMissingNode() || user.isNull()) {
      return null;
    }
    String displayName = textOrNull(user.path("profile").path("display_name"));
    if (displayName != null) {
      return displayName;
    }
    String realName = textOrNull(user.path("real_name"));
    if (realName != null) {
      return realName;
    }
    String profileRealName = textOrNull(user.path("profile").path("real_name"));
    if (profileRealName != null) {
      return profileRealName;
    }
    return textOrNull(user.path("name"));
  }

  private static String textOrNull(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return null;
    }
    String value = node.asText();
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }

  public Map<String, String> userNameCacheView() {
    return Map.copyOf(userNameCache);
  }

  public void putUserName(String userId, String displayName) {
    if (userId != null && !userId.isBlank() && displayName != null && !displayName.isBlank()) {
      userNameCache.put(userId, displayName);
    }
  }

  /**
   * Posts a message via {@code chat.postMessage}. Requires {@code chat:write} and that the bot is
   * a member of the target channel.
   *
   * @param blocksJson optional Slack Block Kit JSON array string; null to send text only
   */
  public SlackPostResult postMessage(String channelId, String text, String blocksJson)
      throws IOException {
    if (channelId == null || channelId.isBlank()) {
      throw new IllegalArgumentException("channelId is required");
    }
    if ((text == null || text.isBlank()) && (blocksJson == null || blocksJson.isBlank())) {
      throw new IllegalArgumentException("text or blocks are required");
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("channel", channelId.trim());
    if (text != null && !text.isBlank()) {
      body.put("text", text);
    }
    if (blocksJson != null && !blocksJson.isBlank()) {
      body.put("blocks", OBJECT_MAPPER.readTree(blocksJson));
    }
    JsonNode response = transport.postJson("chat.postMessage", body);
    ensureOk(response, "chat.postMessage");
    return new SlackPostResult(
        response.path("ok").asBoolean(false),
        response.path("channel").asText(""),
        response.path("ts").asText(""));
  }

  static SlackMessage parseMessage(JsonNode node) {
    String ts = node.path("ts").asText(null);
    String threadTs = node.path("thread_ts").asText(null);
    String userId = node.path("user").asText(null);
    if (userId == null || userId.isBlank()) {
      userId = node.path("bot_id").asText(null);
    }
    String text = node.path("text").asText("");
    String subtype = node.hasNonNull("subtype") ? node.path("subtype").asText() : null;

    Map<String, Integer> reactions = new LinkedHashMap<>();
    int reactionCount = 0;
    JsonNode reactionNodes = node.path("reactions");
    if (reactionNodes.isArray()) {
      for (JsonNode reaction : reactionNodes) {
        String name = reaction.path("name").asText();
        int count = reaction.path("count").asInt(0);
        if (name != null && !name.isBlank() && count > 0) {
          reactions.put(name, count);
          reactionCount += count;
        }
      }
    }
    int replyCount = node.path("reply_count").asInt(0);
    return new SlackMessage(ts, threadTs, userId, text, subtype, reactionCount, replyCount, reactions);
  }

  private static void ensureOk(JsonNode response, String method) throws IOException {
    if (response == null || !response.path("ok").asBoolean(false)) {
      String error = response == null ? "null response" : response.path("error").asText("unknown");
      throw new IOException("Slack API " + method + " failed: " + error);
    }
  }

  static String toSlackTs(Instant instant) {
    long seconds = instant.getEpochSecond();
    int nanos = instant.getNano();
    if (nanos == 0) {
      return Long.toString(seconds);
    }
    return seconds + "." + String.format("%06d", nanos / 1_000);
  }

  public record SlackChannel(String id, String name, boolean isMember) {}

  public record SlackAuthTestResult(
      boolean ok, String team, String teamId, String user, String userId, String botId) {}

  public record SlackPostResult(boolean ok, String channel, String ts) {}

  static final class HttpTransport implements Transport {
    private final String botToken;
    private final HttpClient httpClient;

    HttpTransport(String botToken) {
      if (botToken == null || botToken.isBlank()) {
        throw new IllegalArgumentException("Slack bot token is required");
      }
      this.botToken = botToken;
      this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    }

    @Override
    public JsonNode get(String method, Map<String, String> queryParams) throws IOException {
      StringBuilder url = new StringBuilder(API_BASE).append(method).append('?');
      boolean first = true;
      for (Map.Entry<String, String> entry : queryParams.entrySet()) {
        if (!first) {
          url.append('&');
        }
        first = false;
        url.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
            .append('=')
            .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
      }
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(url.toString()))
              .timeout(Duration.ofSeconds(60))
              .header("Authorization", "Bearer " + botToken)
              .GET()
              .build();
      return send(request, method);
    }

    @Override
    public JsonNode postJson(String method, Map<String, Object> body) throws IOException {
      String payload = OBJECT_MAPPER.writeValueAsString(body == null ? Map.of() : body);
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(API_BASE + method))
              .timeout(Duration.ofSeconds(60))
              .header("Authorization", "Bearer " + botToken)
              .header("Content-Type", "application/json; charset=utf-8")
              .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
              .build();
      return send(request, method);
    }

    private JsonNode send(HttpRequest request, String method) throws IOException {
      try {
        HttpResponse<String> response =
            httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 400) {
          throw new IOException(
              "Slack HTTP " + response.statusCode() + " for " + method + ": " + response.body());
        }
        return OBJECT_MAPPER.readTree(response.body());
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new IOException("Interrupted calling Slack API " + method, ex);
      }
    }
  }
}
