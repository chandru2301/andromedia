package com.andromedia.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.andromedia.common.EmbeddingConstants;
import com.andromedia.indexing.IndexingRequest;
import com.andromedia.indexing.IndexingStats;
import com.andromedia.indexing.LuceneIndexingService;
import com.andromedia.ingestion.SemanticBoundaryChunker;
import com.andromedia.llm.StubEmbeddingService;
import com.andromedia.search.SearchMode;
import com.andromedia.search.SearchRequest;
import com.andromedia.search.VectorSearchService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SemanticSearchIntegrationTest {

  @TempDir
  Path tempDirectory;

  @Test
  void indexesEmbeddingsAndRunsSemanticSearch() throws IOException {
    Path baseIndexPath = Files.createDirectories(tempDirectory.resolve("semantic-index"));
    Path workspace = Files.createDirectories(tempDirectory.resolve("semantic-workspace"));
    Path sourceRoot = Files.createDirectories(workspace.resolve("src"));

    String validateMethodBody =
        """
          public boolean validateJwtToken(String token) {
            return token != null && token.startsWith("Bearer");
          }
        """;

    Files.writeString(
        sourceRoot.resolve("AuthService.java"),
        """
        package demo;

        public class AuthService {
        """
            + validateMethodBody
            + """

          public void refreshSession() {
            System.out.println("refresh");
          }
        }
        """);

    StubEmbeddingService embeddingService =
        new StubEmbeddingService(EmbeddingConstants.ADA_002_DIMENSIONS);
    LuceneIndexingService indexingService =
        new LuceneIndexingService(
            baseIndexPath.toString(), new SemanticBoundaryChunker(), embeddingService);

    IndexingStats stats =
        indexingService.indexPath(
            new IndexingRequest(workspace, Set.of("java"), true, false, true));
    assertTrue(stats.chunksEmbedded() > 0);

    VectorSearchService vectorSearchService =
        new VectorSearchService(baseIndexPath.toString(), embeddingService);

    String originalUserDir = System.getProperty("user.dir");
    try {
      System.setProperty("user.dir", workspace.toString());

      var result =
          vectorSearchService.search(
              new SearchRequest(validateMethodBody.trim(), 5, SearchMode.SEMANTIC));

      assertFalse(result.hits().isEmpty());
      assertEquals("validateJwtToken", result.hits().getFirst().methodName());
    } finally {
      System.setProperty("user.dir", originalUserDir);
    }
  }
}
