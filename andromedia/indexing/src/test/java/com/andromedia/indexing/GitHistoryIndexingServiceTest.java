package com.andromedia.indexing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.andromedia.common.ChunkIdGenerator;
import com.andromedia.common.GitHistoryChunk;
import com.andromedia.common.IndexConstants;
import com.andromedia.common.IndexPathHolder;
import com.andromedia.common.StructuralMetadata;
import com.andromedia.ingestion.SemanticBoundaryChunker;
import com.andromedia.ingestion.metadata.MetadataExtractorRegistry;
import com.andromedia.llm.StubEmbeddingService;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitHistoryIndexingServiceTest {

  @TempDir Path tempDir;

  @Test
  void indexesGitHistoryChunkAndFindsItByContent() throws Exception {
    Path indexPath = tempDir.resolve("index");
    IndexPathHolder.setDirectIndexPath(indexPath);
    try {
      LuceneIndexingService indexingService =
          new LuceneIndexingService(
              "",
              new SemanticBoundaryChunker(new MetadataExtractorRegistry()),
              new StubEmbeddingService(8));

      GitHistoryChunk chunk =
          new GitHistoryChunk(
              ChunkIdGenerator.generateGitEventId("deadbeef"),
              "[GIT_COMMIT] deadbeef · 2024-03-01T12:00:00Z · Alice\nFix user lookup\n\nChanged: src/UserService.java\nSymbols: UserService#getUser\n\n",
              "deadbeef",
              Instant.parse("2024-03-01T12:00:00Z"),
              "Alice",
              List.of("src/UserService.java"),
              StructuralMetadata.empty());

      GitHistoryIndexingStats stats =
          indexingService.indexGitHistory(new GitHistoryIndexingRequest(List.of(chunk), false, true));
      assertEquals(1, stats.chunksIndexed());

      try (var directory = FSDirectory.open(indexPath);
          var reader = DirectoryReader.open(directory)) {
        IndexSearcher searcher = new IndexSearcher(reader);
        TopDocs topDocs =
            searcher.search(new TermQuery(new Term(IndexConstants.FIELD_COMMIT_SHA, "deadbeef")), 1);
        assertEquals(1, topDocs.totalHits.value);
        var document = searcher.storedFields().document(topDocs.scoreDocs[0].doc);
        assertEquals(IndexConstants.SOURCE_TYPE_GIT, document.get(IndexConstants.FIELD_SOURCE_TYPE));
        assertEquals("deadbeef", document.get(IndexConstants.FIELD_COMMIT_SHA));
      }
    } finally {
      IndexPathHolder.clear();
    }
  }
}
