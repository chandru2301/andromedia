package com.andromedia.core.index;

import com.andromedia.common.IndexPathHolder;
import com.andromedia.common.IndexPathResolver;
import com.andromedia.common.JiraHistoryCursorStore;
import com.andromedia.common.ServiceIndexPathResolver;
import com.andromedia.ingestion.jira.JiraApiClient;
import com.andromedia.ingestion.jira.JiraIngestionOptions;
import com.andromedia.ingestion.jira.JiraIngestionService;
import com.andromedia.indexing.IndexingService;
import com.andromedia.indexing.JiraHistoryIndexingRequest;
import com.andromedia.indexing.JiraHistoryIndexingStats;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class JiraIngestionJobService {

  private static final Logger log = LoggerFactory.getLogger(JiraIngestionJobService.class);

  private final IndexingService indexingService;
  private final JiraIngestionJobStore jobStore;
  private final ServiceIndexMetadataStore metadataStore;
  private final JiraIngestionProperties jiraIngestionProperties;
  private final JiraHistoryCursorStore cursorStore;
  private final ExecutorService executor = Executors.newCachedThreadPool();

  public JiraIngestionJobService(
      IndexingService indexingService,
      JiraIngestionJobStore jobStore,
      ServiceIndexMetadataStore metadataStore,
      JiraIngestionProperties jiraIngestionProperties,
      JiraHistoryCursorStore cursorStore) {
    this.indexingService = indexingService;
    this.jobStore = jobStore;
    this.metadataStore = metadataStore;
    this.jiraIngestionProperties = jiraIngestionProperties;
    this.cursorStore = cursorStore;
  }

  public JiraIngestionJobStore.JiraIngestionJob enqueue(
      String service,
      String cloudId,
      String accessToken,
      List<String> projectKeys,
      Integer lookbackDays,
      boolean embed,
      String workspaceRoot) {
    if (cloudId == null || cloudId.isBlank()) {
      throw new IllegalArgumentException("Jira cloudId is required");
    }
    if (accessToken == null || accessToken.isBlank()) {
      throw new IllegalArgumentException("Jira access token is required");
    }

    List<String> effectiveProjects =
        projectKeys == null || projectKeys.isEmpty()
            ? jiraIngestionProperties.projectKeys()
            : List.copyOf(projectKeys);
    if (effectiveProjects.isEmpty()) {
      throw new IllegalArgumentException("At least one Jira projectKey is required");
    }
    int lookback =
        lookbackDays == null || lookbackDays <= 0
            ? jiraIngestionProperties.lookbackDays()
            : lookbackDays;

    try {
      new JiraApiClient(cloudId, accessToken).myself();
    } catch (Exception ex) {
      throw new IllegalArgumentException(
          ex.getMessage() == null ? "Jira credential validation failed" : ex.getMessage());
    }

    String jobId = UUID.randomUUID().toString();
    JiraIngestionJobStore.JiraIngestionJob job =
        new JiraIngestionJobStore.JiraIngestionJob(
            jobId,
            service,
            cloudId,
            accessToken,
            effectiveProjects,
            lookback,
            embed,
            workspaceRoot,
            "queued",
            null,
            null);
    jobStore.put(job);
    executor.submit(() -> runJob(job));
    return job;
  }

  public JiraIngestionJobStore.JiraIngestionJob getJob(String jobId) {
    return jobStore.get(jobId);
  }

  private void runJob(JiraIngestionJobStore.JiraIngestionJob job) {
    jobStore.updateStatus(job.jobId(), "running", null, null);
    Path indexPath = ServiceIndexPathResolver.resolveIndexPath(job.service());
    IndexPathHolder.setDirectIndexPath(indexPath);
    try {
      Path workspace =
          job.workspaceRoot() == null || job.workspaceRoot().isBlank()
              ? Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize()
              : Path.of(job.workspaceRoot()).toAbsolutePath().normalize();
      Path workspaceRoot = IndexPathResolver.resolveWorkspaceRoot(workspace);

      JiraIngestionService ingestionService =
          new JiraIngestionService(
              new JiraApiClient(job.cloudId(), job.accessToken()));
      JiraIngestionService.IngestResult ingestResult =
          ingestionService.ingest(
              new JiraIngestionOptions(job.projectKeys(), job.lookbackDays()));

      if (ingestResult.chunks().isEmpty()) {
        throw new IllegalStateException(
            "No Jira issues found to index. Check project keys and lookback filter.");
      }

      JiraHistoryIndexingStats stats =
          indexingService.indexJiraHistory(
              new JiraHistoryIndexingRequest(
                  ingestResult.chunks(), job.embed(), false, workspaceRoot));

      cursorStore.saveCursors(workspaceRoot, ingestResult.latestUpdatedByProject());

      Map<String, Object> statsMap =
          toMap(stats, ingestResult.issuesIndexed(), indexPath, job.projectKeys());
      statsMap.put("jiraCursors", ingestResult.latestUpdatedByProject());
      jobStore.updateStatus(job.jobId(), "completed", statsMap, null);
      mergeMetadata(job.service(), statsMap);
      log.info(
          "Indexed Jira history for service {} into {} ({} chunks)",
          job.service(),
          indexPath,
          stats.chunksIndexed());
    } catch (Exception ex) {
      log.warn("Jira ingestion failed for {}: {}", job.service(), ex.getMessage());
      jobStore.updateStatus(
          job.jobId(),
          "failed",
          null,
          ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
    } finally {
      IndexPathHolder.clear();
    }
  }

  private void mergeMetadata(String service, Map<String, Object> statsMap) {
    Map<String, Object> merged = new LinkedHashMap<>();
    ServiceIndexMetadataStore.ServiceIndexMetadata existing = metadataStore.get(service);
    if (existing != null && existing.stats() != null) {
      merged.putAll(existing.stats());
    }
    merged.putAll(statsMap);
    metadataStore.save(service, merged);
  }

  private static Map<String, Object> toMap(
      JiraHistoryIndexingStats stats, int issuesIndexed, Path indexPath, List<String> projectKeys) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("issuesIndexed", issuesIndexed);
    map.put("chunksIndexed", stats.chunksIndexed());
    map.put("chunksEmbedded", stats.chunksEmbedded());
    map.put("embeddingApiCalls", stats.embeddingApiCalls());
    map.put("startedAt", stats.startedAt() != null ? stats.startedAt().toString() : null);
    map.put("finishedAt", stats.finishedAt() != null ? stats.finishedAt().toString() : null);
    map.put("sourceType", "jira-history");
    map.put("indexPath", indexPath.toString());
    map.put("projectKeys", projectKeys);
    return map;
  }
}
