package com.andromedia.core.index;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class GoogleChatIngestionJobStore {

  private final ConcurrentHashMap<String, GoogleChatIngestionJob> jobs = new ConcurrentHashMap<>();

  public void put(GoogleChatIngestionJob job) {
    jobs.put(job.jobId(), job);
  }

  public GoogleChatIngestionJob get(String jobId) {
    return jobs.get(jobId);
  }

  public void updateStatus(String jobId, String status, Map<String, Object> stats, String error) {
    jobs.computeIfPresent(
        jobId,
        (id, existing) ->
            new GoogleChatIngestionJob(
                existing.jobId(),
                existing.service(),
                existing.accessToken(),
                existing.spaces(),
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

  public record GoogleChatIngestionJob(
      String jobId,
      String service,
      String accessToken,
      List<String> spaces,
      boolean embed,
      int lookbackDays,
      String workspaceRoot,
      String status,
      Map<String, Object> stats,
      String error,
      Instant startedAt,
      Instant finishedAt) {

    public GoogleChatIngestionJob {
      spaces = spaces == null ? List.of() : List.copyOf(spaces);
    }

    public GoogleChatIngestionJob(
        String jobId,
        String service,
        String accessToken,
        List<String> spaces,
        boolean embed,
        int lookbackDays,
        String workspaceRoot,
        String status,
        Map<String, Object> stats,
        String error) {
      this(
          jobId,
          service,
          accessToken,
          spaces,
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
