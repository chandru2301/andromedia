package com.andromedia.ingestion.googlechat;

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
import java.util.List;

/** Thin client over the Google Chat REST API (https://chat.googleapis.com/v1). */
public class GoogleChatApiClient {

  private static final String API_BASE = "https://chat.googleapis.com/v1/";
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  public interface Transport {
    JsonNode get(String path, java.util.Map<String, String> queryParams) throws IOException;
  }

  private final Transport transport;

  public GoogleChatApiClient(String accessToken) {
    this(new HttpTransport(accessToken));
  }

  public GoogleChatApiClient(Transport transport) {
    this.transport = transport;
  }

  public List<GoogleChatSpace> listSpaces() throws IOException {
    List<GoogleChatSpace> spaces = new ArrayList<>();
    String pageToken = null;
    do {
      java.util.Map<String, String> params = new java.util.LinkedHashMap<>();
      params.put("pageSize", "100");
      if (pageToken != null && !pageToken.isBlank()) {
        params.put("pageToken", pageToken);
      }
      JsonNode response = transport.get("spaces", params);
      JsonNode list = response.path("spaces");
      if (list.isArray()) {
        for (JsonNode space : list) {
          spaces.add(
              new GoogleChatSpace(
                  space.path("name").asText(),
                  space.path("displayName").asText(space.path("name").asText())));
        }
      }
      pageToken = response.path("nextPageToken").asText(null);
    } while (pageToken != null && !pageToken.isBlank());
    return spaces;
  }

  /** {@code spaceName} is the resource name, e.g. {@code spaces/AAAAAAAAAAA}. */
  public List<GoogleChatMessage> fetchMessages(String spaceName, Instant oldest, int maxMessages)
      throws IOException {
    List<GoogleChatMessage> messages = new ArrayList<>();
    String pageToken = null;
    do {
      java.util.Map<String, String> params = new java.util.LinkedHashMap<>();
      params.put("pageSize", "100");
      params.put("orderBy", "createTime desc");
      if (oldest != null) {
        params.put("filter", "createTime > \"" + oldest.toString() + "\"");
      }
      if (pageToken != null && !pageToken.isBlank()) {
        params.put("pageToken", pageToken);
      }
      JsonNode response = transport.get(spaceName + "/messages", params);
      JsonNode list = response.path("messages");
      if (list.isArray()) {
        for (JsonNode message : list) {
          messages.add(
              new GoogleChatMessage(
                  message.path("name").asText(),
                  message.path("thread").path("name").asText(null),
                  message.path("sender").path("name").asText(null),
                  message.path("sender").path("displayName").asText(null),
                  parseCreateTime(message.path("createTime").asText(null)),
                  message.path("text").asText("")));
        }
      }
      pageToken = response.path("nextPageToken").asText(null);
    } while (pageToken != null && !pageToken.isBlank() && messages.size() < maxMessages);
    return messages.size() > maxMessages ? messages.subList(0, maxMessages) : messages;
  }

  private static Instant parseCreateTime(String value) {
    if (value == null || value.isBlank()) {
      return Instant.EPOCH;
    }
    try {
      return Instant.parse(value);
    } catch (Exception ex) {
      return Instant.EPOCH;
    }
  }

  public record GoogleChatSpace(String name, String displayName) {}

  static final class HttpTransport implements Transport {
    private final String accessToken;
    private final HttpClient httpClient;

    HttpTransport(String accessToken) {
      if (accessToken == null || accessToken.isBlank()) {
        throw new IllegalArgumentException("Google Chat access token is required");
      }
      this.accessToken = accessToken;
      this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    }

    @Override
    public JsonNode get(String path, java.util.Map<String, String> queryParams) throws IOException {
      StringBuilder url = new StringBuilder(API_BASE).append(path).append('?');
      boolean first = true;
      for (var entry : queryParams.entrySet()) {
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
              .header("Authorization", "Bearer " + accessToken)
              .GET()
              .build();
      try {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
          throw new IOException(
              "Google Chat API " + path + " failed: HTTP " + response.statusCode() + " " + response.body());
        }
        return OBJECT_MAPPER.readTree(response.body());
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new IOException("Google Chat API call interrupted", ex);
      }
    }
  }
}
