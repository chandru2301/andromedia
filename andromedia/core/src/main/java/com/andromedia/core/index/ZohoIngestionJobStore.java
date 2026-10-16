package com.andromedia.core.index;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class ZohoIngestionJobStore {

  private final ConcurrentHashMap<String, ZohoIngestionJob> jobs = new ConcurrentHashMap<>();

  public void put(ZohoIngestionJob job) {
    jobs.put(job.jobId(), job);
  }

  public ZohoIngestionJob get(String jobId) {
    return jobs.get(jobId);
  }

  public void updateStatus(String jobId, String status, Map<String, Object> stats, String error) {
    jobs.computeIfPresent(
        jobId,
        (id, existing) ->
            new ZohoIngestionJob(
                existing.jobId(),
                existing.service(),
                existing.connectionId(),
                existing.clientId(),
                existing.clientSecret(),
                existing.refreshToken(),
                existing.region(),
                existing.lookbackDays(),
                existing.minQualityScore(),
                existing.embed(),
                existing.workspaceRoot(),
                existing.fullRefresh(),
                status,
                stats,
                error,
                existing.startedAt(),
                "failed".equals(status) || "completed".equals(status)
                    ? Instant.now()
                    : existing.finishedAt()));
  }

  /**
   * Clears plaintext OAuth secrets from an in-memory job after credentials have been loaded for the
   * run (connectionId remains for audit).
   */
  public void clearSecrets(String jobId) {
    jobs.computeIfPresent(
        jobId,
        (id, existing) ->
            new ZohoIngestionJob(
                existing.jobId(),
                existing.service(),
                existing.connectionId(),
                "",
                "",
                "",
                existing.region(),
                existing.lookbackDays(),
                existing.minQualityScore(),
                existing.embed(),
                existing.workspaceRoot(),
                existing.fullRefresh(),
                existing.status(),
                existing.stats(),
                existing.error(),
                existing.startedAt(),
                existing.finishedAt()));
  }

  public record ZohoIngestionJob(
      String jobId,
      String service,
      String connectionId,
      String clientId,
      String clientSecret,
      String refreshToken,
      String region,
      int lookbackDays,
      double minQualityScore,
      boolean embed,
      String workspaceRoot,
      boolean fullRefresh,
      String status,
      Map<String, Object> stats,
      String error,
      Instant startedAt,
      Instant finishedAt) {

    public ZohoIngestionJob {
      connectionId = connectionId == null ? "" : connectionId.trim();
      clientId = clientId == null ? "" : clientId;
      clientSecret = clientSecret == null ? "" : clientSecret;
      refreshToken = refreshToken == null ? "" : refreshToken;
    }

    public ZohoIngestionJob(
        String jobId,
        String service,
        String connectionId,
        String clientId,
        String clientSecret,
        String refreshToken,
        String region,
        int lookbackDays,
        double minQualityScore,
        boolean embed,
        String workspaceRoot,
        boolean fullRefresh,
        String status,
        Map<String, Object> stats,
        String error) {
      this(
          jobId,
          service,
          connectionId,
          clientId,
          clientSecret,
          refreshToken,
          region,
          lookbackDays,
          minQualityScore,
          embed,
          workspaceRoot,
          fullRefresh,
          status,
          stats,
          error,
          Instant.now(),
          null);
    }
  }
}
