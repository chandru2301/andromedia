package com.andromedia.ingestion.notion;

import com.andromedia.common.NotionChunk;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NotionIngestionService {

  private static final Logger log = LoggerFactory.getLogger(NotionIngestionService.class);

  private final NotionApiClient apiClient;

  public NotionIngestionService(NotionApiClient apiClient) {
    this.apiClient = apiClient;
  }

  public NotionApiClient.NotionMeResult validateToken() throws IOException {
    return apiClient.usersMe();
  }

  public IngestResult ingest(NotionIngestionOptions options) throws IOException {
    LinkedHashSet<String> pageIds = new LinkedHashSet<>(options.pageIds());
    for (String databaseId : options.databaseIds()) {
      pageIds.addAll(apiClient.queryDatabasePageIds(databaseId));
    }
    if (pageIds.isEmpty()) {
      return new IngestResult(List.of(), Map.of(), 0);
    }

    List<NotionChunk> chunks = new ArrayList<>();
    Map<String, String> latestEdited = new LinkedHashMap<>();
    int pagesIndexed = 0;

    for (String pageId : pageIds) {
      NotionPage page = apiClient.retrievePage(pageId);
      if (!NotionPageFilter.shouldIndex(page)) {
        log.warn(
            "Skipping Notion page id={} title='{}' archived={}",
            page.id(),
            page.title(),
            page.archived());
        continue;
      }
      List<NotionBlock> blocks = apiClient.fetchAllBlocks(page.id());
      List<NotionSection> sections = NotionPageChunker.chunk(blocks);
      if (sections.isEmpty()) {
        log.warn(
            "Notion page '{}' ({}) produced 0 sections from {} blocks",
            page.title(),
            page.id(),
            blocks.size());
        continue;
      }
      log.info(
          "Notion page '{}' ({}) -> {} sections from {} blocks",
          page.title(),
          page.id(),
          sections.size(),
          blocks.size());
      for (NotionSection section : sections) {
        chunks.add(NotionHistoryChunkBuilder.build(page, section));
      }
      latestEdited.put(page.id(), page.lastEdited().toString());
      pagesIndexed++;
    }

    return new IngestResult(chunks, latestEdited, pagesIndexed);
  }

  public record IngestResult(
      List<NotionChunk> chunks, Map<String, String> latestEditedByPage, int pagesIndexed) {

    public IngestResult {
      chunks = chunks == null ? List.of() : List.copyOf(chunks);
      latestEditedByPage =
          latestEditedByPage == null ? Map.of() : Map.copyOf(latestEditedByPage);
    }
  }
}
