package com.andromedia.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.andromedia.common.IndexConstants;
import java.nio.file.Path;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServiceIndexOverviewReaderTest {

  @TempDir Path tempDir;

  @Test
  void returnsEmptyWhenIndexMissing() {
    Path missing = tempDir.resolve("no-such-index");
    ServiceIndexOverviewReader.Overview overview = ServiceIndexOverviewReader.read(missing);
    assertFalse(overview.exists());
    assertEquals(0, overview.totalChunks());
    assertEquals(0, overview.sourcesConnected());
    assertTrue(overview.sourceTypes().isEmpty());
    assertEquals(0L, overview.indexSizeBytes());
  }

  @Test
  void readsChunksSourceTypesSizeAndLastSync() throws Exception {
    Path indexPath = tempDir.resolve("index");
    try (var directory = FSDirectory.open(indexPath);
        var writer = new IndexWriter(directory, new IndexWriterConfig(new StandardAnalyzer()))) {
      Document slack = new Document();
      slack.add(new StringField(IndexConstants.FIELD_CHUNK_ID, "slack-1", Field.Store.YES));
      slack.add(
          new StringField(
              IndexConstants.FIELD_SOURCE_TYPE, IndexConstants.SOURCE_TYPE_SLACK, Field.Store.YES));
      slack.add(new TextField(IndexConstants.FIELD_CONTENT, "kafka decided", Field.Store.YES));
      writer.addDocument(slack);

      Document code = new Document();
      code.add(new StringField(IndexConstants.FIELD_CHUNK_ID, "code-1", Field.Store.YES));
      code.add(new TextField(IndexConstants.FIELD_CONTENT, "class PaymentService", Field.Store.YES));
      writer.addDocument(code);
      writer.commit();
    }

    ServiceIndexOverviewReader.Overview overview = ServiceIndexOverviewReader.read(indexPath);
    assertTrue(overview.exists());
    assertEquals(2, overview.totalChunks());
    assertTrue(overview.sourceTypes().contains(IndexConstants.SOURCE_TYPE_SLACK));
    assertTrue(overview.sourceTypes().contains(IndexConstants.SOURCE_TYPE_CODE));
    assertEquals(2, overview.sourcesConnected());
    assertTrue(overview.indexSizeBytes() > 0);
    assertTrue(overview.lastSyncAt() != null);
  }
}
