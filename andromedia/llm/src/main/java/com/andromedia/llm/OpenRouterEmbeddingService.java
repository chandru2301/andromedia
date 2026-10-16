package com.andromedia.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

public class OpenRouterEmbeddingService implements EmbeddingService {

  private static final int MAX_INPUT_CHARS = 30_000;

  private final EmbeddingProperties properties;
  private final RestClient restClient;

  public OpenRouterEmbeddingService(EmbeddingProperties properties) {
    this(properties, RestClient.builder().baseUrl(normalizeBaseUrl(properties.baseUrl())).build());
  }

  OpenRouterEmbeddingService(EmbeddingProperties properties, RestClient restClient) {
    this.properties = properties;
    this.restClient = restClient;
    if (!properties.hasApiKey()) {
      throw new EmbeddingException(
          "OpenRouter API key is required. Set OPENROUTER_API_KEY or andromedia.embedding.api-key.");
    }
    if (properties.dimensions() <= 0) {
      throw new EmbeddingException("Embedding dimensions must be positive.");
    }
  }

  @Override
  public float[] embed(String text) {
    return embedAll(List.of(text)).getFirst();
  }

  @Override
  public List<float[]> embedAll(List<String> texts) {
    if (texts == null || texts.isEmpty()) {
      throw new EmbeddingException("Cannot embed empty texts list");
    }
    List<String> prepared = texts.stream().map(OpenRouterEmbeddingService::prepareInput).toList();
    int batchSize = Math.max(1, properties.batchSize());
    int maxCharsPerBatch = Math.max(1, properties.maxCharsPerBatch());

    List<float[]> all = new ArrayList<>(prepared.size());
    int index = 0;
    while (index < prepared.size()) {
      int end = Math.min(prepared.size(), index + batchSize);
      List<String> candidate = prepared.subList(index, end);

      int charSum = 0;
      int cappedEnd = index;
      while (cappedEnd < end) {
        String value = prepared.get(cappedEnd);
        int next = charSum + value.length();
        if (cappedEnd > index && next > maxCharsPerBatch) {
          break;
        }
        charSum = next;
        cappedEnd++;
      }
      if (cappedEnd == index) {
        cappedEnd = index + 1;
      }

      List<String> batch = prepared.subList(index, cappedEnd);
      all.addAll(embedBatchWithBisection(batch));
      index = cappedEnd;
    }
    return all;
  }

  @Override
  public int dimensions() {
    return properties.dimensions();
  }

  private void applyHeaders(HttpHeaders headers) {
    headers.setBearerAuth(properties.apiKey());
    if (!properties.httpReferer().isBlank()) {
      headers.set("HTTP-Referer", properties.httpReferer());
    }
    if (!properties.appTitle().isBlank()) {
      headers.set("X-Title", properties.appTitle());
    }
  }

  private static String prepareInput(String text) {
    if (text == null || text.isBlank()) {
      throw new EmbeddingException("Cannot embed empty text");
    }
    if (text.length() <= MAX_INPUT_CHARS) {
      return text;
    }
    return text.substring(0, MAX_INPUT_CHARS);
  }

  private List<float[]> parseEmbeddings(EmbeddingsResponse response, int expectedCount) {
    if (response == null || response.data() == null || response.data().isEmpty()) {
      throw new EmbeddingException("OpenRouter returned no embedding data");
    }
    if (response.data().size() != expectedCount) {
      throw new EmbeddingException(
          "OpenRouter returned "
              + response.data().size()
              + " embeddings for "
              + expectedCount
              + " inputs");
    }

    boolean hasAnyIndex = response.data().stream().anyMatch(row -> row.index() != null);
    boolean hasAllIndexes = response.data().stream().allMatch(row -> row.index() != null);

    List<EmbeddingData> rows;
    if (hasAnyIndex && hasAllIndexes) {
      rows = response.data().stream().sorted(Comparator.comparingInt(EmbeddingData::index)).toList();
    } else {
      rows = response.data();
    }

    List<float[]> vectors = new ArrayList<>(rows.size());
    for (EmbeddingData row : rows) {
      List<Double> values = row.embedding();
      if (values == null || values.isEmpty()) {
        throw new EmbeddingException("OpenRouter returned an empty embedding vector");
      }
      if (values.size() != properties.dimensions()) {
        throw new EmbeddingException(
            "Expected embedding dimension "
                + properties.dimensions()
                + " but received "
                + values.size());
      }
      float[] vector = new float[values.size()];
      for (int i = 0; i < values.size(); i++) {
        vector[i] = values.get(i).floatValue();
      }
      vectors.add(vector);
    }
    return vectors;
  }

  private List<float[]> embedBatchWithBisection(List<String> inputs) {
    try {
      return embedBatch(inputs);
    } catch (RestClientException ex) {
      if (inputs.size() <= 1) {
        throw new EmbeddingException("OpenRouter embedding request failed", ex);
      }
      int mid = inputs.size() / 2;
      List<float[]> left = embedBatchWithBisection(inputs.subList(0, mid));
      List<float[]> right = embedBatchWithBisection(inputs.subList(mid, inputs.size()));
      List<float[]> combined = new ArrayList<>(inputs.size());
      combined.addAll(left);
      combined.addAll(right);
      return combined;
    }
  }

  private List<float[]> embedBatch(List<String> inputs) {
    EmbeddingsRequest request = new EmbeddingsRequest(properties.model(), inputs);
    EmbeddingsResponse response =
        restClient
            .post()
            .uri("/embeddings")
            .headers(this::applyHeaders)
            .contentType(MediaType.APPLICATION_JSON)
            .body(request)
            .retrieve()
            .body(EmbeddingsResponse.class);
    return parseEmbeddings(response, inputs.size());
  }

  private static String normalizeBaseUrl(String baseUrl) {
    if (baseUrl.endsWith("/")) {
      return baseUrl.substring(0, baseUrl.length() - 1);
    }
    return baseUrl;
  }

  private record EmbeddingsRequest(String model, List<String> input) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record EmbeddingsResponse(List<EmbeddingData> data) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record EmbeddingData(List<Double> embedding, Integer index) {}
}
