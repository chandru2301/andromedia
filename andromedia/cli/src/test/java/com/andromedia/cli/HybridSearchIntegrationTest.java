package com.andromedia.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.andromedia.common.ChunkUnitType;
import com.andromedia.common.EmbeddingConstants;
import com.andromedia.indexing.IndexingRequest;
import com.andromedia.indexing.LuceneIndexingService;
import com.andromedia.ingestion.SemanticBoundaryChunker;
import com.andromedia.llm.StubEmbeddingService;
import com.andromedia.search.HybridSearchService;
import com.andromedia.search.PassThroughReranker;
import com.andromedia.search.LuceneSearchService;
import com.andromedia.search.SearchMode;
import com.andromedia.search.SearchRequest;
import com.andromedia.search.VectorSearchService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HybridSearchIntegrationTest {

  @TempDir
  Path tempDirectory;

  @Test
  void hybridSearchFusesLexicalAndSemanticChannels() throws Exception {
    Path baseIndexPath = Files.createDirectories(tempDirectory.resolve("hybrid-index"));
    Path workspace = Files.createDirectories(tempDirectory.resolve("hybrid-workspace"));
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
    String indexPath = baseIndexPath.toString();
    LuceneIndexingService indexingService =
        new LuceneIndexingService(indexPath, new SemanticBoundaryChunker(), embeddingService);
    indexingService.indexPath(
        new IndexingRequest(workspace, Set.of("java"), true, false, true));

    HybridSearchService hybridSearchService =
        new HybridSearchService(
            indexPath,
            new LuceneSearchService(indexPath),
            new VectorSearchService(indexPath, embeddingService),
            new PassThroughReranker());

    String originalUserDir = System.getProperty("user.dir");
    try {
      System.setProperty("user.dir", workspace.toString());

      var hybridResult =
          hybridSearchService.search(new SearchRequest("validateJwtToken", 5, SearchMode.HYBRID));

      assertFalse(hybridResult.hits().isEmpty());
      assertEquals("validateJwtToken", hybridResult.hits().getFirst().methodName());
      assertTrue(hybridResult.diagnostics().bm25Candidates() > 0);
      assertTrue(hybridResult.diagnostics().semanticCandidates() > 0);
      assertEquals(false, hybridResult.diagnostics().semanticSkipped());
      assertTrue(
          hybridResult.hits().stream().anyMatch(hit -> "validateJwtToken".equals(hit.methodName())));
    } finally {
      System.setProperty("user.dir", originalUserDir);
    }
  }
}
