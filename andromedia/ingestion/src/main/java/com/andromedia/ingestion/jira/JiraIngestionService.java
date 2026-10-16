package com.andromedia.ingestion.jira;

import com.andromedia.common.JiraChunk;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JiraIngestionService {

  private static final Logger log = LoggerFactory.getLogger(JiraIngestionService.class);

  private final JiraApiClient apiClient;

  public JiraIngestionService(JiraApiClient apiClient) {
    this.apiClient = apiClient;
  }

  public JiraApiClient.JiraMyselfResult validateCredentials() throws IOException {
    return apiClient.myself();
  }

  public IngestResult ingest(JiraIngestionOptions options) throws IOException {
    List<JiraIssue> issues = apiClient.searchIssues(options.projectKeys());
    List<JiraChunk> chunks = new ArrayList<>();
    Map<String, String> latestUpdated = new LinkedHashMap<>();
    int issuesIndexed = 0;

    for (JiraIssue issue : JiraIssueChunker.chunk(issues)) {
      if (!JiraIssueFilter.shouldIndex(issue, options.lookbackDays())) {
        log.debug("Skipping Jira issue {} status={}", issue.key(), issue.status());
        continue;
      }
      chunks.add(JiraHistoryChunkBuilder.build(issue));
      if (issue.projectKey() != null && !issue.projectKey().isBlank() && issue.updated() != null) {
        latestUpdated.merge(
            issue.projectKey(),
            issue.updated().toString(),
            (a, b) -> a.compareTo(b) >= 0 ? a : b);
      }
      issuesIndexed++;
    }

    log.info(
        "Jira ingest: {} issues fetched, {} indexed, {} chunks",
        issues.size(),
        issuesIndexed,
        chunks.size());
    return new IngestResult(chunks, latestUpdated, issuesIndexed);
  }

  public record IngestResult(
      List<JiraChunk> chunks, Map<String, String> latestUpdatedByProject, int issuesIndexed) {

    public IngestResult {
      chunks = chunks == null ? List.of() : List.copyOf(chunks);
      latestUpdatedByProject =
          latestUpdatedByProject == null ? Map.of() : Map.copyOf(latestUpdatedByProject);
    }
  }
}
