package com.andromedia.core.index;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class GitHistoryJobStore {

  private final ConcurrentHashMap<String, GitHistoryJob> jobs = new ConcurrentHashMap<>();

  public void put(GitHistoryJob job) {
    jobs.put(job.jobId(), job);
  }

  public GitHistoryJob get(String jobId) {
    return jobs.get(jobId);
  }

  public void updateStatus(
      String jobId, String status, Map<String, Object> stats, String error) {
    jobs.computeIfPresent(
        jobId,
        (id, existing) ->
            new GitHistoryJob(
                existing.jobId(),
                existing.service(),
                existing.localPath(),
                existing.embed(),
                existing.maxCommits(),
                existing.since(),
                status,
                stats,
                error,
                existing.startedAt(),
                "failed".equals(status) || "completed".equals(status)
                    ? Instant.now()
                    : existing.finishedAt()));
  }

  public record GitHistoryJob(
      String jobId,
      String service,
      String localPath,
      boolean embed,
      int maxCommits,
      String since,
      String status,
      Map<String, Object> stats,
      String error,
      Instant startedAt,
      Instant finishedAt) {

    public GitHistoryJob(
        String jobId,
        String service,
        String localPath,
        boolean embed,
        int maxCommits,
        String since,
        String status,
        Map<String, Object> stats,
        String error) {
      this(
          jobId,
          service,
          localPath,
          embed,
          maxCommits,
          since,
          status,
          stats,
          error,
          Instant.now(),
          null);
    }
  }
}
