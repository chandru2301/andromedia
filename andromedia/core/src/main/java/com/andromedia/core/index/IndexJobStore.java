package com.andromedia.core.index;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class IndexJobStore {

  private final ConcurrentHashMap<String, IndexJob> jobs = new ConcurrentHashMap<>();

  public void put(IndexJob job) {
    jobs.put(job.jobId(), job);
  }

  public IndexJob get(String jobId) {
    return jobs.get(jobId);
  }

  public void updateStatus(
      String jobId, String status, Map<String, Object> stats, String error) {
    jobs.computeIfPresent(
        jobId,
        (id, existing) ->
            new IndexJob(
                existing.jobId(),
                existing.service(),
                existing.repo(),
                existing.branch(),
                existing.repoSubpath(),
                existing.localPath(),
                existing.embed(),
                status,
                stats,
                error,
                existing.startedAt(),
                "failed".equals(status) || "completed".equals(status)
                    ? Instant.now()
                    : existing.finishedAt()));
  }

  public record IndexJob(
      String jobId,
      String service,
      String repo,
      String branch,
      String repoSubpath,
      String localPath,
      boolean embed,
      String status,
      Map<String, Object> stats,
      String error,
      Instant startedAt,
      Instant finishedAt) {

    public IndexJob(
        String jobId,
        String service,
        String repo,
        String branch,
        String repoSubpath,
        String localPath,
        boolean embed,
        String status,
        Map<String, Object> stats,
        String error) {
      this(
          jobId,
          service,
          repo,
          branch,
          repoSubpath,
          localPath,
          embed,
          status,
          stats,
          error,
          Instant.now(),
          null);
    }
  }
}
