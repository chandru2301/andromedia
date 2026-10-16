package com.andromedia.core.index;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class SlackIngestionJobStore {

  private final ConcurrentHashMap<String, SlackIngestionJob> jobs = new ConcurrentHashMap<>();

  public void put(SlackIngestionJob job) {
    jobs.put(job.jobId(), job);
  }

  public SlackIngestionJob get(String jobId) {
    return jobs.get(jobId);
  }

  public void updateStatus(
      String jobId, String status, Map<String, Object> stats, String error) {
    jobs.computeIfPresent(
        jobId,
        (id, existing) ->
            new SlackIngestionJob(
                existing.jobId(),
                existing.service(),
                existing.botToken(),
                existing.channels(),
                existing.embed(),
                existing.lookbackDays(),
                existing.workspaceRoot(),
                status,
                stats,
                error,
                existing.startedAt(),
                "failed".equals(status) || "completed".equals(status)
                    ? Instant.now()
                    : existing.finishedAt()));
  }

  public record SlackIngestionJob(
      String jobId,
      String service,
      String botToken,
      List<String> channels,
      boolean embed,
      int lookbackDays,
      String workspaceRoot,
      String status,
      Map<String, Object> stats,
      String error,
      Instant startedAt,
      Instant finishedAt) {

    public SlackIngestionJob {
      channels = channels == null ? List.of() : List.copyOf(channels);
    }

    public SlackIngestionJob(
        String jobId,
        String service,
        String botToken,
        List<String> channels,
        boolean embed,
        int lookbackDays,
        String workspaceRoot,
        String status,
        Map<String, Object> stats,
        String error) {
      this(
          jobId,
          service,
          botToken,
          channels,
          embed,
          lookbackDays,
          workspaceRoot,
          status,
          stats,
          error,
          Instant.now(),
          null);
    }
  }
}
