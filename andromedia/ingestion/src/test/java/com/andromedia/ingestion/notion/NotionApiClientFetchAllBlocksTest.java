package com.andromedia.ingestion.notion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class NotionApiClientFetchAllBlocksTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void fetchAllBlocksInlinesToggleChildren() throws Exception {
    JsonNode root = loadFixture("fixtures/notion/blocks-page-root.json");
    JsonNode toggleChildren = loadFixture("fixtures/notion/blocks-toggle-children.json");

    Map<String, JsonNode> getResponses = new ConcurrentHashMap<>();
    getResponses.put("blocks/page-root/children?page_size=100", root);
    getResponses.put("blocks/b-toggle/children?page_size=100", toggleChildren);

    AtomicInteger toggleFetches = new AtomicInteger();
    NotionApiClient.Transport transport =
        new NotionApiClient.Transport() {
          @Override
          public JsonNode get(String path) {
            if (path.startsWith("blocks/b-toggle/")) {
              toggleFetches.incrementAndGet();
            }
            JsonNode response = getResponses.get(path);
            if (response == null) {
              throw new IllegalArgumentException("Unexpected path: " + path);
            }
            return response;
          }

          @Override
          public JsonNode post(String path, String jsonBody) {
            throw new UnsupportedOperationException();
          }
        };

    NotionApiClient client = new NotionApiClient(transport);
    List<NotionBlock> blocks = client.fetchAllBlocks("page-root");

    assertTrue(toggleFetches.get() >= 1);
    assertTrue(blocks.stream().anyMatch(b -> b.plainText().contains("Nested toggle body")));
    assertTrue(blocks.stream().anyMatch(b -> "Why JWT".equals(b.plainText())));

    List<NotionSection> sections = NotionPageChunker.chunk(blocks);
    assertEquals(2, sections.size());
    assertTrue(sections.get(0).body().contains("Nested toggle body"));
  }

  private static JsonNode loadFixture(String resource) throws IOException {
    try (InputStream in =
        NotionApiClientFetchAllBlocksTest.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new IOException("Missing fixture: " + resource);
      }
      return MAPPER.readTree(in);
    }
  }
}
