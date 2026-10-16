package com.andromedia.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.andromedia.common.IndexConstants;
import com.andromedia.common.IndexPathHolder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexAccessMultiReaderTest {

  @TempDir Path tempDir;

  @AfterEach
  void tearDown() {
    IndexPathHolder.clear();
  }

  @Test
  void openIndexUnionsDocumentsFromMultipleDirectories() throws Exception {
    Path slackIndex = tempDir.resolve("local");
    Path zohoIndex = tempDir.resolve("local__z_abc");
    writeDoc(slackIndex, "slack-1", "SLACK", "standup notes about reliance");
    writeDoc(zohoIndex, "zoho-1", "ZOHO", "reliance data center expansion deal");

    try (IndexAccess.OpenedIndex opened = IndexAccess.openIndex(List.of(slackIndex, zohoIndex))) {
      assertEquals(2, opened.reader().numDocs());
    }
  }

  @Test
  void luceneSearchRawFindsHitsAcrossHolderPaths() throws Exception {
    Path slackIndex = tempDir.resolve("local");
    Path zohoIndex = tempDir.resolve("local__z_abc");
    writeDoc(slackIndex, "slack-1", "SLACK", "channel decision about reliance");
    writeDoc(zohoIndex, "zoho-1", "ZOHO", "reliance data center expansion");

    IndexPathHolder.setDirectIndexPaths(List.of(slackIndex, zohoIndex));
    LuceneSearchService searchService = new LuceneSearchService("");
    List<SearchHit> hits =
        searchService.searchRaw(new SearchRequest("reliance", 10, SearchMode.BM25));

    assertTrue(hits.size() >= 2);
    assertTrue(
        hits.stream().anyMatch(hit -> hit.sourceType().map("SLACK"::equals).orElse(false)));
    assertTrue(
        hits.stream().anyMatch(hit -> hit.sourceType().map("ZOHO"::equals).orElse(false)));
  }

  private static void writeDoc(Path indexPath, String chunkId, String sourceType, String content)
      throws Exception {
    Files.createDirectories(indexPath);
    try (FSDirectory directory = FSDirectory.open(indexPath);
        IndexWriter writer =
            new IndexWriter(directory, new IndexWriterConfig(new StandardAnalyzer()))) {
      Document document = new Document();
      document.add(new StringField(IndexConstants.FIELD_CHUNK_ID, chunkId, Field.Store.YES));
      document.add(new StringField(IndexConstants.FIELD_SOURCE_TYPE, sourceType, Field.Store.YES));
      document.add(new StringField(IndexConstants.FIELD_PATH, chunkId + ".txt", Field.Store.YES));
      document.add(new StringField(IndexConstants.FIELD_FILE_NAME, chunkId + ".txt", Field.Store.YES));
      document.add(new TextField(IndexConstants.FIELD_CONTENT, content, Field.Store.YES));
      writer.addDocument(document);
      writer.commit();
    }
  }
}
