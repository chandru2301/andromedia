package com.andromedia.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.andromedia.common.EmbeddingConstants;
import com.andromedia.indexing.IndexingRequest;
import com.andromedia.indexing.LuceneIndexingService;
import com.andromedia.ingestion.SemanticBoundaryChunker;
import com.andromedia.llm.EmbeddingException;
import com.andromedia.llm.EmbeddingService;
import com.andromedia.llm.StubEmbeddingService;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HybridSearchEmbeddingFailureTest {

  @TempDir
  Path tempDirectory;

  @Test
  void hybridSearchFallsBackToBm25WhenQueryEmbeddingFails() throws Exception {
    Path baseIndexPath = Files.createDirectories(tempDirectory.resolve("hybrid-index"));
    Path workspace = Files.createDirectories(tempDirectory.resolve("hybrid-workspace"));
    Path sourceRoot = Files.createDirectories(workspace.resolve("src"));

    Files.writeString(
        sourceRoot.resolve("AnomalyKafkaListener.java"),
        """
        package demo;

        public class AnomalyKafkaListener {
          public void onMessage(String payload) {
            System.out.println(payload);
          }
        }
        """);

    StubEmbeddingService indexEmbeddingService =
        new StubEmbeddingService(EmbeddingConstants.ADA_002_DIMENSIONS);
    String indexPath = baseIndexPath.toString();
    LuceneIndexingService indexingService =
        new LuceneIndexingService(indexPath, new SemanticBoundaryChunker(), indexEmbeddingService);
    indexingService.indexPath(
        new IndexingRequest(workspace, Set.of("java"), true, false, true));

    EmbeddingService failingQueryEmbedding =
        new EmbeddingService() {
          @Override
          public float[] embed(String text) {
            throw new EmbeddingException("OpenRouter embedding request failed");
          }

          @Override
          public List<float[]> embedAll(List<String> texts) {
            throw new EmbeddingException("OpenRouter embedding request failed");
          }

          @Override
          public int dimensions() {
            return EmbeddingConstants.ADA_002_DIMENSIONS;
          }
        };

    HybridSearchService hybridSearchService =
        new HybridSearchService(
            indexPath,
            new LuceneSearchService(indexPath),
            new VectorSearchService(indexPath, failingQueryEmbedding),
            new PassThroughReranker());

    String originalUserDir = System.getProperty("user.dir");
    PrintStream originalErr = System.err;
    ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();
    try {
      System.setProperty("user.dir", workspace.toString());
      System.setErr(new PrintStream(capturedErr, true, StandardCharsets.UTF_8));

      var hybridResult =
          hybridSearchService.search(new SearchRequest("onMessage", 5, SearchMode.HYBRID));

      assertFalse(hybridResult.hits().isEmpty());
      assertEquals("onMessage", hybridResult.hits().getFirst().methodName());
      assertTrue(hybridResult.diagnostics().semanticSkipped());
      assertEquals(0, hybridResult.diagnostics().semanticCandidates());
      assertTrue(hybridResult.diagnostics().bm25Candidates() > 0);
      assertTrue(
          hybridResult.diagnostics().semanticSkipReason().startsWith("Query embedding failed"));
      String stderr = capturedErr.toString(StandardCharsets.UTF_8);
      assertTrue(stderr.contains(HybridSearchService.SEMANTIC_EMBEDDING_FAILURE_WARNING));
    } finally {
      System.setErr(originalErr);
      System.setProperty("user.dir", originalUserDir);
    }
  }
}
