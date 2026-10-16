package com.andromedia.ingestion.googlechat;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GoogleChatIngestionService {

  private static final Logger log = LoggerFactory.getLogger(GoogleChatIngestionService.class);

  private final GoogleChatApiClient apiClient;

  public GoogleChatIngestionService(String accessToken) {
    this(new GoogleChatApiClient(accessToken));
  }

  public GoogleChatIngestionService(GoogleChatApiClient apiClient) {
    this.apiClient = apiClient;
  }

  public IngestResult ingest(GoogleChatIngestionOptions options) throws IOException {
    GoogleChatIngestionOptions effective =
        options == null ? GoogleChatIngestionOptions.defaults() : options;
    List<GoogleChatApiClient.GoogleChatSpace> available = apiClient.listSpaces();
    List<GoogleChatApiClient.GoogleChatSpace> selected = resolveSpaces(available, effective.spaces());
    if (selected.isEmpty()) {
      throw new IllegalArgumentException(
          "No matching Google Chat spaces found for configured names/ids: " + effective.spaces());
    }

    List<GoogleChatThreadEvent> threads = new ArrayList<>();
    for (GoogleChatApiClient.GoogleChatSpace space : selected) {
      List<GoogleChatMessage> messages =
          apiClient.fetchMessages(space.name(), effective.oldest(), effective.maxMessagesPerSpace());
      List<GoogleChatThreadEvent> spaceThreads =
          GoogleChatThreadChunker.chunk(space.name(), space.displayName(), messages);
      threads.addAll(spaceThreads);
      log.info(
          "Google Chat space {}: {} messages → {} threads",
          space.displayName(),
          messages.size(),
          spaceThreads.size());
    }

    return new IngestResult(threads);
  }

  public static Instant defaultOldest(int lookbackDays) {
    int days = lookbackDays <= 0 ? 90 : lookbackDays;
    return Instant.now().minus(days, ChronoUnit.DAYS);
  }

  private static List<GoogleChatApiClient.GoogleChatSpace> resolveSpaces(
      List<GoogleChatApiClient.GoogleChatSpace> available, List<String> configured) {
    if (configured == null || configured.isEmpty()) {
      return available;
    }
    Map<String, GoogleChatApiClient.GoogleChatSpace> byName = new LinkedHashMap<>();
    Map<String, GoogleChatApiClient.GoogleChatSpace> byDisplayName = new LinkedHashMap<>();
    for (GoogleChatApiClient.GoogleChatSpace space : available) {
      byName.put(space.name(), space);
      byDisplayName.put(normalize(space.displayName()), space);
    }
    List<GoogleChatApiClient.GoogleChatSpace> selected = new ArrayList<>();
    for (String raw : configured) {
      if (raw == null || raw.isBlank()) {
        continue;
      }
      String trimmed = raw.trim();
      GoogleChatApiClient.GoogleChatSpace match = byName.get(trimmed);
      if (match == null) {
        match = byDisplayName.get(normalize(trimmed));
      }
      if (match != null) {
        selected.add(match);
      }
    }
    return selected;
  }

  private static String normalize(String name) {
    return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
  }

  public record IngestResult(List<GoogleChatThreadEvent> threads) {
    public IngestResult {
      threads = threads == null ? List.of() : List.copyOf(threads);
    }
  }
}
