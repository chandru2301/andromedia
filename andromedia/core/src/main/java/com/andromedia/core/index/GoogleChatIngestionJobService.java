package com.andromedia.core.index;

import com.andromedia.common.GoogleChatChunk;
import com.andromedia.common.IndexPathHolder;
import com.andromedia.common.IndexPathResolver;
import com.andromedia.common.ServiceIndexPathResolver;
import com.andromedia.ingestion.googlechat.GoogleChatHistoryChunkBuilder;
import com.andromedia.ingestion.googlechat.GoogleChatIngestionOptions;
import com.andromedia.ingestion.googlechat.GoogleChatIngestionService;
import com.andromedia.ingestion.googlechat.GoogleChatThreadEvent;
import com.andromedia.indexing.GoogleChatHistoryIndexingRequest;
import com.andromedia.indexing.GoogleChatHistoryIndexingStats;
import com.andromedia.indexing.IndexingService;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Note on scope: unlike Slack's cursor-based incremental sync, this re-fetches the full lookback
 * window on every run (no persisted per-space cursor yet) — correct, just less efficient on
 * repeated re-indexing. Add a cursor store the same way {@code SlackHistoryCursorStore} works if
 * that becomes a problem.
 */
@Service
public class GoogleChatIngestionJobService {

  private static final Logger log = LoggerFactory.getLogger(GoogleChatIngestionJobService.class);

  private final IndexingService indexingService;
  private final GoogleChatIngestionJobStore jobStore;
  private final ServiceIndexMetadataStore metadataStore;
  private final GoogleChatIngestionProperties properties;
  private final ExecutorService executor = Executors.newCachedThreadPool();

  public GoogleChatIngestionJobService(
      IndexingService indexingService,
      GoogleChatIngestionJobStore jobStore,
      ServiceIndexMetadataStore metadataStore,
      GoogleChatIngestionProperties properties) {
    this.indexingService = indexingService;
    this.jobStore = jobStore;
    this.metadataStore = metadataStore;
    this.properties = properties;
  }

  public GoogleChatIngestionJobStore.GoogleChatIngestionJob enqueue(
      String service,
      String accessToken,
      List<String> spaces,
      boolean embed,
      Integer lookbackDays,
      String workspaceRoot) {
    if (accessToken == null || accessToken.isBlank()) {
      throw new IllegalArgumentException(
          "Google Chat access token is required — connect via OAuth first");
    }

    List<String> effectiveSpaces =
        spaces == null || spaces.isEmpty() ? properties.spaces() : List.copyOf(spaces);

    int effectiveLookback =
        lookbackDays == null || lookbackDays <= 0 ? properties.lookbackDays() : lookbackDays;

    String jobId = UUID.randomUUID().toString();
    GoogleChatIngestionJobStore.GoogleChatIngestionJob job =
        new GoogleChatIngestionJobStore.GoogleChatIngestionJob(
            jobId,
            service,
            accessToken.trim(),
            effectiveSpaces,
            embed,
            effectiveLookback,
            workspaceRoot,
            "queued",
            null,
            null);
    jobStore.put(job);
    executor.submit(() -> runJob(job));
    return job;
  }

  public GoogleChatIngestionJobStore.GoogleChatIngestionJob getJob(String jobId) {
    return jobStore.get(jobId);
  }

