package com.andromedia.core.index;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class JiraIngestionJobStore {

  private final ConcurrentHashMap<String, JiraIngestionJob> jobs = new ConcurrentHashMap<>();

  public void put(JiraIngestionJob job) {
    jobs.put(job.jobId(), job);
  }

  public JiraIngestionJob get(String jobId) {
    return jobs.get(jobId);
  }

  public void updateStatus(String jobId, String status, Map<String, Object> stats, String error) {
    jobs.computeIfPresent(
        jobId,
        (id, existing) ->
            new JiraIngestionJob(
                existing.jobId(),
                existing.service(),
                existing.cloudId(),
                existing.accessToken(),
                existing.projectKeys(),
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

  public record JiraIngestionJob(
      String jobId,
      String service,
      String cloudId,
      String accessToken,
      List<String> projectKeys,
      int lookbackDays,
      boolean embed,
      String workspaceRoot,
      String status,
      Map<String, Object> stats,
      String error,
      Instant startedAt,
      Instant finishedAt) {

    public JiraIngestionJob {
      projectKeys = projectKeys == null ? List.of() : List.copyOf(projectKeys);
    }

    public JiraIngestionJob(
        String jobId,
        String service,
        String cloudId,
        String accessToken,
        List<String> projectKeys,
        int lookbackDays,
        boolean embed,
        String workspaceRoot,
        String status,
        Map<String, Object> stats,
        String error) {
      this(
          jobId,
          service,
          cloudId,
          accessToken,
          projectKeys,
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
