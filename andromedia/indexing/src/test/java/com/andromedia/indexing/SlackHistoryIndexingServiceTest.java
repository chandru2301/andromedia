package com.andromedia.indexing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.andromedia.common.ChunkIdGenerator;
import com.andromedia.common.IndexConstants;
import com.andromedia.common.IndexPathHolder;
import com.andromedia.common.SlackChunk;
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

class SlackHistoryIndexingServiceTest {

  @TempDir Path tempDir;

  @Test
  void indexesSlackHistoryChunkAndFindsItByChannel() throws Exception {
    Path indexPath = tempDir.resolve("index");
    IndexPathHolder.setDirectIndexPath(indexPath);
    try {
      LuceneIndexingService indexingService =
          new LuceneIndexingService(
              "",
              new SemanticBoundaryChunker(new MetadataExtractorRegistry()),
              new StubEmbeddingService(8));

      SlackChunk chunk =
          new SlackChunk(
              ChunkIdGenerator.generateSlackThreadId("C123", "1718366400.000100"),
              "C123",
              "backend-eng",
              "1718366400.000100",
              Instant.parse("2024-06-14T12:00:00Z"),
              List.of("chandru", "ravi"),
              7,
              3,
              "[SLACK] #backend-eng · 2024-06-14 · chandru, ravi\nwe decided to switch to async Kafka\n");

      SlackHistoryIndexingStats stats =
          indexingService.indexSlackHistory(
              new SlackHistoryIndexingRequest(List.of(chunk), false, true));
      assertEquals(1, stats.chunksIndexed());

      try (var directory = FSDirectory.open(indexPath);
          var reader = DirectoryReader.open(directory)) {
        IndexSearcher searcher = new IndexSearcher(reader);
        TopDocs topDocs =
            searcher.search(
                new TermQuery(new Term(IndexConstants.FIELD_CHANNEL_ID, "C123")), 1);
        assertEquals(1, topDocs.totalHits.value);
        var document = searcher.storedFields().document(topDocs.scoreDocs[0].doc);
        assertEquals(IndexConstants.SOURCE_TYPE_SLACK, document.get(IndexConstants.FIELD_SOURCE_TYPE));
        assertEquals("backend-eng", document.get(IndexConstants.FIELD_CHANNEL_NAME));
        assertEquals("7", document.get(IndexConstants.FIELD_REACTION_COUNT));
      }
    } finally {
      IndexPathHolder.clear();
    }
  }
}
