package com.andromedia.core.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.andromedia.common.GitChangeEvent;
import com.andromedia.common.GitHistoryCursorStore;
import com.andromedia.indexing.GitHistoryIndexingOrchestrator;
import com.andromedia.indexing.GitHistoryIndexingStats;
import com.andromedia.indexing.IndexingService;
import com.andromedia.ingestion.git.GitIngestionOptions;
import com.andromedia.ingestion.git.GitIngestionService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitHistoryJobServiceTest {

  @TempDir Path tempDir;

  @Test
  void rejectsNonGitDirectoryBeforeEnqueue() {
    GitHistoryJobService service =
        new GitHistoryJobService(
            new GitHistoryIndexingOrchestrator(
                new IndexingServiceStub(), new GitHistoryCursorStore()),
            new GitHistoryJobStore(),
            new ServiceIndexMetadataStore(),
            new GitIngestionProperties());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.enqueueLocalHistory(
                "demo", tempDir.toString(), false, 10, null));
  }

  @Test
  void runsHistoryJobAgainstLocalRepository() throws Exception {
    Path repoDir = tempDir.resolve("repo");
    Files.createDirectories(repoDir);
    try (Git git = Git.init().setDirectory(repoDir.toFile()).call()) {
      Files.writeString(repoDir.resolve("README.md"), "hello");
      git.add().addFilepattern(".").call();
      git.commit().setMessage("bootstrap").call();
    }

    IndexingServiceStub indexingService = new IndexingServiceStub();
    GitHistoryJobStore jobStore = new GitHistoryJobStore();
    GitHistoryIndexingOrchestrator orchestrator =
        new GitHistoryIndexingOrchestrator(
            new GitIngestionService(List.of()),
            indexingService,
            new GitHistoryCursorStore());
    GitHistoryJobService service =
        new GitHistoryJobService(
            orchestrator,
            jobStore,
            new ServiceIndexMetadataStore(),
            new GitIngestionProperties());

    GitHistoryJobStore.GitHistoryJob job =
        service.enqueueLocalHistory("demo", repoDir.toString(), false, 5, null);

    awaitCompletion(jobStore, job.jobId());
    GitHistoryJobStore.GitHistoryJob completed = jobStore.get(job.jobId());
    assertEquals("completed", completed.status());
    assertEquals(1, indexingService.lastStats().commitsIndexed());
  }

  private static void awaitCompletion(GitHistoryJobStore jobStore, String jobId)
      throws InterruptedException {
    for (int attempt = 0; attempt < 100; attempt++) {
      GitHistoryJobStore.GitHistoryJob job = jobStore.get(jobId);
      if (job != null && ("completed".equals(job.status()) || "failed".equals(job.status()))) {
        return;
      }
      Thread.sleep(50);
    }
    throw new IllegalStateException("Job did not complete in time");
  }

  private static final class IndexingServiceStub implements IndexingService {
    private GitHistoryIndexingStats lastStats;

    @Override
    public com.andromedia.indexing.IndexingStats indexPath(
        com.andromedia.indexing.IndexingRequest request) {
      throw new UnsupportedOperationException();
    }

    @Override
    public GitHistoryIndexingStats indexGitHistory(
        com.andromedia.indexing.GitHistoryIndexingRequest request) {
      lastStats =
          new GitHistoryIndexingStats(
              request.chunks().size(),
              request.chunks().size(),
              0,
              0,
              0,
              Instant.now(),
              Instant.now());
      return lastStats;
    }

    @Override
    public com.andromedia.indexing.SlackHistoryIndexingStats indexSlackHistory(
        com.andromedia.indexing.SlackHistoryIndexingRequest request) {
      throw new UnsupportedOperationException();
    }

    @Override
    public com.andromedia.indexing.NotionHistoryIndexingStats indexNotionHistory(
        com.andromedia.indexing.NotionHistoryIndexingRequest request) {
      throw new UnsupportedOperationException();
    }

    @Override
    public com.andromedia.indexing.JiraHistoryIndexingStats indexJiraHistory(
        com.andromedia.indexing.JiraHistoryIndexingRequest request) {
      throw new UnsupportedOperationException();
    }

    @Override
    public com.andromedia.indexing.LinearHistoryIndexingStats indexLinearHistory(
        com.andromedia.indexing.LinearHistoryIndexingRequest request) {
      throw new UnsupportedOperationException();
    }

    @Override
    public com.andromedia.indexing.ZohoHistoryIndexingStats indexZohoHistory(
        com.andromedia.indexing.ZohoHistoryIndexingRequest request) {
      throw new UnsupportedOperationException();
    }

    GitHistoryIndexingStats lastStats() {
      return lastStats;
    }
  }
}
