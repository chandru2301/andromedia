package com.andromedia.ingestion.notion;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class NotionApiClient {

  private static final String API_BASE = "https://api.notion.com/v1/";
  private static final String NOTION_VERSION = "2022-06-28";
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final int MAX_RECURSION_DEPTH = 32;

  public interface Transport {
    JsonNode get(String path) throws IOException;

    JsonNode post(String path, String jsonBody) throws IOException;
  }

  private final Transport transport;

  public NotionApiClient(String apiKey) {
    this(new HttpTransport(apiKey));
  }

  public NotionApiClient(Transport transport) {
    this.transport = transport;
  }

  public NotionMeResult usersMe() throws IOException {
    JsonNode response = transport.get("users/me");
    JsonNode bot = response.path("bot");
    String workspace =
        bot.path("workspace_name").asText(response.path("name").asText(""));
    String user = response.path("name").asText("");
    return new NotionMeResult(true, workspace, user, response.path("id").asText(""));
  }

  public NotionPage retrievePage(String pageId) throws IOException {
    JsonNode response = transport.get("pages/" + pageId);
    return parsePage(response);
  }

  public NotionBlocksResponse getBlockChildren(String blockId, String startCursor)
      throws IOException {
    StringBuilder path =
        new StringBuilder("blocks/")
            .append(pageEncode(blockId))
            .append("/children?page_size=100");
    if (startCursor != null && !startCursor.isBlank()) {
      path.append("&start_cursor=").append(URLEncoder.encode(startCursor, StandardCharsets.UTF_8));
    }
    JsonNode response = transport.get(path.toString());
    List<NotionBlock> results = NotionBlock.listFromResults(response.path("results"));
    String next =
        response.path("has_more").asBoolean(false)
            ? blankToNull(response.path("next_cursor").asText(null))
            : null;
    return new NotionBlocksResponse(results, next);
  }

  /**
   * Recursively fetches all blocks under {@code blockId}, inlining children. Does not descend into
   * {@code child_page} / {@code child_database} bodies (those are separate pages).
   */
  public List<NotionBlock> fetchAllBlocks(String blockId) throws IOException {
    return fetchAllBlocks(blockId, 0, new LinkedHashSet<>());
  }

  private List<NotionBlock> fetchAllBlocks(String blockId, int depth, Set<String> visited)
      throws IOException {
    List<NotionBlock> all = new ArrayList<>();
    if (blockId == null || blockId.isBlank() || depth > MAX_RECURSION_DEPTH) {
      return all;
    }
    if (!visited.add(blockId)) {
      return all;
    }

    String cursor = null;
    do {
      NotionBlocksResponse response = getBlockChildren(blockId, cursor);
      for (NotionBlock block : response.results()) {
        all.add(block);
        if (block.hasChildren() && !block.isChildPageOrDatabase()) {
          all.addAll(fetchAllBlocks(block.id(), depth + 1, visited));
        }
      }
      cursor = response.nextCursor();
    } while (cursor != null && !cursor.isBlank());
    return all;
  }

  public List<String> queryDatabasePageIds(String databaseId) throws IOException {
    List<String> pageIds = new ArrayList<>();
    String cursor = null;
    do {
      String body =
          cursor == null || cursor.isBlank()
              ? "{\"page_size\":100}"
              : "{\"page_size\":100,\"start_cursor\":\"" + cursor + "\"}";
      JsonNode response = transport.post("databases/" + pageEncode(databaseId) + "/query", body);
      JsonNode results = response.path("results");
      if (results.isArray()) {
        for (JsonNode page : results) {
          String id = page.path("id").asText(null);
          if (id != null && !id.isBlank()) {
            pageIds.add(id);
          }
        }
      }
      cursor =
          response.path("has_more").asBoolean(false)
              ? blankToNull(response.path("next_cursor").asText(null))
              : null;
    } while (cursor != null);
    return pageIds;
  }

  static NotionPage parsePage(JsonNode node) {
    String id = node.path("id").asText("");
    boolean archived = node.path("archived").asBoolean(false);
    Instant lastEdited = parseInstant(node.path("last_edited_time").asText(null));
    String author =
        node.path("last_edited_by").path("name").asText(
            node.path("created_by").path("name").asText(""));
    String title = extractPageTitle(node.path("properties"));
    if (title.isBlank()) {
      title = "Untitled";
    }
    return new NotionPage(id, title, archived, lastEdited, author);
  }

  static String extractPageTitle(JsonNode properties) {
    if (properties == null || !properties.isObject()) {
      return "";
    }
    for (JsonNode property : properties) {
      if (!"title".equals(property.path("type").asText())) {
        continue;
      }
      JsonNode titleArray = property.path("title");
      if (!titleArray.isArray()) {
        continue;
      }
      StringBuilder builder = new StringBuilder();
      for (JsonNode item : titleArray) {
        builder.append(item.path("plain_text").asText(""));
      }
      String title = builder.toString().trim();
      if (!title.isBlank()) {
        return title;
      }
    }
    return "";
  }

  private static Instant parseInstant(String value) {
    if (value == null || value.isBlank()) {
      return Instant.EPOCH;
    }
    try {
      return Instant.parse(value);
    } catch (Exception ex) {
      return Instant.EPOCH;
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static String pageEncode(String id) {
    return URLEncoder.encode(id, StandardCharsets.UTF_8);
  }

  public record NotionMeResult(boolean ok, String workspace, String user, String userId) {}

  public record NotionBlocksResponse(List<NotionBlock> results, String nextCursor) {
    public NotionBlocksResponse {
      results = results == null ? List.of() : List.copyOf(results);
    }
  }

  static final class HttpTransport implements Transport {
    private final String apiKey;
    private final HttpClient httpClient;

    HttpTransport(String apiKey) {
      if (apiKey == null || apiKey.isBlank()) {
        throw new IllegalArgumentException("Notion API key is required");
      }
      this.apiKey = apiKey;
      this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    }

    @Override
    public JsonNode get(String path) throws IOException {
      return send(
          HttpRequest.newBuilder(URI.create(API_BASE + path))
              .timeout(Duration.ofSeconds(60))
              .header("Authorization", "Bearer " + apiKey)
              .header("Notion-Version", NOTION_VERSION)
              .GET()
              .build(),
          path);
    }

    @Override
    public JsonNode post(String path, String jsonBody) throws IOException {
      return send(
          HttpRequest.newBuilder(URI.create(API_BASE + path))
              .timeout(Duration.ofSeconds(60))
              .header("Authorization", "Bearer " + apiKey)
              .header("Notion-Version", NOTION_VERSION)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(jsonBody == null ? "{}" : jsonBody))
              .build(),
          path);
    }

    private JsonNode send(HttpRequest request, String path) throws IOException {
      try {
        HttpResponse<String> response =
            httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 429) {
          String retryAfter = response.headers().firstValue("Retry-After").orElse("1");
          throw new IOException("Notion rate limited (Retry-After: " + retryAfter + ") for " + path);
        }
        if (response.statusCode() >= 400) {
          throw new IOException(
              "Notion HTTP " + response.statusCode() + " for " + path + ": " + response.body());
        }
        return OBJECT_MAPPER.readTree(response.body());
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new IOException("Interrupted calling Notion API " + path, ex);
      }
    }
  }
}
