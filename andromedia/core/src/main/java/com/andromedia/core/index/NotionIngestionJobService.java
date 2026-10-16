package com.andromedia.core.index;

import com.andromedia.common.IndexPathHolder;
import com.andromedia.common.IndexPathResolver;
import com.andromedia.common.NotionHistoryCursorStore;
import com.andromedia.common.ServiceIndexPathResolver;
import com.andromedia.ingestion.notion.NotionApiClient;
import com.andromedia.ingestion.notion.NotionIngestionOptions;
import com.andromedia.ingestion.notion.NotionIngestionService;
import com.andromedia.indexing.IndexingService;
import com.andromedia.indexing.NotionHistoryIndexingRequest;
import com.andromedia.indexing.NotionHistoryIndexingStats;
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
public class NotionIngestionJobService {

  private static final Logger log = LoggerFactory.getLogger(NotionIngestionJobService.class);

  private final IndexingService indexingService;
  private final NotionIngestionJobStore jobStore;
  private final ServiceIndexMetadataStore metadataStore;
  private final NotionIngestionProperties notionIngestionProperties;
  private final NotionHistoryCursorStore cursorStore;
  private final ExecutorService executor = Executors.newCachedThreadPool();

  public NotionIngestionJobService(
      IndexingService indexingService,
      NotionIngestionJobStore jobStore,
      ServiceIndexMetadataStore metadataStore,
      NotionIngestionProperties notionIngestionProperties,
      NotionHistoryCursorStore cursorStore) {
    this.indexingService = indexingService;
    this.jobStore = jobStore;
    this.metadataStore = metadataStore;
    this.notionIngestionProperties = notionIngestionProperties;
    this.cursorStore = cursorStore;
  }

  public NotionIngestionJobStore.NotionIngestionJob enqueue(
      String service,
      String apiKey,
      List<String> pageIds,
      List<String> databaseIds,
      boolean embed,
      String workspaceRoot) {
    String token =
        apiKey == null || apiKey.isBlank()
            ? notionIngestionProperties.apiKey()
            : apiKey.trim();
    if (token == null || token.isBlank()) {
      throw new IllegalArgumentException("Notion API key is required");
    }

    List<String> effectivePages =
        pageIds == null || pageIds.isEmpty()
            ? notionIngestionProperties.pageIds()
            : List.copyOf(pageIds);
    List<String> effectiveDatabases =
        databaseIds == null || databaseIds.isEmpty()
            ? notionIngestionProperties.databaseIds()
            : List.copyOf(databaseIds);
    if (effectivePages.isEmpty() && effectiveDatabases.isEmpty()) {
      throw new IllegalArgumentException("At least one Notion pageId or databaseId is required");
    }

    try {
      new NotionApiClient(token).usersMe();
    } catch (Exception ex) {
      throw new IllegalArgumentException(
          ex.getMessage() == null ? "Notion API key validation failed" : ex.getMessage());
    }

    String jobId = UUID.randomUUID().toString();
    NotionIngestionJobStore.NotionIngestionJob job =
        new NotionIngestionJobStore.NotionIngestionJob(
            jobId,
            service,
            token,
            effectivePages,
            effectiveDatabases,
            embed,
            workspaceRoot,
            "queued",
            null,
            null);
    jobStore.put(job);
    executor.submit(() -> runJob(job));
    return job;
  }

  public NotionIngestionJobStore.NotionIngestionJob getJob(String jobId) {
    return jobStore.get(jobId);
  }

  private void runJob(NotionIngestionJobStore.NotionIngestionJob job) {
    jobStore.updateStatus(job.jobId(), "running", null, null);
    Path indexPath = ServiceIndexPathResolver.resolveIndexPath(job.service());
    IndexPathHolder.setDirectIndexPath(indexPath);
    try {
      Path workspace =
          job.workspaceRoot() == null || job.workspaceRoot().isBlank()
              ? Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize()
              : Path.of(job.workspaceRoot()).toAbsolutePath().normalize();
      Path workspaceRoot = IndexPathResolver.resolveWorkspaceRoot(workspace);

      NotionIngestionService ingestionService =
          new NotionIngestionService(new NotionApiClient(job.apiKey()));
      NotionIngestionService.IngestResult ingestResult =
          ingestionService.ingest(new NotionIngestionOptions(job.pageIds(), job.databaseIds()));

      if (ingestResult.chunks().isEmpty()) {
        throw new IllegalStateException(
            "No Notion sections found to index. Share the page with your Notion integration, "
                + "confirm the page ID, and ensure the page has text content.");
      }

      NotionHistoryIndexingStats stats =
          indexingService.indexNotionHistory(
              new NotionHistoryIndexingRequest(
                  ingestResult.chunks(), job.embed(), false, workspaceRoot));

      cursorStore.saveCursors(workspaceRoot, ingestResult.latestEditedByPage());

      Map<String, Object> statsMap =
          toMap(stats, ingestResult.pagesIndexed(), indexPath, job.pageIds(), job.databaseIds());
      statsMap.put("notionCursors", ingestResult.latestEditedByPage());
      jobStore.updateStatus(job.jobId(), "completed", statsMap, null);
      mergeMetadata(job.service(), statsMap);
      log.info(
          "Indexed Notion history for service {} into {} ({} chunks)",
          job.service(),
          indexPath,
          stats.chunksIndexed());
    } catch (Exception ex) {
      log.warn("Notion ingestion failed for {}: {}", job.service(), ex.getMessage());
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
      NotionHistoryIndexingStats stats,
      int pagesIndexed,
      Path indexPath,
      List<String> pageIds,
      List<String> databaseIds) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("pagesIndexed", pagesIndexed);
    map.put("chunksIndexed", stats.chunksIndexed());
    map.put("chunksEmbedded", stats.chunksEmbedded());
    map.put("embeddingApiCalls", stats.embeddingApiCalls());
    map.put("startedAt", stats.startedAt() != null ? stats.startedAt().toString() : null);
    map.put("finishedAt", stats.finishedAt() != null ? stats.finishedAt().toString() : null);
    map.put("sourceType", "notion-history");
    map.put("indexPath", indexPath.toString());
    map.put("pageIds", pageIds);
    map.put("databaseIds", databaseIds);
    return map;
  }
}
