package com.andromedia.indexing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.andromedia.common.GitHistoryCursorStore;
import com.andromedia.common.IndexPathResolver;
import com.andromedia.ingestion.git.GitIngestionOptions;
import com.andromedia.ingestion.git.GitIngestionService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitHistoryIndexingOrchestratorTest {

  @TempDir Path tempDir;

  @Test
  void indexesGitHistoryIntoProjectIndexPath() throws Exception {
    Path repoDir = tempDir.resolve("repo");
    Files.createDirectories(repoDir);
    try (Git git = Git.init().setDirectory(repoDir.toFile()).call()) {
      Files.writeString(repoDir.resolve("README.md"), "hello");
      git.add().addFilepattern(".").call();
      git.commit().setMessage("bootstrap commit").call();
    }

    Path configuredIndexRoot = tempDir.resolve("indexes");
    LuceneIndexingService indexingService =
        new LuceneIndexingService(
            configuredIndexRoot.toString(),
            new com.andromedia.ingestion.SemanticBoundaryChunker(
                new com.andromedia.ingestion.metadata.MetadataExtractorRegistry()),
            new com.andromedia.llm.StubEmbeddingService(8));

    GitHistoryIndexingOrchestrator orchestrator =
        new GitHistoryIndexingOrchestrator(
            new GitIngestionService(java.util.List.of()),
            indexingService,
            new GitHistoryCursorStore());

    GitHistoryIndexingOrchestrator.GitHistoryIndexingResult result =
        orchestrator.indexRepositoryWithCursor(
            repoDir, new GitIngestionOptions(null, 10, null, null), false, true);

    assertEquals(1, result.stats().commitsIndexed());
    assertEquals(1, result.events().size());

    Path workspaceRoot = IndexPathResolver.resolveWorkspaceRoot(repoDir);
    String projectId = IndexPathResolver.resolveProjectId(workspaceRoot);
    Path projectIndexPath = configuredIndexRoot.resolve(projectId);
    assertTrue(Files.isDirectory(projectIndexPath), "Expected project index at " + projectIndexPath);
  }
}
