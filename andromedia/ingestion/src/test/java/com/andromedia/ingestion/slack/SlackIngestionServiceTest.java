package com.andromedia.ingestion.slack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SlackIngestionServiceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void ingestsConfiguredChannelAndExpandsThreads() throws Exception {
    FixtureTransport transport = new FixtureTransport();
    SlackApiClient client = new SlackApiClient(transport);
    client.putUserName("U001", "chandru");
    client.putUserName("U002", "ravi");
    client.putUserName("U003", "priya");

    SlackIngestionService service = new SlackIngestionService(client);
    SlackIngestionService.IngestResult result =
        service.ingest(
            new SlackIngestionOptions(
                null, List.of("backend-eng"), Map.of(), 500, true));

    assertEquals(2, result.threads().size());
    assertTrue(
        result.threads().stream()
            .anyMatch(thread -> thread.messageCount() == 3 && thread.reactionCount() == 7));
    assertTrue(
        result.threads().stream()
            .anyMatch(thread -> thread.messageCount() == 1 && thread.messages().getFirst().text().contains("standup")));
    assertTrue(result.latestTimestampByChannel().containsKey("C001"));
  }

  private static final class FixtureTransport implements SlackApiClient.Transport {
    private final Map<String, JsonNode> fixtures = new LinkedHashMap<>();

    FixtureTransport() throws IOException {
      fixtures.put(
          "conversations.list",
          read("src/test/resources/fixtures/slack/conversations-list.json"));
      fixtures.put(
          "conversations.history",
          read("src/test/resources/fixtures/slack/conversations-history.json"));
      fixtures.put(
          "conversations.replies",
          read("src/test/resources/fixtures/slack/conversations-replies.json"));
    }

    @Override
    public JsonNode get(String method, Map<String, String> queryParams) {
      JsonNode fixture = fixtures.get(method);
      if (fixture == null) {
        throw new IllegalArgumentException("No fixture for " + method);
      }
      return fixture;
    }

    private static JsonNode read(String path) throws IOException {
      return MAPPER.readTree(Files.readString(Path.of(path)));
    }
  }
}
