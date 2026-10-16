package com.andromedia.indexing;

import com.andromedia.common.GitChangeEvent;
import com.andromedia.common.GitHistoryChunk;
import com.andromedia.common.GitHistoryCursorStore;
import com.andromedia.common.IndexPathResolver;
import com.andromedia.ingestion.git.GitHistoryChunkBuilder;
import com.andromedia.ingestion.git.GitIngestionOptions;
import com.andromedia.ingestion.git.GitIngestionService;
import com.andromedia.ingestion.git.GitHubPullRequestProvider;
import com.andromedia.ingestion.git.MergeCommitPullRequestParser;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class GitHistoryIndexingOrchestrator {

  private final GitIngestionService gitIngestionService;
  private final IndexingService indexingService;
  private final GitHistoryCursorStore cursorStore;

  public GitHistoryIndexingOrchestrator(
      IndexingService indexingService, GitHistoryCursorStore cursorStore) {
    this(
        new GitIngestionService(
            List.of(new MergeCommitPullRequestParser(), new GitHubPullRequestProvider())),
        indexingService,
        cursorStore);
  }

  public GitHistoryIndexingOrchestrator(
      GitIngestionService gitIngestionService,
      IndexingService indexingService,
      GitHistoryCursorStore cursorStore) {
    this.gitIngestionService = gitIngestionService;
    this.indexingService = indexingService;
    this.cursorStore = cursorStore;
  }

  public GitHistoryIndexingResult indexRepository(
      Path repoRoot,
      GitIngestionOptions options,
      boolean embedChunks,
      boolean recreateIndex) {
    Path normalizedRepo = repoRoot.toAbsolutePath().normalize();
    if (!GitIngestionService.isGitRepository(normalizedRepo)) {
      throw new IllegalArgumentException("Not a git repository: " + normalizedRepo);
    }

    Path workspaceRoot = IndexPathResolver.resolveWorkspaceRoot(normalizedRepo);
    GitIngestionOptions effectiveOptions = options == null ? GitIngestionOptions.defaults() : options;

    List<GitChangeEvent> events;
    try {
      events = gitIngestionService.ingest(normalizedRepo, effectiveOptions);
    } catch (java.io.IOException ex) {
      throw new IndexingException("Failed to ingest git history from " + normalizedRepo, ex);
    }
    if (events.isEmpty()) {
      return new GitHistoryIndexingResult(
          new GitHistoryIndexingStats(0, 0, 0, 0, 0, null, null), null, List.of());
    }

    List<GitHistoryChunk> chunks = new ArrayList<>(events.size());
    for (GitChangeEvent event : events) {
      chunks.add(GitHistoryChunkBuilder.build(event));
    }

    GitHistoryIndexingStats stats =
        indexingService.indexGitHistory(
            new GitHistoryIndexingRequest(chunks, embedChunks, recreateIndex, workspaceRoot));

    String latestCommitSha = events.getFirst().commitSha();
    return new GitHistoryIndexingResult(stats, latestCommitSha, events);
  }

  public GitHistoryIndexingResult indexRepositoryWithCursor(
      Path repoRoot,
      GitIngestionOptions options,
      boolean embedChunks,
      boolean recreateIndex) {
    Path workspaceRoot = IndexPathResolver.resolveWorkspaceRoot(repoRoot);
    GitIngestionOptions baseOptions = options == null ? GitIngestionOptions.defaults() : options;
    String stopAtCommitSha =
        recreateIndex
            ? null
            : cursorStore.readLastIndexedCommitSha(workspaceRoot).orElse(null);
    GitIngestionOptions effectiveOptions = baseOptions.withStopAtCommitSha(stopAtCommitSha);

    GitHistoryIndexingResult result =
        indexRepository(repoRoot, effectiveOptions, embedChunks, recreateIndex);
    if (result.latestCommitSha() != null) {
      cursorStore.saveLastIndexedCommitSha(workspaceRoot, result.latestCommitSha());
    }
    return result;
  }

  public record GitHistoryIndexingResult(
      GitHistoryIndexingStats stats, String latestCommitSha, List<GitChangeEvent> events) {}
}
