package com.andromedia.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.andromedia.common.ChunkUnitType;
import com.andromedia.common.IndexConstants;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.junit.jupiter.api.Test;

class IndexDocumentMapperTest {

  @Test
  void mapsContentFieldToSearchHit() {
    Document document = new Document();
    document.add(new StringField(IndexConstants.FIELD_CHUNK_ID, "git-1", Field.Store.YES));
    document.add(new StoredField(IndexConstants.FIELD_PATH, "git/abc"));
    document.add(new StoredField(IndexConstants.FIELD_FILE_NAME, "abc"));
    document.add(new StoredField(IndexConstants.FIELD_UNIT_TYPE, ChunkUnitType.HISTORY.name()));
    document.add(new StoredField(IndexConstants.FIELD_CONTENT, "[GIT_COMMIT] abc\nsubject line\n"));
    document.add(new StoredField(IndexConstants.FIELD_SOURCE_TYPE, IndexConstants.SOURCE_TYPE_GIT));
    document.add(new StoredField(IndexConstants.FIELD_COMMIT_SHA, "abc"));
    document.add(new StoredField(IndexConstants.FIELD_TIMESTAMP, "2024-01-01T00:00:00Z"));

    SearchHit hit = IndexDocumentMapper.toSearchHit(document, 0.5f);

    assertEquals("git-1", hit.chunkId());
    assertTrue(hit.content().isPresent());
    assertTrue(hit.content().orElseThrow().contains("[GIT_COMMIT]"));
    assertEquals(IndexConstants.SOURCE_TYPE_GIT, hit.sourceType().orElseThrow());
  }
}