  private void runJob(GoogleChatIngestionJobStore.GoogleChatIngestionJob job) {
    jobStore.updateStatus(job.jobId(), "running", null, null);
    Path indexPath = ServiceIndexPathResolver.resolveIndexPath(job.service());
    IndexPathHolder.setDirectIndexPath(indexPath);
    try {
      Path workspace =
          job.workspaceRoot() == null || job.workspaceRoot().isBlank()
              ? Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize()
              : Path.of(job.workspaceRoot()).toAbsolutePath().normalize();
      Path workspaceRoot = IndexPathResolver.resolveWorkspaceRoot(workspace);

      Instant oldest = GoogleChatIngestionService.defaultOldest(job.lookbackDays());
      GoogleChatIngestionOptions options =
          new GoogleChatIngestionOptions(oldest, job.spaces(), properties.maxMessagesPerSpace());

      GoogleChatIngestionService ingestionService = new GoogleChatIngestionService(job.accessToken());
      GoogleChatIngestionService.IngestResult ingestResult = ingestionService.ingest(options);

      if (ingestResult.threads().isEmpty()) {
        Instant now = Instant.now();
        GoogleChatHistoryIndexingStats emptyStats =
            new GoogleChatHistoryIndexingStats(0, 0, 0, 0, now, now);
        Map<String, Object> statsMap = toMap(emptyStats, indexPath, job.spaces());
        statsMap.put("message", "No new Google Chat threads found for configured spaces");
        jobStore.updateStatus(job.jobId(), "completed", statsMap, null);
        mergeMetadata(job.service(), statsMap);
        log.info("No new Google Chat threads for service {} in {}", job.service(), indexPath);
        return;
      }

      List<GoogleChatChunk> chunks = new ArrayList<>(ingestResult.threads().size());
      for (GoogleChatThreadEvent thread : ingestResult.threads()) {
        chunks.add(GoogleChatHistoryChunkBuilder.build(thread));
      }

      GoogleChatHistoryIndexingStats stats =
          indexingService.indexGoogleChatHistory(
              new GoogleChatHistoryIndexingRequest(chunks, job.embed(), false, workspaceRoot));

      Map<String, Object> statsMap = toMap(stats, indexPath, job.spaces());
      jobStore.updateStatus(job.jobId(), "completed", statsMap, null);
      mergeMetadata(job.service(), statsMap);
      log.info("Indexed Google Chat history for service {} into {}", job.service(), indexPath);
    } catch (Exception ex) {
      String rootCauseMessage = rootCauseMessage(ex);
      log.warn(
          "Google Chat ingestion job {} failed for service {}: {}",
          job.jobId(),
          job.service(),
          rootCauseMessage,
          ex);
      jobStore.updateStatus(job.jobId(), "failed", null, rootCauseMessage);
    } finally {
      IndexPathHolder.clear();
    }
  }

  /**
   * Wrapping exceptions (e.g. IndexingException's fixed "Failed to index Google Chat history")
   * carry no diagnostic value on their own — walk to the deepest cause so the job's stored error
   * and the log line both surface what actually broke (e.g. the underlying IOException message).
   */
  private static String rootCauseMessage(Throwable ex) {
    Throwable deepest = ex;
    while (deepest.getCause() != null && deepest.getCause() != deepest) {
      deepest = deepest.getCause();
    }
    String message = deepest.getMessage();
    String detail = message == null || message.isBlank() ? deepest.getClass().getSimpleName() : message;
    return deepest == ex ? detail : detail + " (" + deepest.getClass().getSimpleName() + ")";
  }

  private void mergeMetadata(String service, Map<String, Object> statsMap) {
    ServiceIndexMetadataStore.ServiceIndexMetadata existing = metadataStore.get(service);
    Map<String, Object> merged = new LinkedHashMap<>();
    if (existing != null && existing.stats() != null) {
      merged.putAll(existing.stats());
    }
    merged.putAll(statsMap);
    metadataStore.save(service, merged);
  }

  private static Map<String, Object> toMap(
      GoogleChatHistoryIndexingStats stats, Path indexPath, List<String> spaces) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("threadsIndexed", stats.threadsIndexed());
    map.put("chunksIndexed", stats.chunksIndexed());
    map.put("chunksEmbedded", stats.chunksEmbedded());
    map.put("embeddingApiCalls", stats.embeddingApiCalls());
    map.put("startedAt", stats.startedAt() != null ? stats.startedAt().toString() : null);
    map.put("finishedAt", stats.finishedAt() != null ? stats.finishedAt().toString() : null);
    map.put("sourceType", "google-chat-history");
    map.put("indexPath", indexPath.toString());
    map.put("spaces", spaces);
    return map;
  }
}
