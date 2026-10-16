package com.andromedia.ingestion.slack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SlackApiClientUserResolveTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void extractDisplayNamePrefersProfileDisplayName() throws Exception {
    JsonNode user =
        MAPPER.readTree(
            """
            {
              "id": "U001",
              "name": "chandru.raw",
              "real_name": "Chandru Raw",
              "profile": { "display_name": "chandru", "real_name": "Chandru Raw" }
            }
            """);
    assertEquals("chandru", SlackApiClient.extractDisplayName(user));
  }

  @Test
  void extractDisplayNameFallsBackWhenDisplayNameBlank() throws Exception {
    JsonNode user =
        MAPPER.readTree(
            """
            {
              "id": "U001",
              "name": "chandru.raw",
              "real_name": "Chandru",
              "profile": { "display_name": "", "real_name": "Chandru" }
            }
            """);
    assertEquals("Chandru", SlackApiClient.extractDisplayName(user));
  }

  @Test
  void resolveUserNamesCachesDisplayNames() throws Exception {
    MapTransport transport = new MapTransport();
    transport.put(
        "users.info",
        MAPPER.readTree(
            """
            {
              "ok": true,
              "user": {
                "id": "U09AFJBJ876",
                "name": "chandru",
                "real_name": "Chandru",
                "profile": { "display_name": "Chandru", "real_name": "Chandru" }
              }
            }
            """));
    SlackApiClient client = new SlackApiClient(transport);

    Map<String, String> names =
        client.resolveUserNames(List.of("U09AFJBJ876", "U09AFJBJ876"));
    assertEquals("Chandru", names.get("U09AFJBJ876"));
    assertEquals("Chandru", client.resolveUserName("U09AFJBJ876"));
    assertEquals(1, transport.usersInfoCalls);
  }

  @Test
  void resolveUserNamesFallsBackToIdWhenUsersInfoFails() {
    MapTransport transport = new MapTransport();
    transport.put(
        "users.info",
        MAPPER.createObjectNode().put("ok", false).put("error", "missing_scope"));
    SlackApiClient client = new SlackApiClient(transport);

    Map<String, String> names = client.resolveUserNames(List.of("U09AFJBJ876"));
    assertEquals("U09AFJBJ876", names.get("U09AFJBJ876"));
    assertTrue(client.userNameCacheView().containsKey("U09AFJBJ876"));
  }

  private static final class MapTransport implements SlackApiClient.Transport {
    private final Map<String, JsonNode> byMethod = new LinkedHashMap<>();
    private int usersInfoCalls;

    void put(String method, JsonNode body) {
      byMethod.put(method, body);
    }

    @Override
    public JsonNode get(String method, Map<String, String> queryParams) throws IOException {
      if ("users.info".equals(method)) {
        usersInfoCalls++;
      }
      JsonNode body = byMethod.get(method);
      if (body == null) {
        throw new IOException("No fixture for " + method);
      }
      return body;
    }
  }
}
