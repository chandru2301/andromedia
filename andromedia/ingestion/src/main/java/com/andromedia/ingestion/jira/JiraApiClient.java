package com.andromedia.ingestion.jira;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class JiraApiClient {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final int PAGE_SIZE = 50;
  private static final List<String> SEARCH_FIELDS =
      List.of(
          "summary",
          "description",
          "comment",
          "status",
          "issuetype",
          "priority",
          "project",
          "updated",
          "created",
          "assignee",
          "reporter");

  public interface Transport {
    JsonNode get(String path) throws IOException;

    JsonNode post(String path, String jsonBody) throws IOException;
  }

  private final Transport transport;

  public JiraApiClient(String cloudId, String accessToken) {
    this(new HttpTransport(cloudId, accessToken));
  }

  public JiraApiClient(Transport transport) {
    this.transport = transport;
  }

  public JiraMyselfResult myself() throws IOException {
    JsonNode response = transport.get("/rest/api/3/myself");
    return new JiraMyselfResult(
        true,
        response.path("displayName").asText(""),
        response.path("emailAddress").asText(""),
        response.path("accountId").asText(""));
  }

  public List<JiraIssue> searchIssues(List<String> projectKeys) throws IOException {
    if (projectKeys == null || projectKeys.isEmpty()) {
      return List.of();
    }
    String jql = buildProjectJql(projectKeys);
    List<JiraIssue> issues = new ArrayList<>();
    String nextPageToken = null;
    do {
      ObjectNode body = OBJECT_MAPPER.createObjectNode();
      body.put("jql", jql);
      body.put("maxResults", PAGE_SIZE);
      ArrayNode fields = body.putArray("fields");
      for (String field : SEARCH_FIELDS) {
        fields.add(field);
      }
      if (nextPageToken != null && !nextPageToken.isBlank()) {
        body.put("nextPageToken", nextPageToken);
      }

      JsonNode response = transport.post("/rest/api/3/search/jql", OBJECT_MAPPER.writeValueAsString(body));
      JsonNode results = response.path("issues");
      if (results.isArray()) {
        for (JsonNode issueNode : results) {
          issues.add(parseIssue(issueNode));
        }
      }
      nextPageToken = blankToNull(response.path("nextPageToken").asText(null));
      if (!results.isArray() || results.isEmpty()) {
        break;
      }
    } while (nextPageToken != null);
    return issues;
  }

  static String buildProjectJql(List<String> projectKeys) {
    StringBuilder jql = new StringBuilder("project in (");
    boolean first = true;
    for (String raw : projectKeys) {
      String key = normalizeProjectKey(raw);
      if (key.isBlank()) {
        continue;
      }
      if (!first) {
        jql.append(',');
      }
      first = false;
      jql.append('"').append(key.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
    }
    jql.append(") ORDER BY updated DESC");
    return jql.toString();
  }

  static String normalizeProjectKey(String raw) {
    if (raw == null) {
      return "";
    }
    String key = raw.trim();
    while (key.length() >= 2
        && ((key.startsWith("\"") && key.endsWith("\""))
            || (key.startsWith("'") && key.endsWith("'")))) {
      key = key.substring(1, key.length() - 1).trim();
    }
    return key;
  }

  static JiraIssue parseIssue(JsonNode node) {
    JsonNode fields = node.path("fields");
    String key = node.path("key").asText("");
    String summary = fields.path("summary").asText("");
    String description = fieldToPlainText(fields.get("description"));
    String issueType = fields.path("issuetype").path("name").asText("");
    String projectKey = fields.path("project").path("key").asText("");
    String priority = fields.path("priority").path("name").asText("");
    String status = fields.path("status").path("name").asText("");
    String author =
        fields.path("assignee").path("displayName").asText(
            fields.path("reporter").path("displayName").asText(""));
    Instant updated = parseInstant(fields.path("updated").asText(null));
    Instant created = parseInstant(fields.path("created").asText(null));
    List<JiraComment> comments = parseComments(fields.path("comment").path("comments"));
    return new JiraIssue(
        key,
        summary,
        description,
        issueType,
        projectKey,
        priority,
        status,
        author,
        updated,
        created,
        comments);
  }

  static List<JiraComment> parseComments(JsonNode comments) {
    List<JiraComment> parsed = new ArrayList<>();
    if (comments == null || !comments.isArray()) {
      return parsed;
    }
    for (JsonNode comment : comments) {
      parsed.add(
          new JiraComment(
              comment.path("author").path("displayName").asText(""),
              parseInstant(comment.path("created").asText(null)),
              fieldToPlainText(comment.get("body"))));
    }
    return parsed;
  }

  /**
   * Converts Jira Cloud ADF (or plain string) fields to searchable plain text. Never returns raw
   * ADF JSON.
   */
  static String fieldToPlainText(JsonNode field) {
    if (field == null || field.isNull() || field.isMissingNode()) {
      return "";
    }
    if (field.isTextual()) {
      return field.asText("").trim();
    }
    return adfToPlainText(field);
  }

  static String adfToPlainText(JsonNode adf) {
    if (adf == null || adf.isNull() || adf.isMissingNode()) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    if (adf.has("text")) {
      sb.append(adf.get("text").asText()).append(' ');
    }
    if (adf.has("content") && adf.get("content").isArray()) {
      for (JsonNode child : adf.get("content")) {
        sb.append(adfToPlainText(child));
      }
    }
    return sb.toString().trim();
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

  public record JiraMyselfResult(boolean ok, String displayName, String email, String accountId) {}

  static final class HttpTransport implements Transport {
    private final String baseUrl;
    private final String authorization;
    private final HttpClient httpClient;

    HttpTransport(String cloudId, String accessToken) {
      if (cloudId == null || cloudId.isBlank()) {
        throw new IllegalArgumentException("Jira cloudId is required");
      }
      if (accessToken == null || accessToken.isBlank()) {
        throw new IllegalArgumentException("Jira access token is required");
      }
      this.baseUrl = "https://api.atlassian.com/ex/jira/" + cloudId.trim();
      this.authorization = "Bearer " + accessToken.trim();
      this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    }

    @Override
    public JsonNode get(String path) throws IOException {
      return send(
          HttpRequest.newBuilder(URI.create(url(path)))
              .timeout(Duration.ofSeconds(60))
              .header("Authorization", authorization)
              .header("Accept", "application/json")
              .GET()
              .build(),
          path);
    }

    @Override
    public JsonNode post(String path, String jsonBody) throws IOException {
      return send(
          HttpRequest.newBuilder(URI.create(url(path)))
              .timeout(Duration.ofSeconds(60))
              .header("Authorization", authorization)
              .header("Accept", "application/json")
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(jsonBody == null ? "{}" : jsonBody))
              .build(),
          path);
    }

    private String url(String path) {
      return baseUrl + (path.startsWith("/") ? path : "/" + path);
    }

    private JsonNode send(HttpRequest request, String path) throws IOException {
      try {
        HttpResponse<String> response =
            httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 429) {
          throw new IOException("Jira rate limited for " + path);
        }
        if (response.statusCode() >= 400) {
          throw new IOException(
              "Jira HTTP " + response.statusCode() + " for " + path + ": " + response.body());
        }
        return OBJECT_MAPPER.readTree(response.body());
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new IOException("Interrupted calling Jira API " + path, ex);
      }
    }
  }
}
