package com.andromedia.core.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.andromedia.common.SlackChunk;
import com.andromedia.common.SlackHistoryCursorStore;
import com.andromedia.indexing.GitHistoryIndexingStats;
import com.andromedia.indexing.IndexingService;
import com.andromedia.indexing.SlackHistoryIndexingRequest;
import com.andromedia.indexing.SlackHistoryIndexingStats;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SlackIngestionJobServiceTest {

  @Test
  void rejectsMissingBotToken() {
    SlackIngestionJobService service =
        new SlackIngestionJobService(
            new IndexingServiceStub(),
            new SlackIngestionJobStore(),
            new ServiceIndexMetadataStore(),
            new SlackIngestionProperties(),
            new SlackHistoryCursorStore());

    assertThrows(
        IllegalArgumentException.class,
        () -> service.enqueue("demo", "", List.of("#eng"), false, 30, null));
  }

  @Test
  void rejectsEmptyChannels() {
    SlackIngestionProperties properties = new SlackIngestionProperties();
    properties.setBotToken("xoxb-test");
    SlackIngestionJobService service =
        new SlackIngestionJobService(
            new IndexingServiceStub(),
            new SlackIngestionJobStore(),
            new ServiceIndexMetadataStore(),
            properties,
            new SlackHistoryCursorStore());

    assertThrows(
        IllegalArgumentException.class,
        () -> service.enqueue("demo", "xoxb-test", List.of(), false, 30, null));
  }

  @Test
  void enqueuesJobWithTokenAndChannels() {
    SlackIngestionJobStore jobStore = new SlackIngestionJobStore();
    SlackIngestionJobService service =
        new SlackIngestionJobService(
            new IndexingServiceStub(),
            jobStore,
            new ServiceIndexMetadataStore(),
            new SlackIngestionProperties(),
            new SlackHistoryCursorStore());

    SlackIngestionJobStore.SlackIngestionJob job =
        service.enqueue("demo", "xoxb-test", List.of("backend-eng"), false, 30, null);

    assertEquals("queued", job.status());
    assertEquals("demo", job.service());
    assertEquals(List.of("backend-eng"), job.channels());
  }

  private static final class IndexingServiceStub implements IndexingService {
    @Override
    public com.andromedia.indexing.IndexingStats indexPath(
        com.andromedia.indexing.IndexingRequest request) {
      throw new UnsupportedOperationException();
    }

    @Override
    public GitHistoryIndexingStats indexGitHistory(
        com.andromedia.indexing.GitHistoryIndexingRequest request) {
      throw new UnsupportedOperationException();
    }

    @Override
    public SlackHistoryIndexingStats indexSlackHistory(SlackHistoryIndexingRequest request) {
      List<SlackChunk> chunks = request.chunks();
      return new SlackHistoryIndexingStats(
          chunks.size(), chunks.size(), 0, 0, Instant.now(), Instant.now());
    }

    @Override
    public com.andromedia.indexing.NotionHistoryIndexingStats indexNotionHistory(
        com.andromedia.indexing.NotionHistoryIndexingRequest request) {
      return new com.andromedia.indexing.NotionHistoryIndexingStats(
          0, request.chunks().size(), 0, 0, Instant.now(), Instant.now());
    }

    @Override
    public com.andromedia.indexing.JiraHistoryIndexingStats indexJiraHistory(
        com.andromedia.indexing.JiraHistoryIndexingRequest request) {
      return new com.andromedia.indexing.JiraHistoryIndexingStats(
          request.chunks().size(), request.chunks().size(), 0, 0, Instant.now(), Instant.now());
    }

    @Override
    public com.andromedia.indexing.LinearHistoryIndexingStats indexLinearHistory(
        com.andromedia.indexing.LinearHistoryIndexingRequest request) {
      return new com.andromedia.indexing.LinearHistoryIndexingStats(
          request.chunks().size(), request.chunks().size(), 0, 0, Instant.now(), Instant.now());
    }

    @Override
    public com.andromedia.indexing.ZohoHistoryIndexingStats indexZohoHistory(
        com.andromedia.indexing.ZohoHistoryIndexingRequest request) {
      return new com.andromedia.indexing.ZohoHistoryIndexingStats(
          request.chunks().size(), 0, 0, 0, Instant.now(), Instant.now());
    }
  }
}
