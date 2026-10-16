package com.andromedia.core.index;

import com.andromedia.common.ChunkIdGenerator;
import com.andromedia.common.IndexPathHolder;
import com.andromedia.common.IndexPathResolver;
import com.andromedia.common.ServiceIndexPathResolver;
import com.andromedia.common.ZohoSyncWatermarkStore;
import com.andromedia.core.alerts.DealAlertMonitor;
import com.andromedia.core.credentials.ZohoConnectionVault;
import com.andromedia.core.credentials.ZohoConnectionVault.DecryptedZohoCredentials;
import com.andromedia.core.credentials.ZohoReauthRequiredException;
import com.andromedia.ingestion.crm.CrmConnection;
import com.andromedia.ingestion.crm.CrmDataGapReport;
import com.andromedia.ingestion.crm.CrmSchemaCache;
import com.andromedia.ingestion.zoho.ZohoApiClient;
import com.andromedia.ingestion.zoho.ZohoIngestionOptions;
import com.andromedia.ingestion.zoho.ZohoIngestionService;
import com.andromedia.ingestion.zoho.ZohoRegion;
import com.andromedia.ingestion.zoho.ZohoSchemaAdapter;
import com.andromedia.indexing.IndexingService;
import com.andromedia.indexing.ZohoHistoryIndexingRequest;
import com.andromedia.indexing.ZohoHistoryIndexingStats;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ZohoIngestionJobService {

  private static final Logger log = LoggerFactory.getLogger(ZohoIngestionJobService.class);
  private static final Duration DELETED_RECORDS_RETENTION = Duration.ofDays(30);
  private static final String MODULE_DEALS = "Deals";
  private static final String MODULE_ACCOUNTS = "Accounts";
  private static final String MODULE_CONTACTS = "Contacts";

  private final IndexingService indexingService;
  private final ZohoIngestionJobStore jobStore;
  private final ServiceIndexMetadataStore metadataStore;
  private final ZohoIngestionProperties zohoIngestionProperties;
  private final ZohoSyncWatermarkStore watermarkStore;
  private final ActiveZohoIndexStore activeZohoIndexStore;
  private final DealAlertMonitor dealAlertMonitor;
  private final ZohoConnectionVault zohoConnectionVault;
  private final CrmSchemaCache schemaCache = new CrmSchemaCache();
  private final ExecutorService executor = Executors.newCachedThreadPool();

  public ZohoIngestionJobService(
      IndexingService indexingService,
      ZohoIngestionJobStore jobStore,
      ServiceIndexMetadataStore metadataStore,
      ZohoIngestionProperties zohoIngestionProperties,
      ZohoSyncWatermarkStore watermarkStore,
      ActiveZohoIndexStore activeZohoIndexStore,
      DealAlertMonitor dealAlertMonitor,
      ZohoConnectionVault zohoConnectionVault) {
    this.indexingService = indexingService;
    this.jobStore = jobStore;
    this.metadataStore = metadataStore;
    this.zohoIngestionProperties = zohoIngestionProperties;
    this.watermarkStore = watermarkStore;
    this.activeZohoIndexStore = activeZohoIndexStore;
    this.dealAlertMonitor = dealAlertMonitor;
    this.zohoConnectionVault = zohoConnectionVault;
  }

  /** Preferred UI path: load OAuth secrets from the server-side vault. */
  public ZohoIngestionJobStore.ZohoIngestionJob enqueueByConnectionId(
      String service,
      String connectionId,
      Integer lookbackDays,
      Double minQualityScore,
      boolean embed,
      String workspaceRoot,
      boolean fullRefresh) {
    DecryptedZohoCredentials creds =
        zohoConnectionVault.requireActiveCredentials(service, connectionId);
    try {
      return enqueueResolved(
          service,
          connectionId,
          creds.clientId(),
          creds.clientSecret(),
          creds.refreshToken(),
          creds.region(),
          lookbackDays,
          minQualityScore,
          embed,
          workspaceRoot,
          fullRefresh);
    } catch (IllegalArgumentException ex) {
      if (ZohoConnectionTestService.looksLikeAuthFailure(ex.getMessage())) {
        zohoConnectionVault.markRevoked(service, connectionId);
        throw new ZohoReauthRequiredException(
            connectionId,
            "Zoho refresh token is invalid or revoked. Exchange a new grant token to reconnect.");
      }
      throw ex;
    }
  }

  public ZohoIngestionJobStore.ZohoIngestionJob enqueue(
      String service,
      String clientId,
      String clientSecret,
      String refreshToken,
      String region,
      Integer lookbackDays,
      Double minQualityScore,
      boolean embed,
      String workspaceRoot) {
    return enqueue(
        service,
        clientId,
        clientSecret,
        refreshToken,
        region,
        lookbackDays,
        minQualityScore,
        embed,
        workspaceRoot,
        false);
  }

  public ZohoIngestionJobStore.ZohoIngestionJob enqueue(
      String service,
      String clientId,
      String clientSecret,
      String refreshToken,
      String region,
      Integer lookbackDays,
      Double minQualityScore,
      boolean embed,
      String workspaceRoot,
      boolean fullRefresh) {
    String effectiveClientId = firstNonBlank(clientId, zohoIngestionProperties.clientId());
    String effectiveClientSecret =
        firstNonBlank(clientSecret, zohoIngestionProperties.clientSecret());
    String effectiveRefreshToken =
        firstNonBlank(refreshToken, zohoIngestionProperties.refreshToken());
    String effectiveRegion = firstNonBlank(region, zohoIngestionProperties.region());
    return enqueueResolved(
        service,
        "",
        effectiveClientId,
        effectiveClientSecret,
        effectiveRefreshToken,
        effectiveRegion,
        lookbackDays,
        minQualityScore,
        embed,
        workspaceRoot,
        fullRefresh);
  }

  private ZohoIngestionJobStore.ZohoIngestionJob enqueueResolved(
      String service,
      String connectionId,
      String clientId,
      String clientSecret,
      String refreshToken,
      String region,
      Integer lookbackDays,
      Double minQualityScore,
      boolean embed,
      String workspaceRoot,
      boolean fullRefresh) {
    if (clientId == null
        || clientId.isBlank()
        || clientSecret == null
        || clientSecret.isBlank()
        || refreshToken == null
        || refreshToken.isBlank()) {
      throw new IllegalArgumentException(
          "Zoho connectionId or clientId/clientSecret/refreshToken are required");
    }
    int lookback =
        lookbackDays == null || lookbackDays <= 0
            ? zohoIngestionProperties.lookbackDays()
            : lookbackDays;
    double quality =
        minQualityScore == null || minQualityScore <= 0
            ? zohoIngestionProperties.minQualityScore()
            : minQualityScore;

    try {
      new ZohoApiClient(
              clientId,
              clientSecret,
              refreshToken,
              ZohoRegion.fromToken(region),
              zohoIngestionProperties.httpRetryPolicy())
          .testConnection();
      if (connectionId != null && !connectionId.isBlank()) {
        zohoConnectionVault.markRefreshed(service, connectionId);
      }
    } catch (Exception ex) {
      if (connectionId != null
          && !connectionId.isBlank()
          && ZohoConnectionTestService.looksLikeAuthFailure(ex.getMessage())) {
        zohoConnectionVault.markRevoked(service, connectionId);
        throw new ZohoReauthRequiredException(
            connectionId,
            "Zoho refresh token is invalid or revoked. Exchange a new grant token to reconnect.");
      }
      throw new IllegalArgumentException(
          ex.getMessage() == null ? "Zoho connection validation failed" : ex.getMessage());
    }

    String jobId = UUID.randomUUID().toString();
    ZohoIngestionJobStore.ZohoIngestionJob job =
        new ZohoIngestionJobStore.ZohoIngestionJob(
            jobId,
            service,
            connectionId == null ? "" : connectionId,
            clientId,
            clientSecret,
            refreshToken,
            region,
            lookback,
            quality,
            embed,
            workspaceRoot,
            fullRefresh,
            "queued",
            null,
            null);
    jobStore.put(job);
    executor.submit(() -> runJob(job));
    return job;
  }

  public ZohoIngestionJobStore.ZohoIngestionJob getJob(String jobId) {
    return jobStore.get(jobId);
  }

  private void runJob(ZohoIngestionJobStore.ZohoIngestionJob job) {
    jobStore.updateStatus(job.jobId(), "running", null, null);
    String clientId = job.clientId();
    String clientSecret = job.clientSecret();
    String refreshToken = job.refreshToken();
    String region = job.region();
    String connectionId = job.connectionId();
    if ((clientId == null || clientId.isBlank() || refreshToken == null || refreshToken.isBlank())
        && connectionId != null
        && !connectionId.isBlank()) {
      DecryptedZohoCredentials creds =
          zohoConnectionVault.requireActiveCredentials(job.service(), connectionId);
      clientId = creds.clientId();
      clientSecret = creds.clientSecret();
      refreshToken = creds.refreshToken();
      region = creds.region();
    }
    jobStore.clearSecrets(job.jobId());

    String connectionKey = ActiveZohoIndexStore.connectionKey(clientId, refreshToken, region);
    boolean newZohoIndex = activeZohoIndexStore.activate(job.service(), connectionKey, region);
    Path indexPath =
        ServiceIndexPathResolver.resolveZohoConnectionIndexPath(job.service(), connectionKey);
    IndexPathHolder.setDirectIndexPath(indexPath);
    try {
      Path workspace =
          job.workspaceRoot() == null || job.workspaceRoot().isBlank()
              ? Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize()
              : Path.of(job.workspaceRoot()).toAbsolutePath().normalize();
      Path workspaceRoot = IndexPathResolver.resolveWorkspaceRoot(workspace);

      String orgId = job.service() + ":" + region + ":" + connectionKey;
      SyncMode syncMode = resolveSyncMode(orgId, job.fullRefresh() || newZohoIndex);

      ZohoApiClient apiClient =
          new ZohoApiClient(
              clientId,
              clientSecret,
              refreshToken,
              ZohoRegion.fromToken(region),
              zohoIngestionProperties.httpRetryPolicy());
      ZohoSchemaAdapter schemaAdapter = new ZohoSchemaAdapter(apiClient);
      ZohoIngestionService ingestionService =
          new ZohoIngestionService(
              apiClient,
              schemaAdapter,
              schemaCache,
              new CrmConnection(orgId, CrmConnection.PROVIDER_ZOHO, Map.of()));
      ZohoIngestionService.IngestResult ingestResult =
          ingestionService.ingest(
              new ZohoIngestionOptions(
                  job.lookbackDays(),
                  job.minQualityScore(),
                  zohoIngestionProperties.sandboxDemoSignaturesEnabled(),
                  zohoIngestionProperties.sandboxDemoAccountNames(),
                  syncMode.modifiedSince(),
                  syncMode.fullSync()));

      if (connectionId != null && !connectionId.isBlank()) {
        zohoConnectionVault.markRefreshed(job.service(), connectionId);
      }

      List<String> deletedChunkIds = new ArrayList<>();
      for (String dealId : ingestResult.deletedDealIds()) {
        deletedChunkIds.add(ChunkIdGenerator.generateZohoDealContextId(dealId));
      }

      boolean hasWork =
          !ingestResult.chunks().isEmpty()
              || syncMode.fullSync()
              || !deletedChunkIds.isEmpty();
      if (!hasWork) {
        watermarkStore.updateWatermark(orgId, MODULE_DEALS, ingestResult.syncStart(), 0);
        Map<String, Object> emptyStats =
            toMap(
                new ZohoHistoryIndexingStats(0, 0, 0, 0, Instant.now(), Instant.now()),
                ingestResult,
                indexPath,
                region,
                job.minQualityScore(),
                ingestResult.syncStart(),
                connectionKey,
                newZohoIndex);
        jobStore.updateStatus(job.jobId(), "completed", emptyStats, null);
        try {
          mergeMetadata(job.service(), emptyStats);
        } catch (RuntimeException metaEx) {
          log.warn(
              "Zoho job {} completed empty but metadata merge failed: {}",
              job.jobId(),
              metaEx.getMessage());
        }
        runDealAlerts(job.service());
        return;
      }

      if (ingestResult.chunks().isEmpty()
          && syncMode.fullSync()
          && deletedChunkIds.isEmpty()) {
        throw new IllegalStateException(
            "No Zoho deal contexts passed quality filter. Lower minQualityScore or improve CRM data.");
      }

      log.info(
          "Zoho ingest ready for indexing: jobId={} syncType={} chunks={} deleted={} embed={}",
          job.jobId(),
          ingestResult.syncType(),
          ingestResult.chunks().size(),
          deletedChunkIds.size(),
          job.embed());
      ZohoHistoryIndexingStats stats =
          indexingService.indexZohoHistory(
              new ZohoHistoryIndexingRequest(
                  ingestResult.chunks(),
                  job.embed(),
                  false,
                  workspaceRoot,
                  syncMode.fullSync(),
                  deletedChunkIds));

      Instant watermark = ingestResult.syncStart();
      watermarkStore.updateWatermark(orgId, MODULE_DEALS, watermark, ingestResult.dealsFetched());
      watermarkStore.updateWatermark(
          orgId, MODULE_ACCOUNTS, watermark, ingestResult.accountsFetched());
      watermarkStore.updateWatermark(
          orgId, MODULE_CONTACTS, watermark, ingestResult.contactsFetched());

      Map<String, Object> statsMap =
          toMap(
              stats,
              ingestResult,
              indexPath,
              region,
              job.minQualityScore(),
              watermark,
              connectionKey,
              newZohoIndex);
      jobStore.updateStatus(job.jobId(), "completed", statsMap, null);
      try {
        mergeMetadata(job.service(), statsMap);
      } catch (RuntimeException metaEx) {
        log.warn(
            "Zoho job {} indexed but metadata merge failed: {}",
            job.jobId(),
            metaEx.getMessage());
      }
      runDealAlerts(job.service());
      log.info(
          "Indexed Zoho CRM for service {} into {} ({} chunks, syncType={})",
          job.service(),
          indexPath,
          stats.chunksIndexed(),
          ingestResult.syncType());
    } catch (Throwable ex) {
      if (connectionId != null
          && !connectionId.isBlank()
          && ZohoConnectionTestService.looksLikeAuthFailure(ex.getMessage())) {
        zohoConnectionVault.markRevoked(job.service(), connectionId);
      }
      log.warn(
          "Zoho ingestion failed for {}: {}",
          job.service(),
          ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(),
          ex);
      jobStore.updateStatus(
          job.jobId(),
          "failed",
          null,
          ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
    } finally {
      IndexPathHolder.clear();
    }
  }

  private SyncMode resolveSyncMode(String orgId, boolean requestedFullRefresh) {
    if (requestedFullRefresh) {
      log.info("Zoho fullRefresh requested for {}", orgId);
      return SyncMode.full();
    }
    Optional<Instant> watermark = watermarkStore.getWatermark(orgId, MODULE_DEALS);
    if (watermark.isEmpty()) {
      log.info("Zoho full sync for {} — no watermark found", orgId);
      return SyncMode.full();
    }
    Instant since = watermark.get();
    if (since.isBefore(Instant.now().minus(DELETED_RECORDS_RETENTION))) {
      log.warn(
          "Zoho watermark for {} is older than 30 days ({}) — Deleted Records API cannot cover the"
              + " gap; forcing full refresh",
          orgId,
          since);
      return SyncMode.full();
    }
    return SyncMode.incremental(since);
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

  private void runDealAlerts(String service) {
    try {
      DealAlertMonitor.MonitorResult result = dealAlertMonitor.evaluateAfterZohoReindex(service);
      if ("posted".equals(result.status())) {
        log.info(
            "Pipeline alerts posted for {}: {} event(s)",
            service,
            result.events().size());
      } else if ("events_not_delivered".equals(result.status())) {
        log.info(
            "Pipeline alerts for {} had {} event(s) but were not delivered: {}",
            service,
            result.events().size(),
            result.message());
      }
    } catch (RuntimeException ex) {
      log.warn(
          "Pipeline alert monitor failed for {}: {}",
          service,
          ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
    }
  }

  private static Map<String, Object> toMap(
      ZohoHistoryIndexingStats stats,
      ZohoIngestionService.IngestResult ingestResult,
      Path indexPath,
      String region,
      double minQualityScore,
      Instant watermark,
      String connectionKey,
      boolean newZohoIndex) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("dealsFetched", ingestResult.dealsFetched());
    map.put("dealsIndexed", ingestResult.dealsIndexed());
    map.put("dealsSkipped", ingestResult.dealsSkipped());
    map.put("dealsUpdated", ingestResult.dealsIndexed());
    map.put("dealsDeleted", ingestResult.dealsDeleted());
    map.put("accountsUpdated", ingestResult.accountsFetched());
    map.put("contactsUpdated", ingestResult.contactsFetched());
    map.put("syncType", ingestResult.syncType());
    map.put("watermark", watermark != null ? watermark.toString() : null);
    map.put("watermarkUsed",
        ingestResult.watermarkUsed() != null ? ingestResult.watermarkUsed().toString() : null);
    map.put("chunksIndexed", stats.chunksIndexed());
    map.put("chunksEmbedded", stats.chunksEmbedded());
    map.put("embeddingApiCalls", stats.embeddingApiCalls());
    map.put("startedAt", stats.startedAt() != null ? stats.startedAt().toString() : null);
    map.put("finishedAt", stats.finishedAt() != null ? stats.finishedAt().toString() : null);
    map.put("sourceType", "zoho-history");
    map.put("indexPath", indexPath.toString());
    map.put("region", region);
    map.put("minQualityScore", minQualityScore);
    map.put("modulesDiscovered", List.copyOf(ingestResult.schema().keySet()));
    map.put("dataGapReport", CrmDataGapReport.toMaps(ingestResult.dataGapReport()));
    map.put("zohoConnectionKey", connectionKey);
    map.put("newZohoIndex", newZohoIndex);
    return map;
  }

  private static String firstNonBlank(String primary, String fallback) {
    if (primary != null && !primary.isBlank()) {
      return primary.trim();
    }
    return fallback == null ? "" : fallback.trim();
  }

  private record SyncMode(boolean fullSync, Instant modifiedSince) {
    static SyncMode full() {
      return new SyncMode(true, null);
    }

    static SyncMode incremental(Instant modifiedSince) {
      return new SyncMode(false, modifiedSince);
    }
  }
}
