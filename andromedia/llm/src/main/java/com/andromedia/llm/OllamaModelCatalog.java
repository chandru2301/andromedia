package com.andromedia.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Lists locally installed Ollama chat models via the native {@code /api/tags} endpoint. */
public final class OllamaModelCatalog {

  private static final Logger log = LoggerFactory.getLogger(OllamaModelCatalog.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  private OllamaModelCatalog() {}

  public static List<String> listModels(String openAiCompatibleBaseUrl) {
    String tagsUrl = tagsUrl(openAiCompatibleBaseUrl);
    if (tagsUrl == null) {
      return List.of();
    }
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(tagsUrl))
              .timeout(Duration.ofSeconds(3))
              .GET()
              .header("Accept", "application/json")
              .build();
      HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        log.debug("Ollama model list failed at {} with status {}", tagsUrl, response.statusCode());
        return List.of();
      }
      return parseModelNames(response.body());
    } catch (Exception ex) {
      log.debug("Ollama model list unavailable at {}: {}", tagsUrl, ex.toString());
      return List.of();
    }
  }

  static List<String> parseModelNames(String body) throws Exception {
    JsonNode root = OBJECT_MAPPER.readTree(body);
    JsonNode models = root.path("models");
    if (!models.isArray()) {
      return List.of();
    }
    List<String> names = new ArrayList<>();
    for (JsonNode model : models) {
      String name = model.path("name").asText("").trim();
      if (name.isEmpty()) {
        name = model.path("model").asText("").trim();
      }
      if (!name.isEmpty() && !names.contains(name)) {
        names.add(name);
      }
    }
    return List.copyOf(names);
  }

  static String tagsUrl(String openAiCompatibleBaseUrl) {
    if (openAiCompatibleBaseUrl == null || openAiCompatibleBaseUrl.isBlank()) {
      return null;
    }
    String trimmed = openAiCompatibleBaseUrl.trim();
    if (trimmed.endsWith("/")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    String lower = trimmed.toLowerCase(Locale.ROOT);
    if (lower.endsWith("/v1")) {
      return trimmed.substring(0, trimmed.length() - 3) + "/api/tags";
    }
    return trimmed + "/api/tags";
  }
}
