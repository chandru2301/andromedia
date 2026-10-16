package com.andromedia.indexing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.andromedia.common.ChunkIdGenerator;
import com.andromedia.common.IndexConstants;
import com.andromedia.common.IndexPathHolder;
import com.andromedia.common.SlackChunk;
import com.andromedia.common.ZohoDealContextChunk;
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

class ZohoHistoryIndexingServiceTest {

  @TempDir Path tempDir;

  @Test
  void zohoRefreshDeletesOrphansAndPreservesOtherSources() throws Exception {
    Path indexPath = tempDir.resolve("index");
    IndexPathHolder.setDirectIndexPath(indexPath);
    try {
      LuceneIndexingService indexingService =
          new LuceneIndexingService(
              "",
              new SemanticBoundaryChunker(new MetadataExtractorRegistry()),
              new StubEmbeddingService(8));

      SlackChunk slack =
          new SlackChunk(
              ChunkIdGenerator.generateSlackThreadId("C999", "1718366400.000100"),
              "C999",
              "sales",
              "1718366400.000100",
              Instant.parse("2024-06-14T12:00:00Z"),
              List.of("alice"),
              1,
              0,
              "[SLACK] #sales · keep me");
      indexingService.indexSlackHistory(
          new SlackHistoryIndexingRequest(List.of(slack), false, true));

      ZohoDealContextChunk oldA = zohoChunk("deal-old-a", "Old Deal A", "Acme");
      ZohoDealContextChunk oldB = zohoChunk("deal-old-b", "Old Deal B", "Globex");
      indexingService.indexZohoHistory(
          new ZohoHistoryIndexingRequest(List.of(oldA, oldB), false, false));

      ZohoDealContextChunk fresh = zohoChunk("deal-fresh", "Fresh Deal", "Initech");
      ZohoHistoryIndexingStats stats =
          indexingService.indexZohoHistory(
              new ZohoHistoryIndexingRequest(List.of(fresh), false, false));
      assertEquals(1, stats.chunksIndexed());

      try (var directory = FSDirectory.open(indexPath);
          var reader = DirectoryReader.open(directory)) {
        IndexSearcher searcher = new IndexSearcher(reader);

        TopDocs zohoDocs =
            searcher.search(
                new TermQuery(
                    new Term(IndexConstants.FIELD_SOURCE_TYPE, IndexConstants.SOURCE_TYPE_ZOHO)),
                10);
        assertEquals(1, zohoDocs.totalHits.value);
        var zohoDoc = searcher.storedFields().document(zohoDocs.scoreDocs[0].doc);
        assertEquals(fresh.chunkId(), zohoDoc.get(IndexConstants.FIELD_CHUNK_ID));
        assertEquals("Fresh Deal", zohoDoc.get(IndexConstants.FIELD_DEAL_NAME));

        TopDocs goneA =
            searcher.search(
                new TermQuery(new Term(IndexConstants.FIELD_CHUNK_ID, oldA.chunkId())), 1);
        TopDocs goneB =
            searcher.search(
                new TermQuery(new Term(IndexConstants.FIELD_CHUNK_ID, oldB.chunkId())), 1);
        assertEquals(0, goneA.totalHits.value);
        assertEquals(0, goneB.totalHits.value);

        TopDocs slackDocs =
            searcher.search(
                new TermQuery(
                    new Term(IndexConstants.FIELD_SOURCE_TYPE, IndexConstants.SOURCE_TYPE_SLACK)),
                1);
        assertEquals(1, slackDocs.totalHits.value);
        assertTrue(
            searcher
                    .search(
                        new TermQuery(new Term(IndexConstants.FIELD_CHUNK_ID, slack.chunkId())), 1)
                    .totalHits
                    .value
                == 1);
      }
    } finally {
      IndexPathHolder.clear();
    }
  }

  @Test
  void incrementalUpsertPreservesUnchangedAndDeletesListedChunks() throws Exception {
    Path indexPath = tempDir.resolve("index-incr");
    IndexPathHolder.setDirectIndexPath(indexPath);
    try {
      LuceneIndexingService indexingService =
          new LuceneIndexingService(
              "",
              new SemanticBoundaryChunker(new MetadataExtractorRegistry()),
              new StubEmbeddingService(8));

      ZohoDealContextChunk keep = zohoChunk("deal-keep", "Keep Me", "Acme");
      ZohoDealContextChunk remove = zohoChunk("deal-remove", "Remove Me", "Globex");
      ZohoDealContextChunk update = zohoChunk("deal-update", "Old Name", "Initech");
      indexingService.indexZohoHistory(
          new ZohoHistoryIndexingRequest(
              List.of(keep, remove, update), false, false, null, true, List.of()));

      ZohoDealContextChunk updated = zohoChunk("deal-update", "New Name", "Initech");
      ZohoHistoryIndexingStats stats =
          indexingService.indexZohoHistory(
              new ZohoHistoryIndexingRequest(
                  List.of(updated),
                  false,
                  false,
                  null,
                  false,
                  List.of(remove.chunkId())));
      assertEquals(1, stats.chunksIndexed());

      try (var directory = FSDirectory.open(indexPath);
          var reader = DirectoryReader.open(directory)) {
        IndexSearcher searcher = new IndexSearcher(reader);

        assertEquals(
            1,
            searcher
                .search(new TermQuery(new Term(IndexConstants.FIELD_CHUNK_ID, keep.chunkId())), 1)
                .totalHits
                .value);
        assertEquals(
            0,
            searcher
                .search(new TermQuery(new Term(IndexConstants.FIELD_CHUNK_ID, remove.chunkId())), 1)
                .totalHits
                .value);

        TopDocs updatedDocs =
            searcher.search(
                new TermQuery(new Term(IndexConstants.FIELD_CHUNK_ID, update.chunkId())), 1);
        assertEquals(1, updatedDocs.totalHits.value);
        assertEquals(
            "New Name",
            searcher
                .storedFields()
                .document(updatedDocs.scoreDocs[0].doc)
                .get(IndexConstants.FIELD_DEAL_NAME));

        TopDocs zohoDocs =
            searcher.search(
                new TermQuery(
                    new Term(IndexConstants.FIELD_SOURCE_TYPE, IndexConstants.SOURCE_TYPE_ZOHO)),
                10);
        assertEquals(2, zohoDocs.totalHits.value);
      }
    } finally {
      IndexPathHolder.clear();
    }
  }

  private static ZohoDealContextChunk zohoChunk(String dealId, String dealName, String account) {
    return new ZohoDealContextChunk(
        ChunkIdGenerator.generateZohoDealContextId(dealId),
        dealId,
        dealName,
        "Qualification",
        "10000",
        "",
        "10",
        "2026-08-01",
        "Owner",
        "Web",
        "",
        Instant.parse("2026-07-16T00:00:00Z"),
        "acc-" + dealId,
        account,
        "",
        List.of(),
        List.of(),
        "[ZOHO] Deal · " + dealName + " · " + account,
        0.8,
        List.of(),
        List.of(),
        "deal_context",
        IndexConstants.SOURCE_TYPE_ZOHO);
  }
}
