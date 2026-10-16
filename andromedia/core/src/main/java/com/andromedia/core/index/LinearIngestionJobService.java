package com.andromedia.core.index;

import com.andromedia.common.IndexPathHolder;
import com.andromedia.common.IndexPathResolver;
import com.andromedia.common.LinearHistoryCursorStore;
import com.andromedia.common.ServiceIndexPathResolver;
import com.andromedia.ingestion.linear.LinearApiClient;
import com.andromedia.ingestion.linear.LinearIngestionOptions;
import com.andromedia.ingestion.linear.LinearIngestionService;
import com.andromedia.indexing.IndexingService;
import com.andromedia.indexing.LinearHistoryIndexingRequest;
import com.andromedia.indexing.LinearHistoryIndexingStats;
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
public class LinearIngestionJobService {

  private static final Logger log = LoggerFactory.getLogger(LinearIngestionJobService.class);

  private final IndexingService indexingService;
  private final LinearIngestionJobStore jobStore;
  private final ServiceIndexMetadataStore metadataStore;
  private final LinearIngestionProperties linearIngestionProperties;
  private final LinearHistoryCursorStore cursorStore;
  private final ExecutorService executor = Executors.newCachedThreadPool();

  public LinearIngestionJobService(
      IndexingService indexingService,
      LinearIngestionJobStore jobStore,
      ServiceIndexMetadataStore metadataStore,
      LinearIngestionProperties linearIngestionProperties,
      LinearHistoryCursorStore cursorStore) {
    this.indexingService = indexingService;
    this.jobStore = jobStore;
    this.metadataStore = metadataStore;
    this.linearIngestionProperties = linearIngestionProperties;
    this.cursorStore = cursorStore;
  }

  public LinearIngestionJobStore.LinearIngestionJob enqueue(
      String service,
      String apiKey,
      List<String> teamKeys,
      Integer lookbackDays,
      boolean embed,
      String workspaceRoot) {
    String token =
        apiKey == null || apiKey.isBlank()
            ? linearIngestionProperties.apiKey()
            : apiKey.trim();
    if (token == null || token.isBlank()) {
      throw new IllegalArgumentException("Linear API key is required");
    }

    List<String> effectiveTeams =
        teamKeys == null || teamKeys.isEmpty()
            ? linearIngestionProperties.teamKeys()
            : List.copyOf(teamKeys);
    if (effectiveTeams.isEmpty()) {
      throw new IllegalArgumentException("At least one Linear teamKey is required");
    }
    int lookback =
        lookbackDays == null || lookbackDays <= 0
            ? linearIngestionProperties.lookbackDays()
            : lookbackDays;

    try {
      new LinearApiClient(token).viewer();
    } catch (Exception ex) {
      throw new IllegalArgumentException(
          ex.getMessage() == null ? "Linear API key validation failed" : ex.getMessage());
    }

    String jobId = UUID.randomUUID().toString();
    LinearIngestionJobStore.LinearIngestionJob job =
        new LinearIngestionJobStore.LinearIngestionJob(
            jobId,
            service,
            token,
            effectiveTeams,
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

  public LinearIngestionJobStore.LinearIngestionJob getJob(String jobId) {
    return jobStore.get(jobId);
  }

  private void runJob(LinearIngestionJobStore.LinearIngestionJob job) {
    jobStore.updateStatus(job.jobId(), "running", null, null);
    Path indexPath = ServiceIndexPathResolver.resolveIndexPath(job.service());
    IndexPathHolder.setDirectIndexPath(indexPath);
    try {
      Path workspace =
          job.workspaceRoot() == null || job.workspaceRoot().isBlank()
              ? Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize()
              : Path.of(job.workspaceRoot()).toAbsolutePath().normalize();
      Path workspaceRoot = IndexPathResolver.resolveWorkspaceRoot(workspace);

      LinearIngestionService ingestionService =
          new LinearIngestionService(new LinearApiClient(job.apiKey()));
      LinearIngestionService.IngestResult ingestResult =
          ingestionService.ingest(new LinearIngestionOptions(job.teamKeys(), job.lookbackDays()));

      if (ingestResult.chunks().isEmpty()) {
        throw new IllegalStateException(
            "No Linear issues found to index. Check team keys and lookback filter.");
      }

      LinearHistoryIndexingStats stats =
          indexingService.indexLinearHistory(
              new LinearHistoryIndexingRequest(
                  ingestResult.chunks(), job.embed(), false, workspaceRoot));

      cursorStore.saveCursors(workspaceRoot, ingestResult.latestUpdatedByTeam());

      Map<String, Object> statsMap =
          toMap(stats, ingestResult.issuesIndexed(), indexPath, job.teamKeys());
      statsMap.put("linearCursors", ingestResult.latestUpdatedByTeam());
      jobStore.updateStatus(job.jobId(), "completed", statsMap, null);
      mergeMetadata(job.service(), statsMap);
      log.info(
          "Indexed Linear history for service {} into {} ({} chunks)",
          job.service(),
          indexPath,
          stats.chunksIndexed());
    } catch (Exception ex) {
      log.warn("Linear ingestion failed for {}: {}", job.service(), ex.getMessage());
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
      LinearHistoryIndexingStats stats, int issuesIndexed, Path indexPath, List<String> teamKeys) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("issuesIndexed", issuesIndexed);
    map.put("chunksIndexed", stats.chunksIndexed());
    map.put("chunksEmbedded", stats.chunksEmbedded());
    map.put("embeddingApiCalls", stats.embeddingApiCalls());
    map.put("startedAt", stats.startedAt() != null ? stats.startedAt().toString() : null);
    map.put("finishedAt", stats.finishedAt() != null ? stats.finishedAt().toString() : null);
    map.put("sourceType", "linear-history");
    map.put("indexPath", indexPath.toString());
    map.put("teamKeys", teamKeys);
    return map;
  }
}
