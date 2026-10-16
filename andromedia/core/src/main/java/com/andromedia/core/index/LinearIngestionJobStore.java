package com.andromedia.core.index;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class LinearIngestionJobStore {

  private final ConcurrentHashMap<String, LinearIngestionJob> jobs = new ConcurrentHashMap<>();

  public void put(LinearIngestionJob job) {
    jobs.put(job.jobId(), job);
  }

  public LinearIngestionJob get(String jobId) {
    return jobs.get(jobId);
  }

  public void updateStatus(String jobId, String status, Map<String, Object> stats, String error) {
    jobs.computeIfPresent(
        jobId,
        (id, existing) ->
            new LinearIngestionJob(
                existing.jobId(),
                existing.service(),
                existing.apiKey(),
                existing.teamKeys(),
                existing.lookbackDays(),
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

  public record LinearIngestionJob(
      String jobId,
      String service,
      String apiKey,
      List<String> teamKeys,
      int lookbackDays,
      boolean embed,
      String workspaceRoot,
      String status,
      Map<String, Object> stats,
      String error,
      Instant startedAt,
      Instant finishedAt) {

    public LinearIngestionJob {
      teamKeys = teamKeys == null ? List.of() : List.copyOf(teamKeys);
    }

    public LinearIngestionJob(
        String jobId,
        String service,
        String apiKey,
        List<String> teamKeys,
        int lookbackDays,
        boolean embed,
        String workspaceRoot,
        String status,
        Map<String, Object> stats,
        String error) {
      this(
          jobId,
          service,
          apiKey,
          teamKeys,
          lookbackDays,
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
