package com.andromedia.ingestion.slack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SlackApiClientPostMessageTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void postMessageSendsChatPostMessagePayload() throws Exception {
    RecordingTransport transport = new RecordingTransport();
    transport.postResponse =
        MAPPER.readTree(
            """
            {"ok":true,"channel":"C123","ts":"1710000000.000100"}
            """);
    SlackApiClient client = new SlackApiClient(transport);

    SlackApiClient.SlackPostResult result =
        client.postMessage("C123", "hello from andro", null);

    assertTrue(result.ok());
    assertEquals("C123", result.channel());
    assertEquals("chat.postMessage", transport.lastMethod);
    assertEquals("C123", transport.lastBody.get("channel"));
    assertEquals("hello from andro", transport.lastBody.get("text"));
  }

  private static final class RecordingTransport implements SlackApiClient.Transport {
    JsonNode postResponse;
    String lastMethod;
    Map<String, Object> lastBody;

    @Override
    public JsonNode get(String method, Map<String, String> queryParams) {
      throw new UnsupportedOperationException();
    }

    @Override
    public JsonNode postJson(String method, Map<String, Object> body) throws IOException {
      lastMethod = method;
      lastBody = new LinkedHashMap<>(body);
      if (postResponse == null) {
        throw new IOException("No fixture");
      }
      return postResponse;
    }
  }
}
