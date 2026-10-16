package com.andromedia.core.index;

import com.andromedia.common.IndexPathHolder;
import com.andromedia.common.ServiceIndexPathResolver;
import com.andromedia.ingestion.git.GitIngestionOptions;
import com.andromedia.ingestion.git.GitIngestionService;
import com.andromedia.indexing.GitHistoryIndexingOrchestrator;
import com.andromedia.indexing.GitHistoryIndexingStats;
import com.andromedia.indexing.IndexingService;
import java.nio.file.Path;
import java.time.Instant;
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
public class GitHistoryJobService {

  private static final Logger log = LoggerFactory.getLogger(GitHistoryJobService.class);

  private final GitHistoryIndexingOrchestrator orchestrator;
  private final GitHistoryJobStore jobStore;
  private final ServiceIndexMetadataStore metadataStore;
  private final GitIngestionProperties gitIngestionProperties;
  private final ExecutorService executor = Executors.newCachedThreadPool();

  public GitHistoryJobService(
      GitHistoryIndexingOrchestrator orchestrator,
      GitHistoryJobStore jobStore,
      ServiceIndexMetadataStore metadataStore,
      GitIngestionProperties gitIngestionProperties) {
    this.orchestrator = orchestrator;
    this.jobStore = jobStore;
    this.metadataStore = metadataStore;
    this.gitIngestionProperties = gitIngestionProperties;
  }

  public GitHistoryJobStore.GitHistoryJob enqueueLocalHistory(
      String service, String localPath, boolean embed, Integer maxCommits, String since) {
    Path repoPath = Path.of(localPath).toAbsolutePath().normalize();
    if (!GitIngestionService.isGitRepository(repoPath)) {
      throw new IllegalArgumentException("Not a git repository: " + repoPath);
    }

    String jobId = UUID.randomUUID().toString();
    int effectiveMaxCommits =
        maxCommits == null || maxCommits <= 0
            ? gitIngestionProperties.maxCommits()
            : maxCommits;
    GitHistoryJobStore.GitHistoryJob job =
        new GitHistoryJobStore.GitHistoryJob(
            jobId,
            service,
            repoPath.toString(),
            embed,
            effectiveMaxCommits,
            since,
            "queued",
            null,
            null);
    jobStore.put(job);
    executor.submit(() -> runJob(job));
    return job;
  }

  public GitHistoryJobStore.GitHistoryJob getJob(String jobId) {
    return jobStore.get(jobId);
  }

  private void runJob(GitHistoryJobStore.GitHistoryJob job) {
    jobStore.updateStatus(job.jobId(), "running", null, null);
    Path indexPath = ServiceIndexPathResolver.resolveIndexPath(job.service());
    IndexPathHolder.setDirectIndexPath(indexPath);
    try {
      Path repoPath = Path.of(job.localPath()).toAbsolutePath().normalize();
      Instant sinceInstant = parseSince(job.since());
      String stopAtCommitSha = resolveStopAtCommitSha(job.service());

      GitIngestionOptions options =
          new GitIngestionOptions(sinceInstant, job.maxCommits(), null, stopAtCommitSha);
      GitHistoryIndexingOrchestrator.GitHistoryIndexingResult result =
          orchestrator.indexRepository(repoPath, options, job.embed(), false);
      if (result.events().isEmpty()) {
        throw new IllegalStateException("No git history events found under " + repoPath);
      }

      String latestCommitSha = result.latestCommitSha();
      Map<String, Object> statsMap =
          toMap(result.stats(), repoPath, indexPath, latestCommitSha);
      jobStore.updateStatus(job.jobId(), "completed", statsMap, null);
      metadataStore.save(job.service(), statsMap);
      log.info("Indexed git history for service {} into {}", job.service(), indexPath);
    } catch (Exception ex) {
      log.warn(
          "Git history job {} failed for service {}: {}",
          job.jobId(),
          job.service(),
          ex.getMessage());
      jobStore.updateStatus(job.jobId(), "failed", null, ex.getMessage());
    } finally {
      IndexPathHolder.clear();
    }
  }

  private String resolveStopAtCommitSha(String service) {
    ServiceIndexMetadataStore.ServiceIndexMetadata metadata = metadataStore.get(service);
    if (metadata == null || metadata.stats() == null) {
      return null;
    }
    Object value = metadata.stats().get("lastIndexedCommitSha");
    return value == null ? null : value.toString();
  }

  private static Instant parseSince(String since) {
    if (since == null || since.isBlank()) {
      return null;
    }
    return Instant.parse(since.trim());
  }

  private static Map<String, Object> toMap(
      GitHistoryIndexingStats stats, Path sourcePath, Path indexPath, String latestCommitSha) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("commitsIndexed", stats.commitsIndexed());
    map.put("chunksIndexed", stats.chunksIndexed());
    map.put("chunksEmbedded", stats.chunksEmbedded());
    map.put("embeddingApiCalls", stats.embeddingApiCalls());
    map.put("symbolsExtracted", stats.symbolsExtracted());
    map.put("startedAt", stats.startedAt() != null ? stats.startedAt().toString() : null);
    map.put("finishedAt", stats.finishedAt() != null ? stats.finishedAt().toString() : null);
    map.put("sourceType", "git-history");
    map.put("sourcePath", sourcePath.toString());
    map.put("indexPath", indexPath.toString());
    map.put("lastIndexedCommitSha", latestCommitSha);
    return map;
  }
}
