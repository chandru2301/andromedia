package com.andromedia.core.index;

import com.andromedia.common.IndexConstants;
import com.andromedia.common.IndexPathHolder;
import com.andromedia.common.ServiceIndexPathResolver;
import com.andromedia.indexing.IndexingRequest;
import com.andromedia.indexing.IndexingService;
import com.andromedia.indexing.IndexingStats;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class IndexJobService {

  private static final Logger log = LoggerFactory.getLogger(IndexJobService.class);

  private final GitCloneService gitCloneService;
  private final IndexingService indexingService;
  private final IndexJobStore jobStore;
  private final ServiceIndexMetadataStore metadataStore;
  private final ExecutorService executor = Executors.newCachedThreadPool();

  public IndexJobService(
      GitCloneService gitCloneService,
      IndexingService indexingService,
      IndexJobStore jobStore,
      ServiceIndexMetadataStore metadataStore) {
    this.gitCloneService = gitCloneService;
    this.indexingService = indexingService;
    this.jobStore = jobStore;
    this.metadataStore = metadataStore;
  }

  public IndexJobStore.IndexJob enqueueGit(
      String service, String repo, String branch, String repoSubpath, boolean embed) {
    String jobId = UUID.randomUUID().toString();
    IndexJobStore.IndexJob job =
        new IndexJobStore.IndexJob(
            jobId, service, repo, branch, repoSubpath, null, embed, "queued", null, null);
    jobStore.put(job);
    executor.submit(() -> runJob(job));
    return job;
  }

  public IndexJobStore.IndexJob enqueueLocal(String service, String localPath, boolean embed) {
    String jobId = UUID.randomUUID().toString();
    IndexJobStore.IndexJob job =
        new IndexJobStore.IndexJob(
            jobId, service, null, null, null, localPath, embed, "queued", null, null);
    jobStore.put(job);
    executor.submit(() -> runJob(job));
    return job;
  }

  public IndexJobStore.IndexJob getJob(String jobId) {
    return jobStore.get(jobId);
  }

  private void runJob(IndexJobStore.IndexJob job) {
    jobStore.updateStatus(job.jobId(), "running", null, null);
    Path indexPath = ServiceIndexPathResolver.resolveIndexPath(job.service());
    IndexPathHolder.setDirectIndexPath(indexPath);
    try {
      Path sourcePath;
      String sourceType;
      String sourcePathLabel;
      if (job.localPath() != null && !job.localPath().isBlank()) {
        SourcePathResolver.validateLocalPath(job.localPath());
        sourcePath = Path.of(job.localPath()).toAbsolutePath().normalize();
        sourceType = "local";
        sourcePathLabel = sourcePath.toString();
      } else {
        Path repoPath = gitCloneService.cloneOrPull(job.service(), job.repo(), job.branch());
        sourcePath = SourcePathResolver.resolveSubpath(repoPath, job.repoSubpath());
        sourceType = "git";
        sourcePathLabel = sourcePath.toString();
      }
      IndexingStats stats =
          indexingService.indexPath(
              new IndexingRequest(
                  sourcePath, IndexConstants.DEFAULT_EXTENSIONS, true, false, job.embed()));
      if (stats.filesIndexed() == 0) {
        throw new IllegalStateException("No files indexed under " + sourcePath);
      }
      Map<String, Object> statsMap =
          toMap(stats, sourceType, sourcePathLabel, indexPath, job.repoSubpath());
      jobStore.updateStatus(job.jobId(), "completed", statsMap, null);
      metadataStore.save(job.service(), statsMap);
      log.info("Indexed service {} into {}", job.service(), indexPath);
    } catch (Exception ex) {
      log.warn("Index job {} failed for service {}: {}", job.jobId(), job.service(), ex.getMessage());
      jobStore.updateStatus(job.jobId(), "failed", null, ex.getMessage());
    } finally {
      IndexPathHolder.clear();
    }
  }

  private static Map<String, Object> toMap(
      IndexingStats stats,
      String sourceType,
      String sourcePath,
      Path indexPath,
      String repoSubpath) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("filesVisited", stats.filesVisited());
    map.put("filesIndexed", stats.filesIndexed());
    map.put("chunksIndexed", stats.chunksIndexed());
    map.put("chunksEmbedded", stats.chunksEmbedded());
    map.put("embeddingApiCalls", stats.embeddingApiCalls());
    map.put("bytesIndexed", stats.bytesIndexed());
    map.put("symbolsExtracted", stats.symbolsExtracted());
    map.put("startedAt", stats.startedAt() != null ? stats.startedAt().toString() : null);
    map.put("finishedAt", stats.finishedAt() != null ? stats.finishedAt().toString() : null);
    map.put("sourceType", sourceType);
    map.put("sourcePath", sourcePath);
    map.put("indexPath", indexPath.toString());
    if (repoSubpath != null && !repoSubpath.isBlank()) {
      map.put("repoSubpath", repoSubpath);
    }
    return map;
  }
}
