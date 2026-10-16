package com.andromedia.ingestion.linear;

import com.andromedia.common.LinearChunk;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LinearIngestionService {

  private static final Logger log = LoggerFactory.getLogger(LinearIngestionService.class);

  private final LinearApiClient apiClient;

  public LinearIngestionService(LinearApiClient apiClient) {
    this.apiClient = apiClient;
  }

  public LinearApiClient.LinearViewerResult validateToken() throws IOException {
    return apiClient.viewer();
  }

  public IngestResult ingest(LinearIngestionOptions options) throws IOException {
    List<LinearIssue> issues = apiClient.fetchIssues(options.teamKeys());
    Set<String> teamFilter =
        options.teamKeys().isEmpty() ? Set.of() : new LinkedHashSet<>(options.teamKeys());
    List<LinearChunk> chunks = new ArrayList<>();
    Map<String, String> latestUpdated = new LinkedHashMap<>();
    int issuesIndexed = 0;

    for (LinearIssue issue : LinearIssueChunker.chunk(issues)) {
      if (!LinearIssueFilter.shouldIndex(issue, options.lookbackDays(), teamFilter)) {
        log.debug("Skipping Linear issue {} state={}", issue.identifier(), issue.state());
        continue;
      }
      chunks.add(LinearChunkBuilder.build(issue));
      if (issue.team() != null && !issue.team().isBlank() && issue.updatedAt() != null) {
        latestUpdated.merge(
            issue.team(),
            issue.updatedAt().toString(),
            (a, b) -> a.compareTo(b) >= 0 ? a : b);
      }
      issuesIndexed++;
    }

    log.info(
        "Linear ingest: {} issues fetched, {} indexed, {} chunks",
        issues.size(),
        issuesIndexed,
        chunks.size());
    return new IngestResult(chunks, latestUpdated, issuesIndexed);
  }

  public record IngestResult(
      List<LinearChunk> chunks, Map<String, String> latestUpdatedByTeam, int issuesIndexed) {

    public IngestResult {
      chunks = chunks == null ? List.of() : List.copyOf(chunks);
      latestUpdatedByTeam =
          latestUpdatedByTeam == null ? Map.of() : Map.copyOf(latestUpdatedByTeam);
    }
  }
}
