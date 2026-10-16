package com.andromedia.core.index;

import com.andromedia.common.IndexPathHolder;
import com.andromedia.common.IndexPathResolver;
import com.andromedia.common.ServiceIndexPathResolver;
import com.andromedia.common.SlackChunk;
import com.andromedia.common.SlackHistoryCursorStore;
import com.andromedia.ingestion.slack.SlackHistoryChunkBuilder;
import com.andromedia.ingestion.slack.SlackIngestionOptions;
import com.andromedia.ingestion.slack.SlackIngestionService;
import com.andromedia.ingestion.slack.SlackThreadEvent;
import com.andromedia.indexing.IndexingService;
import com.andromedia.indexing.SlackHistoryIndexingRequest;
import com.andromedia.indexing.SlackHistoryIndexingStats;
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

@Service
public class SlackIngestionJobService {

  private static final Logger log = LoggerFactory.getLogger(SlackIngestionJobService.class);

  private final IndexingService indexingService;
  private final SlackIngestionJobStore jobStore;
  private final ServiceIndexMetadataStore metadataStore;
  private final SlackIngestionProperties slackIngestionProperties;
  private final SlackHistoryCursorStore cursorStore;
  private final ExecutorService executor = Executors.newCachedThreadPool();

  public SlackIngestionJobService(
      IndexingService indexingService,
      SlackIngestionJobStore jobStore,
      ServiceIndexMetadataStore metadataStore,
      SlackIngestionProperties slackIngestionProperties,
      SlackHistoryCursorStore cursorStore) {
    this.indexingService = indexingService;
    this.jobStore = jobStore;
    this.metadataStore = metadataStore;
    this.slackIngestionProperties = slackIngestionProperties;
    this.cursorStore = cursorStore;
  }

  public SlackIngestionJobStore.SlackIngestionJob enqueue(
      String service,
      String botToken,
      List<String> channels,
      boolean embed,
      Integer lookbackDays,
      String workspaceRoot) {
    String token =
        botToken == null || botToken.isBlank()
            ? slackIngestionProperties.botToken()
            : botToken.trim();
    if (token == null || token.isBlank()) {
      throw new IllegalArgumentException("Slack bot token is required");
    }

    List<String> effectiveChannels =
        channels == null || channels.isEmpty()
            ? slackIngestionProperties.channels()
            : List.copyOf(channels);
    if (effectiveChannels.isEmpty()) {
      throw new IllegalArgumentException("At least one Slack channel is required");
    }

    int effectiveLookback =
        lookbackDays == null || lookbackDays <= 0
            ? slackIngestionProperties.lookbackDays()
            : lookbackDays;

    String jobId = UUID.randomUUID().toString();
    SlackIngestionJobStore.SlackIngestionJob job =
        new SlackIngestionJobStore.SlackIngestionJob(
            jobId,
            service,
            token,
            effectiveChannels,
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

  public SlackIngestionJobStore.SlackIngestionJob getJob(String jobId) {
    return jobStore.get(jobId);
  }

  private void runJob(SlackIngestionJobStore.SlackIngestionJob job) {
    jobStore.updateStatus(job.jobId(), "running", null, null);
    Path indexPath = ServiceIndexPathResolver.resolveIndexPath(job.service());
    IndexPathHolder.setDirectIndexPath(indexPath);
    try {
      Path workspace =
          job.workspaceRoot() == null || job.workspaceRoot().isBlank()
              ? Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize()
              : Path.of(job.workspaceRoot()).toAbsolutePath().normalize();
      Path workspaceRoot = IndexPathResolver.resolveWorkspaceRoot(workspace);

      Map<String, Instant> cursors = resolveChannelCursors(job.service(), workspaceRoot);
      Instant oldest = SlackIngestionService.defaultOldest(job.lookbackDays());

      SlackIngestionOptions options =
          new SlackIngestionOptions(
              oldest,
              job.channels(),
              cursors,
              slackIngestionProperties.maxMessagesPerChannel(),
              slackIngestionProperties.skipBotMessages());

      SlackIngestionService ingestionService = new SlackIngestionService(job.botToken());
      SlackIngestionService.IngestResult ingestResult = ingestionService.ingest(options);
      if (ingestResult.threads().isEmpty()) {
        Instant now = Instant.now();
        SlackHistoryIndexingStats emptyStats =
            new SlackHistoryIndexingStats(0, 0, 0, 0, now, now);
        Map<String, Object> statsMap =
            toMap(emptyStats, indexPath, ingestResult.latestTimestampStrings(), job.channels());
        statsMap.put("message", "No new Slack threads found for configured channels");
        jobStore.updateStatus(job.jobId(), "completed", statsMap, null);
        mergeMetadata(job.service(), statsMap);
        log.info("No new Slack threads for service {} in {}", job.service(), indexPath);
        return;
      }

      List<SlackChunk> chunks = new ArrayList<>(ingestResult.threads().size());
      for (SlackThreadEvent thread : ingestResult.threads()) {
        chunks.add(SlackHistoryChunkBuilder.build(thread, ingestResult.userNames()));
      }

      SlackHistoryIndexingStats stats =
          indexingService.indexSlackHistory(
              new SlackHistoryIndexingRequest(chunks, job.embed(), false, workspaceRoot));

      cursorStore.saveCursors(workspaceRoot, ingestResult.latestTimestampStrings());
      Map<String, Object> statsMap =
          toMap(stats, indexPath, ingestResult.latestTimestampStrings(), job.channels());
      jobStore.updateStatus(job.jobId(), "completed", statsMap, null);
      mergeMetadata(job.service(), statsMap);
      log.info("Indexed Slack history for service {} into {}", job.service(), indexPath);
    } catch (Exception ex) {
      log.warn(
          "Slack ingestion job {} failed for service {}: {}",
          job.jobId(),
          job.service(),
          ex.getMessage());
      jobStore.updateStatus(job.jobId(), "failed", null, ex.getMessage());
    } finally {
      IndexPathHolder.clear();
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Instant> resolveChannelCursors(String service, Path workspaceRoot) {
    Map<String, Instant> cursors = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : cursorStore.readCursors(workspaceRoot).entrySet()) {
      try {
        cursors.put(entry.getKey(), Instant.parse(entry.getValue()));
      } catch (Exception ignored) {
        // skip malformed cursor
      }
    }
    ServiceIndexMetadataStore.ServiceIndexMetadata metadata = metadataStore.get(service);
    if (metadata != null && metadata.stats() != null) {
      Object value = metadata.stats().get("slackCursors");
      if (value instanceof Map<?, ?> map) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
          if (entry.getKey() == null || entry.getValue() == null) {
            continue;
          }
          try {
            cursors.put(entry.getKey().toString(), Instant.parse(entry.getValue().toString()));
          } catch (Exception ignored) {
            // skip malformed cursor
          }
        }
      }
    }
    return cursors;
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
      SlackHistoryIndexingStats stats,
      Path indexPath,
      Map<String, String> slackCursors,
      List<String> channels) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("threadsIndexed", stats.threadsIndexed());
    map.put("chunksIndexed", stats.chunksIndexed());
    map.put("chunksEmbedded", stats.chunksEmbedded());
    map.put("embeddingApiCalls", stats.embeddingApiCalls());
    map.put("startedAt", stats.startedAt() != null ? stats.startedAt().toString() : null);
    map.put("finishedAt", stats.finishedAt() != null ? stats.finishedAt().toString() : null);
    map.put("sourceType", "slack-history");
    map.put("indexPath", indexPath.toString());
    map.put("slackCursors", slackCursors);
    map.put("channels", channels);
    return map;
  }
}
