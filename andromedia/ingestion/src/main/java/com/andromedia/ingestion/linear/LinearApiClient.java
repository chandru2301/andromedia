package com.andromedia.ingestion.linear;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class LinearApiClient {

  private static final String API_URL = "https://api.linear.app/graphql";
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final int PAGE_SIZE = 50;

  public interface Transport {
    JsonNode post(String jsonBody) throws IOException;
  }

  private final Transport transport;

  public LinearApiClient(String apiKey) {
    this(new HttpTransport(apiKey));
  }

  public LinearApiClient(Transport transport) {
    this.transport = transport;
  }

  public LinearViewerResult viewer() throws IOException {
    JsonNode data = execute("{ viewer { id name } }");
    JsonNode viewer = data.path("viewer");
    return new LinearViewerResult(
        true, viewer.path("name").asText(""), viewer.path("id").asText(""));
  }

  public List<LinearTeam> listTeams() throws IOException {
    JsonNode data = execute("{ teams { nodes { id key name } } }");
    JsonNode nodes = data.path("teams").path("nodes");
    List<LinearTeam> teams = new ArrayList<>();
    if (nodes.isArray()) {
      for (JsonNode node : nodes) {
        teams.add(
            new LinearTeam(
                node.path("id").asText(""),
                node.path("key").asText(""),
                node.path("name").asText("")));
      }
    }
    return List.copyOf(teams);
  }

  /**
   * Maps configured team key or team name (e.g. {@code Softtwig}) to Linear team keys used in
   * GraphQL filters.
   */
  public List<String> resolveTeamKeys(List<String> teamIdentifiers) throws IOException {
    if (teamIdentifiers == null || teamIdentifiers.isEmpty()) {
      return List.of();
    }
    List<LinearTeam> teams = listTeams();
    Set<String> resolved = new LinkedHashSet<>();
    for (String raw : teamIdentifiers) {
      if (raw == null || raw.isBlank()) {
        continue;
      }
      String token = raw.trim();
      String matchKey = findTeamKey(teams, token);
      resolved.add(matchKey == null ? token : matchKey);
    }
    return List.copyOf(resolved);
  }

  public static String findTeamKey(List<LinearTeam> teams, String token) {
    if (teams == null || token == null || token.isBlank()) {
      return null;
    }
    String normalized = token.trim();
    for (LinearTeam team : teams) {
      if (team.key().equalsIgnoreCase(normalized) || team.name().equalsIgnoreCase(normalized)) {
        return team.key();
      }
    }
    return null;
  }

  public boolean teamExists(List<String> teamIdentifiers) throws IOException {
    if (teamIdentifiers == null || teamIdentifiers.isEmpty()) {
      return true;
    }
    List<LinearTeam> teams = listTeams();
    for (String raw : teamIdentifiers) {
      if (raw == null || raw.isBlank()) {
        continue;
      }
      if (findTeamKey(teams, raw.trim()) == null) {
        return false;
      }
    }
    return true;
  }

  public List<LinearIssue> fetchIssues(List<String> teamIdentifiers) throws IOException {
    List<String> teamKeys = resolveTeamKeys(teamIdentifiers);
    if (teamKeys.isEmpty()) {
      return fetchIssuesForTeam(null);
    }
    List<LinearIssue> issues = new ArrayList<>();
    for (String teamKey : teamKeys) {
      issues.addAll(fetchIssuesForTeam(teamKey));
    }
    return issues;
  }

  private List<LinearIssue> fetchIssuesForTeam(String teamKey) throws IOException {
    List<LinearIssue> issues = new ArrayList<>();
    String cursor = null;
    boolean hasNext = true;
    while (hasNext) {
      String filterClause =
          teamKey == null || teamKey.isBlank()
              ? ""
              : "filter: { team: { key: { eq: \"" + escape(teamKey) + "\" } } }, ";
      String afterClause =
          cursor == null || cursor.isBlank() ? "" : "after: \"" + escape(cursor) + "\", ";
      String query =
          "{ issues("
              + filterClause
              + afterClause
              + "first: "
              + PAGE_SIZE
              + ") { pageInfo { hasNextPage endCursor } nodes { id identifier title description "
              + "priority updatedAt state { name } team { key name } cycle { name } "
              + "creator { name } comments(first: 50) { nodes { body user { name } } } } } }";
      JsonNode data = execute(query);
      JsonNode connection = data.path("issues");
      JsonNode nodes = connection.path("nodes");
      if (nodes.isArray()) {
        for (JsonNode node : nodes) {
          issues.add(parseIssue(node));
        }
      }
      hasNext = connection.path("pageInfo").path("hasNextPage").asBoolean(false);
      cursor = connection.path("pageInfo").path("endCursor").asText(null);
      if (!hasNext || cursor == null || cursor.isBlank()) {
        break;
      }
    }
    return issues;
  }

  static LinearIssue parseIssue(JsonNode node) {
    List<LinearComment> comments = new ArrayList<>();
    JsonNode commentNodes = node.path("comments").path("nodes");
    if (commentNodes.isArray()) {
      for (JsonNode comment : commentNodes) {
        comments.add(
            new LinearComment(
                comment.path("user").path("name").asText(""),
                comment.path("body").asText("")));
      }
    }
    return new LinearIssue(
        node.path("id").asText(""),
        node.path("identifier").asText(""),
        node.path("title").asText(""),
        node.path("description").asText(""),
        node.path("state").path("name").asText(""),
        node.path("team").path("key").asText(""),
        node.path("team").path("name").asText(""),
        node.path("cycle").path("name").asText(""),
        node.path("priority").asInt(0),
        node.path("creator").path("name").asText(""),
        parseInstant(node.path("updatedAt").asText(null)),
        comments);
  }

  private JsonNode execute(String query) throws IOException {
    ObjectNode body = OBJECT_MAPPER.createObjectNode();
    body.put("query", query);
    JsonNode response = transport.post(OBJECT_MAPPER.writeValueAsString(body));
    if (response.has("errors") && response.path("errors").isArray() && !response.path("errors").isEmpty()) {
      throw new IOException("Linear GraphQL error: " + response.path("errors"));
    }
    return response.path("data");
  }

  private static String escape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
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

  public record LinearViewerResult(boolean ok, String name, String id) {}

  public record LinearTeam(String id, String key, String name) {
    public LinearTeam {
      id = id == null ? "" : id;
      key = key == null ? "" : key;
      name = name == null ? "" : name;
    }
  }

  static final class HttpTransport implements Transport {
    private final String apiKey;
    private final HttpClient httpClient;

    HttpTransport(String apiKey) {
      if (apiKey == null || apiKey.isBlank()) {
        throw new IllegalArgumentException("Linear API key is required");
      }
      this.apiKey = apiKey.trim();
      this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    }

    @Override
    public JsonNode post(String jsonBody) throws IOException {
      try {
        HttpResponse<String> response =
            httpClient.send(
                HttpRequest.newBuilder(URI.create(API_URL))
                    .timeout(Duration.ofSeconds(60))
                    // Linear OAuth access tokens (this client is OAuth-only now) require the
                    // "Bearer " scheme, unlike the old personal-API-key header format.
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody == null ? "{}" : jsonBody))
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 429) {
          throw new IOException("Linear rate limited");
        }
        if (response.statusCode() >= 400) {
          throw new IOException(
              "Linear HTTP " + response.statusCode() + ": " + response.body());
        }
        return OBJECT_MAPPER.readTree(response.body());
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new IOException("Interrupted calling Linear API", ex);
      }
    }
  }
}
