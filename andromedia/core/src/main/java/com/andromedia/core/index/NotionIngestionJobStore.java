package com.andromedia.core.index;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class NotionIngestionJobStore {

  private final ConcurrentHashMap<String, NotionIngestionJob> jobs = new ConcurrentHashMap<>();

  public void put(NotionIngestionJob job) {
    jobs.put(job.jobId(), job);
  }

  public NotionIngestionJob get(String jobId) {
    return jobs.get(jobId);
  }

  public void updateStatus(String jobId, String status, Map<String, Object> stats, String error) {
    jobs.computeIfPresent(
        jobId,
        (id, existing) ->
            new NotionIngestionJob(
                existing.jobId(),
                existing.service(),
                existing.apiKey(),
                existing.pageIds(),
                existing.databaseIds(),
                existing.embed(),
                existing.workspaceRoot(),
                status,
                stats,
                error,
                existing.startedAt(),
                "failed".equals(status) || "completed".equals(status)
                    ? Instant.now()
                    : existing.finishedAt()));
  }

  public record NotionIngestionJob(
      String jobId,
      String service,
      String apiKey,
      List<String> pageIds,
      List<String> databaseIds,
      boolean embed,
      String workspaceRoot,
      String status,
      Map<String, Object> stats,
      String error,
      Instant startedAt,
      Instant finishedAt) {

    public NotionIngestionJob {
      pageIds = pageIds == null ? List.of() : List.copyOf(pageIds);
      databaseIds = databaseIds == null ? List.of() : List.copyOf(databaseIds);
    }

    public NotionIngestionJob(
        String jobId,
        String service,
        String apiKey,
        List<String> pageIds,
        List<String> databaseIds,
        boolean embed,
        String workspaceRoot,
        String status,
        Map<String, Object> stats,
        String error) {
      this(
          jobId,
          service,
          apiKey,
          pageIds,
          databaseIds,
          embed,
          workspaceRoot,
          status,
          stats,
          error,
          Instant.now(),
          null);
    }
  }
}
